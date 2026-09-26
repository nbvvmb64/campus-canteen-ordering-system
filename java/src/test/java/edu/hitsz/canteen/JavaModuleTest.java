package edu.hitsz.canteen;

import edu.hitsz.canteen.exception.BusinessException;
import edu.hitsz.canteen.exception.DataValidationException;
import edu.hitsz.canteen.model.Dish;
import edu.hitsz.canteen.model.Order;
import edu.hitsz.canteen.model.OrderItem;
import edu.hitsz.canteen.model.OrderLineRequest;
import edu.hitsz.canteen.model.OrderStatus;
import edu.hitsz.canteen.model.Role;
import edu.hitsz.canteen.model.SaleStatus;
import edu.hitsz.canteen.model.User;
import edu.hitsz.canteen.persistence.CsvCodec;
import edu.hitsz.canteen.persistence.CsvDatabase;
import edu.hitsz.canteen.service.AuthService;
import edu.hitsz.canteen.service.DishService;
import edu.hitsz.canteen.service.OrderService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class JavaModuleTest {
    private static final char[] PASSWORD = "StrongPass9".toCharArray();

    private JavaModuleTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one test-data directory argument.");
        }
        Path testRoot = Path.of(args[0]).toAbsolutePath().normalize();
        if (!"test-data".equals(testRoot.getFileName().toString())
                || testRoot.getParent() == null
                || !"build".equals(testRoot.getParent().getFileName().toString())) {
            throw new IllegalArgumentException("Refusing unsafe test directory: " + testRoot);
        }

        deleteTree(testRoot);
        Files.createDirectories(testRoot);
        List<TestCase> cases = List.of(
                new TestCase("CSV contract and codec", JavaModuleTest::testCsvContractAndCodec),
                new TestCase("registration, login and permissions", JavaModuleTest::testAuthenticationAndPermissions),
                new TestCase("dish CRUD and validation", JavaModuleTest::testDishCrudAndValidation),
                new TestCase("multi-item order, persistence and cancellation", JavaModuleTest::testOrderLifecycle),
                new TestCase("order validation and rollback", JavaModuleTest::testOrderValidationAndRollback),
                new TestCase("merchant order status transitions", JavaModuleTest::testStatusTransitions),
                new TestCase("malformed and missing CSV rejection", JavaModuleTest::testInvalidCsvRejection));

        int passed = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (int i = 0; i < cases.size(); i++) {
                TestCase testCase = cases.get(i);
                Path caseDir = testRoot.resolve(String.format("case-%02d", i + 1));
                try {
                    testCase.body().run(caseDir);
                    passed++;
                    System.out.println("PASS: " + testCase.name());
                } catch (Throwable failure) {
                    failures.add(testCase.name() + " -> " + failure);
                    System.out.println("FAIL: " + testCase.name());
                    failure.printStackTrace(System.out);
                } finally {
                    deleteTree(caseDir);
                }
            }
        } finally {
            deleteTree(testRoot);
        }

        System.out.println("RESULT: " + passed + "/" + cases.size() + " tests passed.");
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join(System.lineSeparator(), failures));
        }
    }

    private static void testCsvContractAndCodec(Path dataDir) throws Exception {
        new CsvDatabase(dataDir);
        assertExactFile(
                dataDir.resolve("users.csv"),
                "user_id,username,password_salt,password_hash,role,user_status,created_at\r\n");
        assertExactFile(
                dataDir.resolve("dishes.csv"),
                "dish_id,merchant_id,dish_name,unit_price,stock,sale_status,created_at,updated_at\r\n");
        assertExactFile(
                dataDir.resolve("orders.csv"),
                "order_id,student_id,merchant_id,order_time,order_status,total_amount,status_updated_at\r\n");
        assertExactFile(
                dataDir.resolve("order_items.csv"),
                "order_item_id,order_id,dish_id,dish_name,quantity,unit_price,subtotal\r\n");

        List<List<String>> source = List.of(
                List.of("a", "b"),
                List.of("逗号,字段", "双引号\"字段"),
                List.of("多行\r\n字段", "普通"));
        String encoded = CsvCodec.write(source);
        assertTrue(encoded.endsWith("\r\n"), "CSV must end with CRLF");
        assertEquals(source, CsvCodec.parse(encoded, "memory.csv"), "CSV round-trip");
    }

    private static void testAuthenticationAndPermissions(Path dataDir) throws Exception {
        Fixture fixture = new Fixture(dataDir);
        assertEquals(Role.MERCHANT, fixture.merchant.getRole(), "merchant role");
        assertEquals(Role.STUDENT, fixture.student.getRole(), "student role");
        assertEquals(
                fixture.student.getUserId(),
                fixture.auth.login("student001", PASSWORD).getUserId(),
                "student login");

        expectBusiness(
                () -> fixture.auth.register("student001", PASSWORD, Role.STUDENT),
                "账号已存在");
        expectBusiness(
                () -> fixture.auth.login("student001", "WrongPass9".toCharArray()),
                "账号或密码错误");
        expectBusiness(
                () -> fixture.auth.login(" student001 ", PASSWORD),
                "账号或密码错误");
        expectBusiness(
                () -> fixture.dishes.addDish(
                        fixture.student, "越权菜品", new BigDecimal("10.00"), 5),
                "权限不足");
        expectBusiness(
                () -> fixture.orders.createOrder(
                        fixture.merchant,
                        List.of(new OrderLineRequest("DSH000001", 1))),
                "权限不足");

        String usersCsv = Files.readString(dataDir.resolve("users.csv"), StandardCharsets.UTF_8);
        assertFalse(usersCsv.contains("StrongPass9"), "users.csv must not contain plaintext password");
        assertTrue(usersCsv.contains("PBKDF2") == false, "CSV stores values, not algorithm labels");
    }

    private static void testDishCrudAndValidation(Path dataDir) {
        Fixture fixture = new Fixture(dataDir);
        Dish first = fixture.dishes.addDish(
                fixture.merchant, "番茄炒蛋", new BigDecimal("12.50"), 8);
        assertEquals("DSH000001", first.getDishId(), "first dish ID");
        Dish updated = fixture.dishes.updateDish(
                fixture.merchant,
                first.getDishId(),
                "番茄炒蛋（大份）",
                new BigDecimal("14.00"),
                10);
        assertEquals("14.00", updated.getUnitPrice().toPlainString(), "updated price");
        assertEquals(10, updated.getStock(), "updated stock");

        expectBusiness(
                () -> fixture.dishes.addDish(
                        fixture.merchant,
                        "番茄炒蛋（大份）",
                        new BigDecimal("15.00"),
                        1),
                "完全同名");
        expectBusiness(
                () -> fixture.dishes.addDish(
                        fixture.merchant, "非法价格", new BigDecimal("1.001"), 1),
                "两位小数");
        expectBusiness(
                () -> fixture.dishes.updateDish(
                        fixture.merchant,
                        first.getDishId(),
                        "非法库存",
                        new BigDecimal("1.00"),
                        -1),
                "库存不能为负数");

        User otherMerchant =
                fixture.auth.register("merchant02", PASSWORD, Role.MERCHANT);
        expectBusiness(
                () -> fixture.dishes.updateDish(
                        otherMerchant,
                        first.getDishId(),
                        "抢改",
                        new BigDecimal("1.00"),
                        1),
                "其他商家");

        Dish deleted = fixture.dishes.deleteDish(fixture.merchant, first.getDishId());
        assertEquals(SaleStatus.OFF_SALE, deleted.getSaleStatus(), "logical deletion");
        expectBusiness(
                () -> fixture.dishes.deleteDish(fixture.merchant, first.getDishId()),
                "重复删除");
        assertTrue(
                fixture.dishes.listAvailableDishes(fixture.student).isEmpty(),
                "off-sale dish hidden from students");
        fixture.dishes.setSaleStatus(
                fixture.merchant, first.getDishId(), SaleStatus.ON_SALE);
        Dish second = fixture.dishes.addDish(
                fixture.merchant, "新菜品", new BigDecimal("9.00"), 3);
        assertEquals("DSH000002", second.getDishId(), "dish ID not reused");
        assertEquals(2, fixture.dishes.listMerchantDishes(fixture.merchant).size(), "merchant dishes");
    }

    private static void testOrderLifecycle(Path dataDir) {
        Fixture fixture = new Fixture(dataDir);
        Dish rice = fixture.dishes.addDish(
                fixture.merchant, "招牌\"鸡腿饭\",大份", new BigDecimal("18.50"), 20);
        Dish tea = fixture.dishes.addDish(
                fixture.merchant, "柠檬茶", new BigDecimal("8.00"), 12);

        Order order = fixture.orders.createOrder(
                fixture.student,
                List.of(
                        new OrderLineRequest(rice.getDishId(), 1),
                        new OrderLineRequest(tea.getDishId(), 1),
                        new OrderLineRequest(rice.getDishId(), 1)));
        assertEquals("45.00", order.getTotalAmount().toPlainString(), "order total");
        List<OrderItem> items = fixture.orders.getOrderItems(fixture.student, order.getOrderId());
        assertEquals(2, items.size(), "duplicate dish merged");
        OrderItem riceItem = items.stream()
                .filter(item -> item.getDishId().equals(rice.getDishId()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, riceItem.getQuantity(), "merged quantity");
        assertEquals("37.00", riceItem.getSubtotal().toPlainString(), "line subtotal");
        assertEquals(18, fixture.database.findDish(rice.getDishId()).orElseThrow().getStock(), "rice stock deducted");
        assertEquals(11, fixture.database.findDish(tea.getDishId()).orElseThrow().getStock(), "tea stock deducted");

        CsvDatabase restarted = new CsvDatabase(dataDir);
        assertEquals(
                "招牌\"鸡腿饭\",大份",
                restarted.findDish(rice.getDishId()).orElseThrow().getDishName(),
                "quoted CSV field persisted");
        assertEquals("45.00", restarted.findOrder(order.getOrderId())
                .orElseThrow().getTotalAmount().toPlainString(), "order persisted");

        AuthService restartedAuth = new AuthService(restarted);
        OrderService restartedOrders = new OrderService(restarted);
        User restartedStudent = restartedAuth.login("student001", PASSWORD);
        restartedOrders.cancelOrder(restartedStudent, order.getOrderId());
        assertEquals(20, restarted.findDish(rice.getDishId()).orElseThrow().getStock(), "rice stock restored");
        assertEquals(12, restarted.findDish(tea.getDishId()).orElseThrow().getStock(), "tea stock restored");
        assertEquals(
                OrderStatus.CANCELLED,
                restarted.findOrder(order.getOrderId()).orElseThrow().getOrderStatus(),
                "cancel status persisted");
        expectBusiness(
                () -> restartedOrders.cancelOrder(restartedStudent, order.getOrderId()),
                "重复取消");
        assertEquals(20, restarted.findDish(rice.getDishId()).orElseThrow().getStock(), "no second restoration");
    }

    private static void testOrderValidationAndRollback(Path dataDir) {
        Fixture fixture = new Fixture(dataDir);
        Dish first = fixture.dishes.addDish(
                fixture.merchant, "一号店菜品", new BigDecimal("10.00"), 5);
        User secondMerchant = fixture.auth.register("merchant02", PASSWORD, Role.MERCHANT);
        Dish second = fixture.dishes.addDish(
                secondMerchant, "二号店菜品", new BigDecimal("20.00"), 5);

        expectBusiness(
                () -> fixture.orders.createOrder(
                        fixture.student,
                        List.of(
                                new OrderLineRequest(first.getDishId(), 1),
                                new OrderLineRequest(second.getDishId(), 1))),
                "同一商家");
        assertEquals(5, fixture.database.findDish(first.getDishId()).orElseThrow().getStock(), "first stock unchanged");
        assertEquals(5, fixture.database.findDish(second.getDishId()).orElseThrow().getStock(), "second stock unchanged");
        assertTrue(fixture.database.orders().isEmpty(), "failed cross-merchant order not saved");
        assertTrue(fixture.database.orderItems().isEmpty(), "failed order items not saved");

        expectBusiness(
                () -> fixture.orders.createOrder(
                        fixture.student,
                        List.of(new OrderLineRequest(first.getDishId(), 0))),
                "必须大于0");
        expectBusiness(
                () -> fixture.orders.createOrder(
                        fixture.student,
                        List.of(new OrderLineRequest(first.getDishId(), 6))),
                "库存不足");
        fixture.dishes.setSaleStatus(
                fixture.merchant, first.getDishId(), SaleStatus.OFF_SALE);
        expectBusiness(
                () -> fixture.orders.createOrder(
                        fixture.student,
                        List.of(new OrderLineRequest(first.getDishId(), 1))),
                "已下架");
        assertEquals(5, fixture.database.findDish(first.getDishId()).orElseThrow().getStock(), "all failures preserve stock");
    }

    private static void testStatusTransitions(Path dataDir) {
        Fixture fixture = new Fixture(dataDir);
        Dish dish = fixture.dishes.addDish(
                fixture.merchant, "状态测试菜品", new BigDecimal("11.00"), 10);
        User otherMerchant = fixture.auth.register("merchant02", PASSWORD, Role.MERCHANT);

        Order preparing = fixture.orders.createOrder(
                fixture.student,
                List.of(new OrderLineRequest(dish.getDishId(), 1)));
        expectBusiness(
                () -> fixture.orders.updateOrderStatus(
                        otherMerchant, preparing.getOrderId(), OrderStatus.PREPARING),
                "其他商家");
        fixture.orders.updateOrderStatus(
                fixture.merchant, preparing.getOrderId(), OrderStatus.PREPARING);
        expectBusiness(
                () -> fixture.orders.cancelOrder(fixture.student, preparing.getOrderId()),
                "只有PLACED");
        fixture.orders.updateOrderStatus(
                fixture.merchant, preparing.getOrderId(), OrderStatus.COMPLETED);
        expectBusiness(
                () -> fixture.orders.updateOrderStatus(
                        fixture.merchant, preparing.getOrderId(), OrderStatus.PREPARING),
                "非法订单状态流转");
        expectBusiness(
                () -> fixture.orders.cancelOrder(fixture.student, preparing.getOrderId()),
                "只有PLACED");

        Order directComplete = fixture.orders.createOrder(
                fixture.student,
                List.of(new OrderLineRequest(dish.getDishId(), 1)));
        expectBusiness(
                () -> fixture.orders.updateOrderStatus(
                        fixture.merchant, directComplete.getOrderId(), OrderStatus.COMPLETED),
                "非法订单状态流转");
        assertEquals(
                OrderStatus.PLACED,
                fixture.database.findOrder(directComplete.getOrderId())
                        .orElseThrow().getOrderStatus(),
                "invalid transition leaves status unchanged");
    }

    private static void testInvalidCsvRejection(Path dataDir) throws Exception {
        new CsvDatabase(dataDir);
        Files.writeString(
                dataDir.resolve("orders.csv"),
                "bad_header\r\n",
                StandardCharsets.UTF_8);
        expectDataValidation(() -> new CsvDatabase(dataDir), "表头");

        deleteTree(dataDir);
        new CsvDatabase(dataDir);
        Files.delete(dataDir.resolve("order_items.csv"));
        expectDataValidation(() -> new CsvDatabase(dataDir), "不完整");
    }

    private static void assertExactFile(Path file, String expected) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        assertFalse(
                bytes.length >= 3
                        && (bytes[0] & 0xFF) == 0xEF
                        && (bytes[1] & 0xFF) == 0xBB
                        && (bytes[2] & 0xFF) == 0xBF,
                file.getFileName() + " must not contain BOM");
        assertEquals(expected, new String(bytes, StandardCharsets.UTF_8), file.getFileName().toString());
    }

    private static void expectBusiness(ThrowingRunnable action, String expectedMessage) {
        try {
            action.run();
        } catch (BusinessException e) {
            assertTrue(e.getMessage().contains(expectedMessage), "unexpected business message: " + e.getMessage());
            return;
        } catch (Exception e) {
            throw new AssertionError("Unexpected checked exception", e);
        }
        throw new AssertionError("Expected BusinessException containing: " + expectedMessage);
    }

    private static void expectDataValidation(ThrowingRunnable action, String expectedMessage) {
        try {
            action.run();
        } catch (DataValidationException e) {
            assertTrue(e.getMessage().contains(expectedMessage), "unexpected data message: " + e.getMessage());
            return;
        } catch (Exception e) {
            throw new AssertionError("Unexpected checked exception", e);
        }
        throw new AssertionError("Expected DataValidationException containing: " + expectedMessage);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void deleteTree(Path target) throws Exception {
        if (!Files.exists(target)) {
            return;
        }
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.toString().contains(
                Path.of("java", "build").toString())) {
            throw new IllegalArgumentException("Refusing unsafe recursive delete: " + normalized);
        }
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class Fixture {
        private final CsvDatabase database;
        private final AuthService auth;
        private final DishService dishes;
        private final OrderService orders;
        private final User merchant;
        private final User student;

        private Fixture(Path dataDir) {
            database = new CsvDatabase(dataDir);
            auth = new AuthService(database);
            dishes = new DishService(database);
            orders = new OrderService(database);
            merchant = auth.register("merchant01", PASSWORD, Role.MERCHANT);
            student = auth.register("student001", PASSWORD, Role.STUDENT);
        }
    }

    private record TestCase(String name, TestBody body) {
    }

    @FunctionalInterface
    private interface TestBody {
        void run(Path dataDir) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
