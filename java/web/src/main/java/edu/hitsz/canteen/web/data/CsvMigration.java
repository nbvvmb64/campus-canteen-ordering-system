package edu.hitsz.canteen.web.data;

import edu.hitsz.canteen.model.*;
import edu.hitsz.canteen.persistence.CsvDatabase;
import edu.hitsz.canteen.persistence.LegacyWriteGate;
import edu.hitsz.canteen.web.ProjectPaths;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class CsvMigration {
    private static final List<String> NAMES = List.of("users.csv","dishes.csv","orders.csv","order_items.csv");
    private final ProjectPaths paths;
    private final DbStore store;
    private final TransactionTemplate tx;
    private final boolean oldConsolesStopped;

    public CsvMigration(ProjectPaths paths, DbStore store, TransactionTemplate tx,
                        @Value("${canteen.legacy-console-stopped:false}") boolean oldConsolesStopped) {
        this.paths = paths; this.store = store; this.tx = tx;
        this.oldConsolesStopped = oldConsolesStopped;
    }

    public void migrate() {
        Path dir = paths.data();
        if (!LegacyWriteGate.isOfficialData(dir)) throw new IllegalStateException("迁移源不是项目正式data目录");
        requireAllFiles(dir);
        String preflight = digest(dir);
        LegacyWriteGate.withCutoverLock(dir, () -> {
            if (Files.isRegularFile(paths.marker())
                    && "ACTIVE".equals(read(paths.marker()).trim())) {
                throw new IllegalStateException("网页模式已经启用，禁止再次迁移");
            }
            writeMarker("MIGRATING");
            verifyOldConsoleStopped();
            if (!preflight.equals(digest(dir))) throw new IllegalStateException("预检后原CSV发生变化，迁移已中止");
            CsvDatabase source = new CsvDatabase(dir); // Strictly validates all four CSVs.
            tx.executeWithoutResult(status -> {
                store.gate();
                JdbcTemplate db = store.jdbc();
                LocalDateTime imported = db.queryForObject(
                        "SELECT imported_at FROM business_revision WHERE id=1", (rs, n) -> {
                            var value = rs.getTimestamp(1); return value == null ? null : value.toLocalDateTime();
                        });
                if (imported != null) {
                    String prior = db.queryForObject("SELECT source_digest FROM business_revision WHERE id=1", String.class);
                    if (!preflight.equals(prior)) throw new IllegalStateException("数据库已从另一版CSV导入，拒绝覆盖");
                    return; // Previous commit succeeded but marker publication was interrupted.
                }
                for (String table : List.of("users","dishes","orders","order_items")) {
                    Integer count = db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
                    if (count != 0) throw new IllegalStateException("数据库非空，拒绝导入: " + table);
                }
                source.users().forEach(store::addUser);
                source.dishes().forEach(store::addDish);
                source.orders().forEach(store::addOrder);
                source.orderItems().forEach(store::addItem);
                counter("USR", source.users().stream().map(User::getUserId).toList());
                counter("DSH", source.dishes().stream().map(Dish::getDishId).toList());
                counter("ORD", source.orders().stream().map(Order::getOrderId).toList());
                counter("ITM", source.orderItems().stream().map(OrderItem::getOrderItemId).toList());
                for (String table : List.of("users","dishes","orders","order_items")) {
                    Integer count = db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
                    int expected = switch (table) {
                        case "users" -> source.users().size();
                        case "dishes" -> source.dishes().size();
                        case "orders" -> source.orders().size();
                        default -> source.orderItems().size();
                    };
                    if (count != expected) throw new IllegalStateException("迁移行数不一致: " + table);
                }
                db.update("UPDATE business_revision SET imported_at=CURRENT_TIMESTAMP,source_digest=? WHERE id=1", preflight);
            });
            if (!preflight.equals(digest(dir))) throw new IllegalStateException("迁移期间原CSV发生变化，请人工检查");
            writeMarker("ACTIVE");
            System.out.println("迁移成功；数据库成为唯一正式业务数据源，旧控制台正式写入已禁用。");
            return null;
        });
    }

    private void counter(String prefix, List<String> ids) {
        int next = ids.stream().mapToInt(id -> Integer.parseInt(id.substring(3))).max().orElse(0) + 1;
        store.jdbc().update("INSERT INTO id_counters(prefix,next_number) VALUES (?,?)", prefix, next);
    }

    private void verifyOldConsoleStopped() {
        if (!oldConsolesStopped) throw new IllegalStateException(
                "迁移前必须停止并核实全部旧控制台进程；请通过迁移脚本明确声明已完成核查");
        long current = ProcessHandle.current().pid();
        for (ProcessHandle process : ProcessHandle.allProcesses().toList()) {
            if (process.pid() == current) continue;
            var info = process.info();
            String command = info.command().orElse("").toLowerCase();
            if (!command.endsWith("java.exe") && !command.endsWith("javaw.exe")) continue;
            String line = info.commandLine().orElse("");
            if (line.contains("edu.hitsz.canteen.App") || line.contains("edu.hitsz.canteen.DemoScenario")) {
                throw new IllegalStateException("旧控制台进程仍在运行 (PID " + process.pid() + ")，迁移已中止");
            }
        }
    }

    private static void requireAllFiles(Path dir) {
        for (String name : NAMES) if (!Files.isRegularFile(dir.resolve(name))) {
            throw new IllegalStateException("缺少正式迁移源: " + name);
        }
    }

    private static String digest(Path dir) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String name : NAMES) {
                md.update(name.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(dir.resolve(name)));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) { throw new IllegalStateException("无法校验四份CSV", e); }
    }

    private void writeMarker(String value) {
        try {
            Path temporary = paths.data().resolve(".web-mode." + UUID.randomUUID() + ".tmp");
            try {
                Files.writeString(temporary, value + "\n", StandardCharsets.UTF_8);
                Files.move(temporary, paths.marker(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException e) { throw new IllegalStateException("无法原子发布网页模式标记", e); }
    }

    private static String read(Path path) {
        try { return Files.readString(path, StandardCharsets.UTF_8); }
        catch (IOException e) { throw new IllegalStateException("无法读取网页模式标记", e); }
    }
}
