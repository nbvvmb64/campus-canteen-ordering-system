package edu.hitsz.canteen.web.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.hitsz.canteen.model.*;
import edu.hitsz.canteen.persistence.CsvCodec;
import edu.hitsz.canteen.persistence.CsvDatabase;
import edu.hitsz.canteen.util.DateTimes;
import edu.hitsz.canteen.util.Rules;
import edu.hitsz.canteen.web.ProjectPaths;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.*;

@Service
public class SnapshotExport {
    private static final List<String> NAMES = List.of("users.csv","dishes.csv","orders.csv","order_items.csv");
    private final ProjectPaths paths;
    private final DbStore db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final String pythonScript;

    public SnapshotExport(ProjectPaths paths, DbStore db, TransactionTemplate tx, ObjectMapper json,
                          @Value("${canteen.python-script:}") String pythonScript) {
        this.paths=paths; this.db=db; this.tx=tx; this.json=json;
        this.pythonScript=pythonScript;
    }
    public record Snapshot(long revision,List<User> users,List<Dish> dishes,List<Order> orders,List<OrderItem> items) { }

    public synchronized Path export() {
        return exportWithFault(() -> { });
    }

    /** Package-visible failure point for the pointer publication integration test. */
    synchronized Path exportWithFault(Runnable beforePointerPublication) {
        requireActive();
        Snapshot snapshot = tx.execute(status -> {
            long revision = db.gate();
            return new Snapshot(revision,db.users(),db.dishes(),db.orders(),db.items());
        });
        if (snapshot==null) throw new IllegalStateException("无法取得数据库快照");
        String generation="snap-"+UUID.randomUUID();
        Path stage=paths.exports().resolve(".stage-"+generation);
        Path published=paths.exports().resolve(generation);
        boolean pointerPublished=false;
        try {
            Files.createDirectories(paths.exports());
            Files.createDirectory(stage);
            write(stage,"users.csv",users(snapshot.users()));
            write(stage,"dishes.csv",dishes(snapshot.dishes()));
            write(stage,"orders.csv",orders(snapshot.orders()));
            write(stage,"order_items.csv",items(snapshot.items()));
            CsvDatabase validation = new CsvDatabase(stage);
            if (validation.users().size()!=snapshot.users().size()
                    || validation.dishes().size()!=snapshot.dishes().size()
                    || validation.orders().size()!=snapshot.orders().size()
                    || validation.orderItems().size()!=snapshot.items().size())
                throw new IllegalStateException("快照行数校验失败");
            Map<String,String> hashes=new LinkedHashMap<>();
            for (String name : NAMES) hashes.put(name,sha256(stage.resolve(name)));
            Files.move(stage,published,StandardCopyOption.ATOMIC_MOVE);
            Map<String,Object> pointer=new LinkedHashMap<>();
            pointer.put("generation",generation);
            pointer.put("business_revision",snapshot.revision());
            pointer.put("generated_at",ZonedDateTime.now(DateTimes.ZONE).withNano(0).toOffsetDateTime().toString());
            pointer.put("sha256",hashes);
            Path temporary=paths.data().resolve(".current_export."+UUID.randomUUID()+".tmp");
            try {
                json.writeValue(temporary.toFile(),pointer);
                beforePointerPublication.run();
                Files.move(temporary,paths.pointer(),StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                pointerPublished=true;
            } finally { Files.deleteIfExists(temporary); }
            tx.executeWithoutResult(status -> { db.gate(); db.exported(snapshot.revision()); });
            System.out.println("已发布完整CSV快照: " + generation + " revision=" + snapshot.revision());
            return published;
        } catch (Exception failure) {
            if (pointerPublished) {
                throw new IllegalStateException("CSV快照已发布，但数据库导出状态记录失败；请核验指针后重试",failure);
            }
            if (!pointerPublished) deletePublishedOrphan(published);
            try { tx.executeWithoutResult(status -> { db.gate(); db.exportFailed(failure.toString()); }); }
            catch (Exception recordFailure) { failure.addSuppressed(recordFailure); }
            throw new IllegalStateException("CSV快照导出失败；上一完整版本仍有效",failure);
        } finally {
            deleteStage(stage);
        }
    }

    public void analyzeLatest() throws Exception {
        requireActive();
        if (!Files.isRegularFile(paths.pointer())) throw new IllegalStateException("尚无已发布的CSV快照");
        JsonNode pointer=json.readTree(paths.pointer().toFile());
        String generation=pointer.path("generation").asText();
        if (!generation.matches("snap-[0-9a-f-]{36}")) throw new IllegalStateException("快照版本号非法");
        Path version=paths.exports().resolve(generation).toAbsolutePath().normalize();
        if (!version.startsWith(paths.exports().toAbsolutePath().normalize()) || !Files.isDirectory(version))
            throw new IllegalStateException("快照目录无效");
        for (String name : List.of("dishes.csv","orders.csv","order_items.csv")) {
            String expected=pointer.path("sha256").path(name).asText();
            if (!expected.matches("[0-9a-f]{64}") || !expected.equals(sha256(version.resolve(name))))
                throw new IllegalStateException("快照指纹校验失败: " + name);
        }
        long revision=pointer.path("business_revision").asLong(-1);
        if (revision<0) throw new IllegalStateException("快照版本号缺失");
        Long current=tx.execute(status -> db.gate());
        System.out.println("分析快照: " + generation + "，生成时间 " + pointer.path("generated_at").asText());
        long lag=current-revision;
        System.out.println(lag==0 ? "分析快照为最新业务版本" : "分析快照滞后 " + lag + " 次业务提交");
        if (lag<0) throw new IllegalStateException("快照版本超过数据库版本，拒绝分析");
        Path temp=paths.webVar().resolve("tmp");
        Path matplotlib=paths.webVar().resolve("matplotlib-cache");
        Files.createDirectories(temp);
        Files.createDirectories(matplotlib);
        String python=System.getenv().getOrDefault("CANTEEN_PYTHON","python");
        Path script=pythonScript.isBlank()?paths.root().resolve("python/src/run_analysis.py")
                :Path.of(pythonScript).toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) throw new IllegalStateException("找不到Python分析入口");
        ProcessBuilder command=new ProcessBuilder(python,"-B",script.toString(),
                "--data-dir",version.toString(),"--output-dir",paths.root().resolve("output").toString());
        command.directory(paths.root().toFile());
        command.environment().put("PYTHONDONTWRITEBYTECODE","1");
        command.environment().put("TMP",temp.toString());
        command.environment().put("TEMP",temp.toString());
        command.environment().put("MPLCONFIGDIR",matplotlib.toString());
        command.inheritIO();
        int exit=command.start().waitFor();
        if (exit!=0) throw new IllegalStateException("Python分析失败，旧output保持不变，退出码="+exit);
    }

    private void requireActive() {
        try {
            if (!Files.isRegularFile(paths.marker()) || !"ACTIVE".equals(Files.readString(paths.marker()).trim()))
                throw new IllegalStateException("网页模式尚未完成迁移");
        } catch (IOException e) { throw new IllegalStateException("无法读取网页模式标记",e); }
    }
    private static void write(Path dir,String name,List<List<String>> rows) throws IOException {
        Files.writeString(dir.resolve(name),CsvCodec.write(rows),StandardCharsets.UTF_8);
    }
    private static List<List<String>> users(List<User> records) {
        List<List<String>> rows=new ArrayList<>();
        rows.add(List.of("user_id","username","password_salt","password_hash","role","user_status","created_at"));
        for (User u:records) rows.add(List.of(u.getUserId(),u.getUsername(),u.getPasswordSalt(),u.getPasswordHash(),
                u.getRole().name(),u.getUserStatus().name(),DateTimes.format(u.getCreatedAt())));
        return rows;
    }
    private static List<List<String>> dishes(List<Dish> records) {
        List<List<String>> rows=new ArrayList<>();
        rows.add(List.of("dish_id","merchant_id","dish_name","unit_price","stock","sale_status","created_at","updated_at"));
        for (Dish d:records) rows.add(List.of(d.getDishId(),d.getMerchantId(),d.getDishName(),Rules.moneyText(d.getUnitPrice()),
                Integer.toString(d.getStock()),d.getSaleStatus().name(),DateTimes.format(d.getCreatedAt()),DateTimes.format(d.getUpdatedAt())));
        return rows;
    }
    private static List<List<String>> orders(List<Order> records) {
        List<List<String>> rows=new ArrayList<>();
        rows.add(List.of("order_id","student_id","merchant_id","order_time","order_status","total_amount","status_updated_at"));
        for (Order o:records) rows.add(List.of(o.getOrderId(),o.getStudentId(),o.getMerchantId(),DateTimes.format(o.getOrderTime()),
                o.getOrderStatus().name(),Rules.moneyText(o.getTotalAmount()),DateTimes.format(o.getStatusUpdatedAt())));
        return rows;
    }
    private static List<List<String>> items(List<OrderItem> records) {
        List<List<String>> rows=new ArrayList<>();
        rows.add(List.of("order_item_id","order_id","dish_id","dish_name","quantity","unit_price","subtotal"));
        for (OrderItem i:records) rows.add(List.of(i.getOrderItemId(),i.getOrderId(),i.getDishId(),i.getDishName(),
                Integer.toString(i.getQuantity()),Rules.moneyText(i.getUnitPrice()),Rules.moneyText(i.getSubtotal())));
        return rows;
    }
    private static String sha256(Path path) {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) { throw new IllegalStateException("无法读取快照文件: "+path.getFileName(),e); }
    }
    private void deleteStage(Path stage) {
        if (!stage.toAbsolutePath().normalize().startsWith(paths.exports().toAbsolutePath().normalize())
                || !stage.getFileName().toString().startsWith(".stage-snap-")) return;
        if (!Files.exists(stage)) return;
        try (var files=Files.walk(stage)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
    private void deletePublishedOrphan(Path directory) {
        if (!directory.toAbsolutePath().normalize().startsWith(paths.exports().toAbsolutePath().normalize())
                || !directory.getFileName().toString().matches("snap-[0-9a-f-]{36}")) return;
        if (!Files.exists(directory)) return;
        try (var files=Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
