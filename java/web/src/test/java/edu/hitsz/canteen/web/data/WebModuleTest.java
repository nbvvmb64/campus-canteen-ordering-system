package edu.hitsz.canteen.web.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.hitsz.canteen.exception.BusinessException;
import edu.hitsz.canteen.model.*;
import edu.hitsz.canteen.persistence.CsvDatabase;
import edu.hitsz.canteen.persistence.LegacyWriteGate;
import edu.hitsz.canteen.service.AuthService;
import edu.hitsz.canteen.service.DishService;
import edu.hitsz.canteen.service.OrderService;
import edu.hitsz.canteen.web.BusinessService;
import edu.hitsz.canteen.web.ForbiddenException;
import edu.hitsz.canteen.web.IdempotencyConflictException;
import edu.hitsz.canteen.web.MerchantDashboard;
import edu.hitsz.canteen.web.ProjectPaths;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"canteen.mode=test", "canteen.legacy-console-stopped=true"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebModuleTest {
    private static final Path ROOT = testRoot();
    private static String merchantId;
    private static String otherMerchantId;
    private static String studentId;
    private static String otherStudentId;
    private static String sourceDishId;
    private static String sourceOrderId;
    private static byte[][] sourceHashes;

    static {
        try {
            if (Files.exists(ROOT)) {
                try (var paths=Files.walk(ROOT)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.delete(path); } catch (IOException e) { throw new RuntimeException(e); }
                    });
                }
            }
            Files.createDirectories(ROOT.resolve("docs"));
            Files.createDirectories(ROOT.resolve("data"));
            Files.writeString(ROOT.resolve("README.md"),"test",StandardCharsets.UTF_8);
            Files.writeString(ROOT.resolve("docs/ARCHITECTURE.md"),"test",StandardCharsets.UTF_8);
            CsvDatabase source=new CsvDatabase(ROOT.resolve("data"));
            AuthService auth=new AuthService(source);
            DishService dishes=new DishService(source);
            OrderService orders=new OrderService(source);
            User merchant=auth.register("merchant_one","StrongPass9".toCharArray(),Role.MERCHANT);
            User other=auth.register("merchant_two","StrongPass9".toCharArray(),Role.MERCHANT);
            User student=auth.register("student_one","StrongPass9".toCharArray(),Role.STUDENT);
            User otherStudent=auth.register("student_two","StrongPass9".toCharArray(),Role.STUDENT);
            merchantId=merchant.getUserId(); otherMerchantId=other.getUserId();
            studentId=student.getUserId(); otherStudentId=otherStudent.getUserId();
            Dish dish=dishes.addDish(merchant,"原始菜品",new java.math.BigDecimal("12.50"),5);
            dishes.addDish(other,"隔离商家菜品",new java.math.BigDecimal("8.00"),3);
            sourceDishId=dish.getDishId();
            sourceOrderId=orders.createOrder(student,List.of(new OrderLineRequest(sourceDishId,2))).getOrderId();
            sourceHashes=new byte[4][];
            int index=0;
            for (String name:List.of("users.csv","dishes.csv","orders.csv","order_items.csv"))
                sourceHashes[index++]=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(ROOT.resolve("data").resolve(name)));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private static Path testRoot() {
        try {
            Path classes=Path.of(WebModuleTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return classes.getParent().resolve("test-data/web-module").toAbsolutePath().normalize();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("canteen.project-root",() -> ROOT.toString());
    }

    @Autowired CsvMigration migration;
    @Autowired DbStore db;
    @Autowired BusinessService business;
    @Autowired MerchantDashboard dashboard;
    @Autowired SnapshotExport snapshots;
    @Autowired ProjectPaths paths;
    @Autowired ObjectMapper json;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    @Autowired CheckedFailureService checkedFailure;

    @Test @org.junit.jupiter.api.Order(1)
    void migrationPreservesSourceAndRejectsRepeat() throws Exception {
        migration.migrate();
        assertEquals(4,db.users().size());
        assertEquals(2,db.dishes().size());
        assertEquals(1,db.orders().size());
        assertEquals(1,db.items().size());
        assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM order_idempotency",Integer.class));
        assertEquals("ACTIVE",Files.readString(paths.marker()).trim());
        int index=0;
        for (String name:List.of("users.csv","dishes.csv","orders.csv","order_items.csv"))
            assertArrayEquals(sourceHashes[index++],MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(paths.data().resolve(name))));
        assertThrows(IllegalStateException.class, migration::migrate);
        assertThrows(BusinessException.class,() -> new CsvDatabase(paths.data()).transaction(() -> null));
    }

    @Test @org.junit.jupiter.api.Order(2)
    void ownershipAndHttpAccess() throws Exception {
        assertThrows(ForbiddenException.class,() -> business.updateDish(otherMerchantId,sourceDishId,
                "越权",new java.math.BigDecimal("1.00"),1));
        assertThrows(ForbiddenException.class,() -> business.cancel(otherStudentId,sourceOrderId));
        assertThrows(ForbiddenException.class,() -> business.updateOrderStatus(otherMerchantId,
                sourceOrderId,OrderStatus.PREPARING));
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/data/users.csv").with(user(merchantId).roles("MERCHANT")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/data/current_export.json").with(user(merchantId).roles("MERCHANT")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/merchants/me/dashboard")
                .with(user(studentId).roles("STUDENT"))).andExpect(status().isForbidden());
        String body=mvc.perform(get("/api/v1/merchants/me/dashboard")
                .with(user(merchantId).roles("MERCHANT"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("merchant-dashboard.v1"));
        assertFalse(body.contains("隔离商家菜品"));
        assertFalse(body.contains("password_hash"));
    }

    @Test @org.junit.jupiter.api.Order(3)
    void gateIsHeldUntilCommitAndOneLastDishCannotOversell() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(2);
        CountDownLatch locked=new CountDownLatch(1), release=new CountDownLatch(1);
        try {
            Future<?> first=workers.submit(() -> tx.executeWithoutResult(status -> {
                db.gate(); locked.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            Future<?> second=workers.submit(() -> tx.executeWithoutResult(status -> db.gate()));
            assertThrows(TimeoutException.class,() -> second.get(200,TimeUnit.MILLISECONDS));
            release.countDown(); first.get(5,TimeUnit.SECONDS); second.get(5,TimeUnit.SECONDS);
        } finally { release.countDown(); workers.shutdownNow(); }

        Dish single=business.addDish(merchantId,"最后一份",new java.math.BigDecimal("3.00"),1);
        CountDownLatch start=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for (String student:List.of(studentId,otherStudentId)) results.add(pool.submit(() -> {
                start.await();
                try { business.createOrder(student,UUID.randomUUID().toString(),
                        List.of(new OrderLineRequest(single.getDishId(),1))); return true; }
                catch (BusinessException expected) { return false; }
            }));
            start.countDown();
            int success=0;
            for (Future<Boolean> result:results) if (result.get(8,TimeUnit.SECONDS)) success++;
            assertEquals(1,success);
            assertEquals(0,db.dish(single.getDishId()).orElseThrow().getStock());
        } finally { pool.shutdownNow(); }
    }

    @Test @org.junit.jupiter.api.Order(4)
    void cancellationAndAcceptanceRaceHasOneWinner() throws Exception {
        Dish dish=business.addDish(merchantId,"竞争菜品",new java.math.BigDecimal("6.00"),1);
        String orderId=business.createOrder(studentId,UUID.randomUUID().toString(),
                List.of(new OrderLineRequest(dish.getDishId(),1))).getOrderId();
        CountDownLatch start=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> cancel=pool.submit(() -> {start.await(); try {business.cancel(studentId,orderId);return true;}
                catch (BusinessException e) {return false;}});
            Future<Boolean> accept=pool.submit(() -> {start.await(); try {business.updateOrderStatus(merchantId,orderId,OrderStatus.PREPARING);return true;}
                catch (BusinessException e) {return false;}});
            start.countDown();
            boolean cancelled=cancel.get(8,TimeUnit.SECONDS), accepted=accept.get(8,TimeUnit.SECONDS);
            assertNotEquals(cancelled,accepted);
            assertEquals(cancelled?OrderStatus.CANCELLED:OrderStatus.PREPARING,
                    db.order(orderId).orElseThrow().getOrderStatus());
            assertEquals(cancelled?1:0,db.dish(dish.getDishId()).orElseThrow().getStock());
        } finally { pool.shutdownNow(); }
    }

    @Test @org.junit.jupiter.api.Order(5)
    void exportIsCompleteFailureKeepsPointerAndFingerprintsAreChecked() throws Exception {
        Path version=snapshots.export();
        CsvDatabase exported=new CsvDatabase(version);
        assertEquals(db.orders().size(),exported.orders().size());
        byte[] pointerBefore=Files.readAllBytes(paths.pointer());
        assertThrows(IllegalStateException.class,() -> snapshots.exportWithFault(() -> {
            throw new IllegalStateException("publication failure injection");
        }));
        assertArrayEquals(pointerBefore,Files.readAllBytes(paths.pointer()));
        var pointer=json.readTree(paths.pointer().toFile());
        assertEquals(db.currentRevision(),pointer.path("business_revision").asLong());
        Path input=version.resolve("dishes.csv");
        byte[] before=Files.readAllBytes(input);
        try {
            Files.writeString(input,"corrupt",StandardCharsets.UTF_8);
            assertThrows(IllegalStateException.class,snapshots::analyzeLatest);
        } finally { Files.write(input,before); }
    }

    @Test @org.junit.jupiter.api.Order(6)
    void exportConcurrentWithOrderKeepsFourTablesOnOneRevision() throws Exception {
        Dish dish=business.addDish(merchantId,"导出竞争菜品",new java.math.BigDecimal("4.00"),2);
        long beforeRevision=db.currentRevision();
        int beforeOrders=db.orders().size();
        CountDownLatch start=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Path> export=pool.submit(() -> {start.await();return snapshots.export();});
            Future<?> order=pool.submit(() -> {start.await();business.createOrder(studentId,UUID.randomUUID().toString(),
                    List.of(new OrderLineRequest(dish.getDishId(),1)));return null;});
            start.countDown();
            Path version=export.get(10,TimeUnit.SECONDS);
            order.get(10,TimeUnit.SECONDS);
            CsvDatabase csv=new CsvDatabase(version);
            long exportedRevision=json.readTree(paths.pointer().toFile()).path("business_revision").asLong();
            assertTrue(exportedRevision==beforeRevision || exportedRevision==beforeRevision+1);
            assertEquals(beforeOrders+(exportedRevision-beforeRevision),csv.orders().size());
            assertEquals(2-(exportedRevision-beforeRevision),csv.findDish(dish.getDishId()).orElseThrow().getStock());
        } finally { pool.shutdownNow(); }
    }

    @Test @org.junit.jupiter.api.Order(7)
    void checkedFailureRollsBackStock() {
        int before=db.dish(sourceDishId).orElseThrow().getStock();
        assertThrows(IOException.class,() -> checkedFailure.failAfterStockUpdate(sourceDishId));
        assertEquals(before,db.dish(sourceDishId).orElseThrow().getStock());
    }

    @Test @org.junit.jupiter.api.Order(8)
    void migrationSqlFailureRollsBackAndKeepsOriginalCsv() throws Exception {
        Path invalid=ROOT.getParent().resolve("migration-failure");
        if (Files.exists(invalid)) {
            assertTrue(invalid.toAbsolutePath().normalize().startsWith(ROOT.getParent().toAbsolutePath().normalize()));
            try (var files=Files.walk(invalid)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.delete(path); } catch (IOException e) { throw new RuntimeException(e); }
                });
            }
        }
        Files.createDirectories(invalid.resolve("docs"));
        Files.createDirectories(invalid.resolve("data"));
        Files.writeString(invalid.resolve("README.md"),"test",StandardCharsets.UTF_8);
        Files.writeString(invalid.resolve("docs/ARCHITECTURE.md"),"test",StandardCharsets.UTF_8);
        CsvDatabase source=new CsvDatabase(invalid.resolve("data"));
        User merchant=new AuthService(source).register("bad_merchant","StrongPass9".toCharArray(),Role.MERCHANT);
        new DishService(source).addDish(merchant,"超大价格",new java.math.BigDecimal("999999999999.99"),1);
        byte[] original=Files.readAllBytes(invalid.resolve("data/dishes.csv"));
        ProjectPaths otherPaths=new ProjectPaths(invalid.toString());
        Files.createDirectories(otherPaths.webVar());
        DriverManagerDataSource sourceDb=new DriverManagerDataSource(
                "jdbc:h2:file:"+otherPaths.database().toString().replace('\\','/'),"sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(sourceDb);
        DbStore otherStore=new DbStore(new org.springframework.jdbc.core.JdbcTemplate(sourceDb));
        TransactionTemplate otherTx=new TransactionTemplate(new DataSourceTransactionManager(sourceDb));
        CsvMigration otherMigration=new CsvMigration(otherPaths,otherStore,otherTx,true);
        assertThrows(RuntimeException.class,otherMigration::migrate);
        assertEquals(0,otherStore.users().size());
        assertEquals(0,otherStore.dishes().size());
        assertArrayEquals(original,Files.readAllBytes(invalid.resolve("data/dishes.csv")));
        assertEquals("MIGRATING",Files.readString(otherPaths.marker()).trim());
    }

    @Test @org.junit.jupiter.api.Order(9)
    void oldWriterAndCutoverUseTheSameCrossProcessLock() throws Exception {
        Path root=ROOT.getParent().resolve("cross-process-cutover");
        Files.createDirectories(root.resolve("docs"));
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("README.md"),"test",StandardCharsets.UTF_8);
        Files.writeString(root.resolve("docs/ARCHITECTURE.md"),"test",StandardCharsets.UTF_8);
        Files.deleteIfExists(root.resolve("data/.web-mode"));
        Path mainClasses=CsvDatabase.class.getProtectionDomain().getCodeSource().getLocation().toURI()==null
                ? null:Path.of(CsvDatabase.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path testClasses=Path.of(LegacyGateProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String classpath=mainClasses+java.io.File.pathSeparator+testClasses;
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",classpath,LegacyGateProbe.class.getName(),root.resolve("data").toString())
                .redirectErrorStream(true).start();
        try {
            String first=new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream())).readLine();
            assertEquals("LOCKED",first);
            long started=System.nanoTime();
            LegacyWriteGate.withCutoverLock(root.resolve("data"),() -> {
                try { Files.writeString(root.resolve("data/.web-mode"),"ACTIVE",StandardCharsets.UTF_8); }
                catch (IOException e) { throw new RuntimeException(e); }
                return null;
            });
            assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()>=800);
            assertEquals(0,child.waitFor());
            assertThrows(BusinessException.class,() -> LegacyWriteGate.officialWrite(root.resolve("data"),() -> null));
        } finally { child.destroyForcibly(); }
    }

    @Test @org.junit.jupiter.api.Order(10)
    void keyedOrderReplaysOriginalCreationAndRejectsChangedPayload() throws Exception {
        Dish dish=business.addDish(merchantId,"幂等菜品",new java.math.BigDecimal("5.00"),5);
        String key=UUID.randomUUID().toString();
        long revision=db.currentRevision();
        int orders=db.orders().size();
        String body="{\"lines\":[{\"dishId\":\""+dish.getDishId()+"\",\"quantity\":1}]}";
        String first=mvc.perform(post("/api/v1/orders").with(user(studentId).roles("STUDENT"))
                .with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId=json.readTree(first).path("orderId").asText();
        assertFalse(orderId.isEmpty());
        business.updateOrderStatus(merchantId,orderId,OrderStatus.PREPARING);
        long afterStatusRevision=db.currentRevision();
        String replay=mvc.perform(post("/api/v1/orders").with(user(studentId).roles("STUDENT"))
                .with(csrf()).header("Idempotency-Key",key.toUpperCase())
                .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(first),json.readTree(replay));
        assertEquals(afterStatusRevision,db.currentRevision());
        assertEquals(orders+1,db.orders().size());
        assertEquals(4,db.dish(dish.getDishId()).orElseThrow().getStock());
        assertEquals(revision+2,afterStatusRevision);
        mvc.perform(post("/api/v1/orders").with(user(studentId).roles("STUDENT"))
                .with(csrf()).header("Idempotency-Key",key).contentType("application/json")
                .content("{\"lines\":[{\"dishId\":\""+dish.getDishId()+"\",\"quantity\":2}]}"))
                .andExpect(status().isConflict());
        assertEquals(afterStatusRevision,db.currentRevision());
        assertEquals(orders+1,db.orders().size());
    }

    @Test @org.junit.jupiter.api.Order(11)
    void canonicalLinesAndStudentScopeAreStable() {
        Dish a=business.addDish(merchantId,"规范化甲",new java.math.BigDecimal("2.00"),10);
        Dish b=business.addDish(merchantId,"规范化乙",new java.math.BigDecimal("3.00"),10);
        String key=UUID.randomUUID().toString();
        int before=db.orders().size();
        long revision=db.currentRevision();
        Order first=business.createOrder(studentId,key,List.of(
                new OrderLineRequest(a.getDishId(),1),new OrderLineRequest(b.getDishId(),1),
                new OrderLineRequest(" "+a.getDishId()+" ",1)));
        Order replay=business.createOrder(studentId,key,List.of(
                new OrderLineRequest(b.getDishId(),1),new OrderLineRequest(a.getDishId(),2)));
        assertEquals(first.getOrderId(),replay.getOrderId());
        assertEquals(first.getOrderTime(),replay.getOrderTime());
        assertEquals(revision+1,db.currentRevision());
        Order other=business.createOrder(otherStudentId,key,List.of(new OrderLineRequest(a.getDishId(),2),
                new OrderLineRequest(b.getDishId(),1)));
        assertNotEquals(first.getOrderId(),other.getOrderId());
        assertEquals(before+2,db.orders().size());
        assertEquals(6,db.dish(a.getDishId()).orElseThrow().getStock());
    }

    @Test @org.junit.jupiter.api.Order(12)
    void sameKeyConcurrentCreatesOnlyOneOrderAndRollbackFreesKey() throws Exception {
        Dish dish=business.addDish(merchantId,"并发幂等菜品",new java.math.BigDecimal("7.00"),3);
        String key=UUID.randomUUID().toString();
        long revision=db.currentRevision();
        int before=db.orders().size();
        CountDownLatch start=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Order>> results=new ArrayList<>();
            for (int i=0;i<2;i++) results.add(pool.submit(() -> {
                start.await();
                return business.createOrder(studentId,key,List.of(new OrderLineRequest(dish.getDishId(),1)));
            }));
            start.countDown();
            Order one=results.get(0).get(8,TimeUnit.SECONDS);
            Order two=results.get(1).get(8,TimeUnit.SECONDS);
            assertEquals(one.getOrderId(),two.getOrderId());
            assertEquals(one.getOrderTime(),two.getOrderTime());
            assertEquals(before+1,db.orders().size());
            assertEquals(2,db.dish(dish.getDishId()).orElseThrow().getStock());
            assertEquals(revision+1,db.currentRevision());
        } finally { start.countDown(); pool.shutdownNow(); }

        String rolledBackKey=UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class,() -> tx.executeWithoutResult(status -> {
            business.createOrder(studentId,rolledBackKey,List.of(new OrderLineRequest(dish.getDishId(),1)));
            throw new IllegalStateException("simulate failure after writes");
        }));
        assertEquals(before+1,db.orders().size());
        assertEquals(2,db.dish(dish.getDishId()).orElseThrow().getStock());
        assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM order_idempotency WHERE student_id=? AND request_key=?",
                Integer.class,studentId,rolledBackKey));
        business.createOrder(studentId,rolledBackKey,List.of(new OrderLineRequest(dish.getDishId(),1)));
        assertEquals(before+2,db.orders().size());
        assertEquals(1,db.dish(dish.getDishId()).orElseThrow().getStock());
    }

    @Test @org.junit.jupiter.api.Order(13)
    void missingInvalidAndIndependentKeysFollowHttpContract() throws Exception {
        Dish dish=business.addDish(merchantId,"请求键校验菜品",new java.math.BigDecimal("9.00"),3);
        String body="{\"lines\":[{\"dishId\":\""+dish.getDishId()+"\",\"quantity\":1}]}";
        int before=db.orders().size();
        long revision=db.currentRevision();
        mvc.perform(post("/api/v1/orders").with(user(studentId).roles("STUDENT"))
                .with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").with(user(studentId).roles("STUDENT"))
                .with(csrf()).header("Idempotency-Key","00000000-0000-1000-8000-000000000000")
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        assertEquals(before,db.orders().size());
        assertEquals(revision,db.currentRevision());
        mvc.perform(post("/api/v1/orders").with(user(studentId).roles("STUDENT"))
                .with(csrf()).header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/orders").with(user(studentId).roles("STUDENT"))
                .with(csrf()).header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content(body)).andExpect(status().isCreated());
        assertEquals(before+2,db.orders().size());
        assertEquals(1,db.dish(dish.getDishId()).orElseThrow().getStock());
    }
}
