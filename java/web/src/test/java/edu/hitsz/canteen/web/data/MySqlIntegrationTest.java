package edu.hitsz.canteen.web.data;

import edu.hitsz.canteen.exception.BusinessException;
import edu.hitsz.canteen.model.Dish;
import edu.hitsz.canteen.model.OrderLineRequest;
import edu.hitsz.canteen.model.Order;
import edu.hitsz.canteen.model.OrderStatus;
import edu.hitsz.canteen.model.Role;
import edu.hitsz.canteen.persistence.CsvDatabase;
import edu.hitsz.canteen.web.BusinessService;
import edu.hitsz.canteen.web.IdempotencyConflictException;
import edu.hitsz.canteen.web.ProjectPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CANTEEN_MYSQL_TEST", matches = "1")
@ActiveProfiles("mysql")
@SpringBootTest(properties = {"canteen.mode=test", "canteen.db-engine=mysql"})
class MySqlIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("canteen.project-root", () -> System.getenv("CANTEEN_PROJECT_ROOT"));
        registry.add("canteen.h2-source", () -> System.getenv("CANTEEN_H2_SOURCE"));
        registry.add("canteen.python-script", () -> System.getenv("CANTEEN_PYTHON_SCRIPT"));
    }

    @Autowired H2ToMySqlImport importer;
    @Autowired BusinessService business;
    @Autowired DbStore store;
    @Autowired ProjectPaths paths;
    @Autowired SnapshotExport snapshots;
    @Autowired MySqlToH2Rollback rollback;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    void isolatedSevenTableImportAndMySqlBusinessContracts() throws Exception {
        importer.run();
        assertThrows(IllegalStateException.class, importer::run);
        assertEquals(3, store.users().size());
        assertEquals(3, store.dishes().size());
        assertEquals(4, store.orders().size());
        assertEquals(7, store.items().size());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_idempotency", Integer.class));
        assertEquals(0L, store.currentRevision());
        assertEquals(5, jdbc.queryForObject("SELECT next_number FROM id_counters WHERE prefix='ORD'", Integer.class));

        String merchant = store.users().stream().filter(u -> u.getRole() == Role.MERCHANT).findFirst().orElseThrow().getUserId();
        String student = store.users().stream().filter(u -> u.getRole() == Role.STUDENT).findFirst().orElseThrow().getUserId();
        String secondStudent = business.register("mysql_student", "StrongPass9".toCharArray(), Role.STUDENT).getUserId();
        business.register("CaseSensitive", "StrongPass9".toCharArray(), Role.STUDENT);
        business.register("casesensitive", "StrongPass9".toCharArray(), Role.STUDENT);
        Dish dish = business.addDish(merchant, "中文，测试", new BigDecimal("12.50"), 1);
        assertEquals("中文，测试", store.dish(dish.getDishId()).orElseThrow().getDishName());
        String key = UUID.randomUUID().toString();

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successful = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await();
                try { business.createOrder(student, key, List.of(new OrderLineRequest(dish.getDishId(), 1))); successful.incrementAndGet(); }
                catch (BusinessException ignored) { } return null; });
            var second = pool.submit(() -> { start.await();
                try { business.createOrder(secondStudent, UUID.randomUUID().toString(),
                        List.of(new OrderLineRequest(dish.getDishId(), 1))); successful.incrementAndGet(); }
                catch (BusinessException ignored) { } return null; });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(1, successful.get());
        assertEquals(0, store.dish(dish.getDishId()).orElseThrow().getStock());

        Dish sameKeyDish = business.addDish(merchant, "同键竞争", new BigDecimal("2.00"), 1);
        String sameKey = UUID.randomUUID().toString();
        long beforeSameKey = store.currentRevision();
        CountDownLatch sameStart = new CountDownLatch(1);
        var samePool = Executors.newFixedThreadPool(2);
        try {
            var first = samePool.submit(() -> { sameStart.await(); return business.createOrder(student, sameKey,
                    List.of(new OrderLineRequest(sameKeyDish.getDishId(), 1))); });
            var second = samePool.submit(() -> { sameStart.await(); return business.createOrder(student, sameKey,
                    List.of(new OrderLineRequest(sameKeyDish.getDishId(), 1))); });
            sameStart.countDown();
            Order a = first.get(10, TimeUnit.SECONDS);
            Order b = second.get(10, TimeUnit.SECONDS);
            assertEquals(a.getOrderId(), b.getOrderId());
        } finally { samePool.shutdownNow(); }
        assertEquals(beforeSameKey + 1, store.currentRevision());
        assertEquals(0, store.dish(sameKeyDish.getDishId()).orElseThrow().getStock());

        // A separate stocked dish proves same-key replay, payload conflict and rollback.
        Dish replayDish = business.addDish(merchant, "幂等验证", new BigDecimal("6.25"), 3);
        String replayKey = UUID.randomUUID().toString();
        var created = business.createOrder(student, replayKey,
                List.of(new OrderLineRequest(replayDish.getDishId(), 2)));
        assertEquals(new BigDecimal("12.50"), created.getTotalAmount());
        long revision = store.currentRevision();
        var replay = business.createOrder(student, replayKey.toUpperCase(),
                List.of(new OrderLineRequest(replayDish.getDishId(), 2)));
        assertEquals(created.getOrderId(), replay.getOrderId());
        assertEquals(revision, store.currentRevision());
        assertThrows(IdempotencyConflictException.class, () -> business.createOrder(student, replayKey,
                List.of(new OrderLineRequest(replayDish.getDishId(), 1))));
        assertEquals(1, store.dish(replayDish.getDishId()).orElseThrow().getStock());
        String rollbackKey = UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            business.createOrder(student, rollbackKey, List.of(new OrderLineRequest(replayDish.getDishId(), 1)));
            throw new IllegalStateException("forced rollback");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_idempotency WHERE student_id=? AND request_key=?",
                Integer.class, student, rollbackKey));
        assertEquals(1, store.dish(replayDish.getDishId()).orElseThrow().getStock());
        business.cancel(student, created.getOrderId());
        assertEquals(OrderStatus.CANCELLED, store.order(created.getOrderId()).orElseThrow().getOrderStatus());
        assertEquals(3, store.dish(replayDish.getDishId()).orElseThrow().getStock());

        Path version = snapshots.export();
        CsvDatabase csv = new CsvDatabase(version);
        assertEquals(store.orders().size(), csv.orders().size());
        snapshots.analyzeLatest();
        assertTrue(Files.isRegularFile(paths.root().resolve("output/analysis/dashboard_data.json")));

        Path restored = rollback.prepare();
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:file:" + restored.toString().replace('\\', '/') + ";IFEXISTS=TRUE", "sa", "");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT revision FROM business_revision WHERE id=1")) {
                assertTrue(rows.next());
                assertEquals(store.currentRevision(), rows.getLong(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM order_idempotency")) {
                assertTrue(rows.next());
                assertTrue(rows.getInt(1) >= 2);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM orders")) {
                assertTrue(rows.next());
                assertEquals(store.orders().size(), rows.getInt(1));
                assertTrue(rows.getInt(1) > 4);
            }
            try (var rows = statement.executeQuery("SELECT next_number FROM id_counters WHERE prefix='ORD'")) {
                assertTrue(rows.next());
                assertTrue(rows.getInt(1) > 5);
            }
        }
        assertThrows(IllegalStateException.class, rollback::prepare);
    }
}
