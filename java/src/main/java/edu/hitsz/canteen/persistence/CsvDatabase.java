package edu.hitsz.canteen.persistence;

import edu.hitsz.canteen.exception.BusinessException;
import edu.hitsz.canteen.exception.DataValidationException;
import edu.hitsz.canteen.model.Dish;
import edu.hitsz.canteen.model.Order;
import edu.hitsz.canteen.model.OrderItem;
import edu.hitsz.canteen.model.OrderStatus;
import edu.hitsz.canteen.model.Role;
import edu.hitsz.canteen.model.SaleStatus;
import edu.hitsz.canteen.model.User;
import edu.hitsz.canteen.model.UserStatus;
import edu.hitsz.canteen.util.DateTimes;
import edu.hitsz.canteen.util.Rules;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class CsvDatabase {
    private static final List<String> USER_HEADER = List.of(
            "user_id", "username", "password_salt", "password_hash",
            "role", "user_status", "created_at");
    private static final List<String> DISH_HEADER = List.of(
            "dish_id", "merchant_id", "dish_name", "unit_price",
            "stock", "sale_status", "created_at", "updated_at");
    private static final List<String> ORDER_HEADER = List.of(
            "order_id", "student_id", "merchant_id", "order_time",
            "order_status", "total_amount", "status_updated_at");
    private static final List<String> ITEM_HEADER = List.of(
            "order_item_id", "order_id", "dish_id", "dish_name",
            "quantity", "unit_price", "subtotal");

    private static final Pattern USER_ID = Pattern.compile("USR\\d{6}");
    private static final Pattern DISH_ID = Pattern.compile("DSH\\d{6}");
    private static final Pattern ORDER_ID = Pattern.compile("ORD\\d{6}");
    private static final Pattern ITEM_ID = Pattern.compile("ITM\\d{6}");

    private final Path dataDir;
    private final Path usersFile;
    private final Path dishesFile;
    private final Path ordersFile;
    private final Path itemsFile;

    private LinkedHashMap<String, User> users = new LinkedHashMap<>();
    private LinkedHashMap<String, Dish> dishes = new LinkedHashMap<>();
    private LinkedHashMap<String, Order> orders = new LinkedHashMap<>();
    private LinkedHashMap<String, OrderItem> orderItems = new LinkedHashMap<>();

    public CsvDatabase(Path dataDir) {
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.usersFile = this.dataDir.resolve("users.csv");
        this.dishesFile = this.dataDir.resolve("dishes.csv");
        this.ordersFile = this.dataDir.resolve("orders.csv");
        this.itemsFile = this.dataDir.resolve("order_items.csv");
        initializeAndLoad();
    }

    public Path getDataDir() {
        return dataDir;
    }

    public synchronized Optional<User> findUser(String userId) {
        return Optional.ofNullable(users.get(userId));
    }

    public synchronized Optional<User> findUserByUsername(String username) {
        return users.values().stream()
                .filter(user -> user.getUsername().equals(username))
                .findFirst();
    }

    public synchronized Optional<Dish> findDish(String dishId) {
        return Optional.ofNullable(dishes.get(dishId));
    }

    public synchronized Optional<Order> findOrder(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    public synchronized List<User> users() {
        return new ArrayList<>(users.values());
    }

    public synchronized List<Dish> dishes() {
        return new ArrayList<>(dishes.values());
    }

    public synchronized List<Order> orders() {
        return new ArrayList<>(orders.values());
    }

    public synchronized List<OrderItem> orderItems() {
        return new ArrayList<>(orderItems.values());
    }

    public synchronized List<OrderItem> orderItemsFor(String orderId) {
        return orderItems.values().stream()
                .filter(item -> item.getOrderId().equals(orderId))
                .sorted(Comparator.comparing(OrderItem::getOrderItemId))
                .toList();
    }

    public synchronized boolean hasBusinessData() {
        return !users.isEmpty() || !dishes.isEmpty() || !orders.isEmpty() || !orderItems.isEmpty();
    }

    public synchronized String nextUserId() {
        return nextId("USR", users.keySet());
    }

    public synchronized String nextDishId() {
        return nextId("DSH", dishes.keySet());
    }

    public synchronized String nextOrderId() {
        return nextId("ORD", orders.keySet());
    }

    public synchronized String nextOrderItemId() {
        return nextId("ITM", orderItems.keySet());
    }

    public synchronized void addUser(User user) {
        users.put(user.getUserId(), user);
    }

    public synchronized void addDish(Dish dish) {
        dishes.put(dish.getDishId(), dish);
    }

    public synchronized void addOrder(Order order) {
        orders.put(order.getOrderId(), order);
    }

    public synchronized void addOrderItem(OrderItem item) {
        orderItems.put(item.getOrderItemId(), item);
    }

    public synchronized <T> T transaction(Supplier<T> work) {
        return LegacyWriteGate.officialWrite(dataDir, () -> transactLocked(work));
    }

    private <T> T transactLocked(Supplier<T> work) {
        State before = snapshot();
        try {
            T result = work.get();
            validateAll();
            saveAllAtomic();
            return result;
        } catch (RuntimeException e) {
            restore(before);
            throw e;
        }
    }

    public synchronized void transaction(Runnable work) {
        transaction(() -> {
            work.run();
            return null;
        });
    }

    private void initializeAndLoad() {
        try {
            Files.createDirectories(dataDir);
            int existing = 0;
            for (Path file : List.of(usersFile, dishesFile, ordersFile, itemsFile)) {
                if (Files.exists(file)) {
                    existing++;
                }
            }
            if (existing == 0) {
                LegacyWriteGate.rejectIfCutOver(dataDir);
                writeInitial(usersFile, USER_HEADER);
                writeInitial(dishesFile, DISH_HEADER);
                writeInitial(ordersFile, ORDER_HEADER);
                writeInitial(itemsFile, ITEM_HEADER);
            } else if (existing != 4) {
                throw new DataValidationException(
                        "data目录中的四个正式CSV不完整，拒绝自动补齐以避免掩盖数据丢失");
            }
            loadAll();
            validateAll();
        } catch (IOException e) {
            throw new DataValidationException("无法初始化或读取CSV数据: " + e.getMessage(), e);
        }
    }

    private void writeInitial(Path file, List<String> header) throws IOException {
        String content = CsvCodec.write(List.of(header));
        Files.writeString(
                file,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private void loadAll() throws IOException {
        LinkedHashMap<String, User> loadedUsers = new LinkedHashMap<>();
        for (List<String> row : readRows(usersFile, USER_HEADER)) {
            User user = new User(
                    row.get(0),
                    row.get(1),
                    row.get(2),
                    row.get(3),
                    parseEnum(Role.class, row.get(4), "role"),
                    parseEnum(UserStatus.class, row.get(5), "user_status"),
                    DateTimes.parse(row.get(6), "created_at"));
            putUnique(loadedUsers, user.getUserId(), user, "users.csv user_id");
        }

        LinkedHashMap<String, Dish> loadedDishes = new LinkedHashMap<>();
        for (List<String> row : readRows(dishesFile, DISH_HEADER)) {
            Dish dish = new Dish(
                    row.get(0),
                    row.get(1),
                    row.get(2),
                    Rules.parseStoredMoney(row.get(3), "unit_price", true),
                    Rules.parseStoredInt(row.get(4), "stock", false),
                    parseEnum(SaleStatus.class, row.get(5), "sale_status"),
                    DateTimes.parse(row.get(6), "created_at"),
                    DateTimes.parse(row.get(7), "updated_at"));
            putUnique(loadedDishes, dish.getDishId(), dish, "dishes.csv dish_id");
        }

        LinkedHashMap<String, Order> loadedOrders = new LinkedHashMap<>();
        for (List<String> row : readRows(ordersFile, ORDER_HEADER)) {
            Order order = new Order(
                    row.get(0),
                    row.get(1),
                    row.get(2),
                    DateTimes.parse(row.get(3), "order_time"),
                    parseEnum(OrderStatus.class, row.get(4), "order_status"),
                    Rules.parseStoredMoney(row.get(5), "total_amount", true),
                    DateTimes.parse(row.get(6), "status_updated_at"));
            putUnique(loadedOrders, order.getOrderId(), order, "orders.csv order_id");
        }

        LinkedHashMap<String, OrderItem> loadedItems = new LinkedHashMap<>();
        for (List<String> row : readRows(itemsFile, ITEM_HEADER)) {
            OrderItem item = new OrderItem(
                    row.get(0),
                    row.get(1),
                    row.get(2),
                    row.get(3),
                    Rules.parseStoredInt(row.get(4), "quantity", true),
                    Rules.parseStoredMoney(row.get(5), "unit_price", true),
                    Rules.parseStoredMoney(row.get(6), "subtotal", true));
            putUnique(loadedItems, item.getOrderItemId(), item, "order_items.csv order_item_id");
        }

        users = loadedUsers;
        dishes = loadedDishes;
        orders = loadedOrders;
        orderItems = loadedItems;
    }

    private List<List<String>> readRows(Path file, List<String> expectedHeader) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) {
            throw new DataValidationException(file.getFileName() + " 不允许包含UTF-8 BOM");
        }
        List<List<String>> rows = CsvCodec.parse(content, file.getFileName().toString());
        if (rows.isEmpty()) {
            throw new DataValidationException(file.getFileName() + " 为空，必须至少包含固定表头");
        }
        if (!rows.get(0).equals(expectedHeader)) {
            throw new DataValidationException(
                    file.getFileName() + " 表头或字段顺序不符合系统接口约定");
        }
        List<List<String>> dataRows = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() != expectedHeader.size()) {
                throw new DataValidationException(
                        file.getFileName() + " 第" + (i + 1) + "行字段数错误");
            }
            boolean allEmpty = row.stream().allMatch(String::isEmpty);
            if (allEmpty) {
                throw new DataValidationException(
                        file.getFileName() + " 第" + (i + 1) + "行为空行");
            }
            dataRows.add(row);
        }
        return dataRows;
    }

    private void validateAll() {
        validateUsers();
        validateDishes();
        validateOrdersAndItems();
    }

    private void validateUsers() {
        Set<String> usernames = new HashSet<>();
        for (User user : users.values()) {
            requireId(user.getUserId(), USER_ID, "user_id");
            if (!user.getUsername().equals(user.getUsername().trim())
                    || user.getUsername().length() < 3
                    || user.getUsername().length() > 30) {
                throw new DataValidationException("username不符合长度或首尾空白规则: " + user.getUserId());
            }
            if (!usernames.add(user.getUsername())) {
                throw new DataValidationException("username重复: " + user.getUsername());
            }
            requireBase64(user.getPasswordSalt(), "password_salt", user.getUserId());
            requireBase64(user.getPasswordHash(), "password_hash", user.getUserId());
        }
    }

    private void validateDishes() {
        for (Dish dish : dishes.values()) {
            requireId(dish.getDishId(), DISH_ID, "dish_id");
            requireId(dish.getMerchantId(), USER_ID, "merchant_id");
            User merchant = users.get(dish.getMerchantId());
            if (merchant == null
                    || merchant.getRole() != Role.MERCHANT
                    || merchant.getUserStatus() != UserStatus.ACTIVE) {
                throw new DataValidationException(
                        "菜品merchant_id未指向有效商家: " + dish.getDishId());
            }
            if (!dish.getDishName().equals(dish.getDishName().trim())
                    || dish.getDishName().isEmpty()
                    || dish.getDishName().length() > 50) {
                throw new DataValidationException("dish_name不符合规则: " + dish.getDishId());
            }
            requirePositiveMoney(dish.getUnitPrice(), "unit_price", dish.getDishId());
            if (dish.getStock() < 0) {
                throw new DataValidationException("stock不能为负数: " + dish.getDishId());
            }
            if (dish.getUpdatedAt().isBefore(dish.getCreatedAt())) {
                throw new DataValidationException("updated_at早于created_at: " + dish.getDishId());
            }
        }
    }

    private void validateOrdersAndItems() {
        Set<String> orderDishPairs = new HashSet<>();
        Map<String, List<OrderItem>> groupedItems = new HashMap<>();

        for (OrderItem item : orderItems.values()) {
            requireId(item.getOrderItemId(), ITEM_ID, "order_item_id");
            requireId(item.getOrderId(), ORDER_ID, "order_id");
            requireId(item.getDishId(), DISH_ID, "dish_id");
            Order order = orders.get(item.getOrderId());
            Dish dish = dishes.get(item.getDishId());
            if (order == null) {
                throw new DataValidationException(
                        "订单明细引用不存在的order_id: " + item.getOrderItemId());
            }
            if (dish == null) {
                throw new DataValidationException(
                        "订单明细引用不存在的dish_id: " + item.getOrderItemId());
            }
            if (!dish.getMerchantId().equals(order.getMerchantId())) {
                throw new DataValidationException(
                        "订单明细菜品不属于订单商家: " + item.getOrderItemId());
            }
            if (item.getDishName().isBlank() || item.getDishName().length() > 50) {
                throw new DataValidationException("dish_name快照不符合规则: " + item.getOrderItemId());
            }
            if (item.getQuantity() <= 0) {
                throw new DataValidationException("quantity必须大于0: " + item.getOrderItemId());
            }
            requirePositiveMoney(item.getUnitPrice(), "unit_price", item.getOrderItemId());
            requirePositiveMoney(item.getSubtotal(), "subtotal", item.getOrderItemId());
            BigDecimal expectedSubtotal = item.getUnitPrice()
                    .multiply(BigDecimal.valueOf(item.getQuantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            if (expectedSubtotal.compareTo(item.getSubtotal()) != 0) {
                throw new DataValidationException("明细小计不一致: " + item.getOrderItemId());
            }
            if (!orderDishPairs.add(item.getOrderId() + "\u0000" + item.getDishId())) {
                throw new DataValidationException(
                        "(order_id,dish_id)重复: " + item.getOrderId() + "/" + item.getDishId());
            }
            groupedItems.computeIfAbsent(item.getOrderId(), ignored -> new ArrayList<>()).add(item);
        }

        for (Order order : orders.values()) {
            requireId(order.getOrderId(), ORDER_ID, "order_id");
            requireId(order.getStudentId(), USER_ID, "student_id");
            requireId(order.getMerchantId(), USER_ID, "merchant_id");
            User student = users.get(order.getStudentId());
            User merchant = users.get(order.getMerchantId());
            if (student == null
                    || student.getRole() != Role.STUDENT
                    || student.getUserStatus() != UserStatus.ACTIVE) {
                throw new DataValidationException(
                        "订单student_id未指向有效学生: " + order.getOrderId());
            }
            if (merchant == null
                    || merchant.getRole() != Role.MERCHANT
                    || merchant.getUserStatus() != UserStatus.ACTIVE) {
                throw new DataValidationException(
                        "订单merchant_id未指向有效商家: " + order.getOrderId());
            }
            requirePositiveMoney(order.getTotalAmount(), "total_amount", order.getOrderId());
            if (order.getStatusUpdatedAt().isBefore(order.getOrderTime())) {
                throw new DataValidationException(
                        "status_updated_at早于order_time: " + order.getOrderId());
            }
            List<OrderItem> items = groupedItems.getOrDefault(order.getOrderId(), List.of());
            if (items.isEmpty()) {
                throw new DataValidationException("订单没有明细: " + order.getOrderId());
            }
            BigDecimal expectedTotal = items.stream()
                    .map(OrderItem::getSubtotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            if (expectedTotal.compareTo(order.getTotalAmount()) != 0) {
                throw new DataValidationException("订单总金额不一致: " + order.getOrderId());
            }
        }
    }

    private void saveAllAtomic() {
        String token = UUID.randomUUID().toString();
        LinkedHashMap<Path, String> contents = new LinkedHashMap<>();
        contents.put(usersFile, serializeUsers());
        contents.put(dishesFile, serializeDishes());
        contents.put(ordersFile, serializeOrders());
        contents.put(itemsFile, serializeItems());

        LinkedHashMap<Path, Path> tempFiles = new LinkedHashMap<>();
        LinkedHashMap<Path, Path> backupFiles = new LinkedHashMap<>();
        try {
            for (Map.Entry<Path, String> entry : contents.entrySet()) {
                Path target = entry.getKey();
                Path temp = dataDir.resolve("." + target.getFileName() + "." + token + ".tmp");
                Path backup = dataDir.resolve("." + target.getFileName() + "." + token + ".bak");
                Files.writeString(
                        temp,
                        entry.getValue(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                tempFiles.put(target, temp);
                backupFiles.put(target, backup);
            }
            for (Map.Entry<Path, Path> entry : tempFiles.entrySet()) {
                moveReplacing(entry.getValue(), entry.getKey());
            }
        } catch (IOException writeFailure) {
            IOException rollbackFailure = null;
            for (Map.Entry<Path, Path> entry : backupFiles.entrySet()) {
                try {
                    if (Files.exists(entry.getValue())) {
                        Files.copy(
                                entry.getValue(),
                                entry.getKey(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    rollbackFailure = e;
                }
            }
            BusinessException exception =
                    new BusinessException("CSV保存失败，业务操作已回滚: " + writeFailure.getMessage(), writeFailure);
            if (rollbackFailure != null) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            deleteQuietly(tempFiles.values());
            deleteQuietly(backupFiles.values());
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Collection<Path> paths) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // 下次启动前不读取隐藏临时文件；交付前脚本会再次检查。
            }
        }
    }

    private String serializeUsers() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(USER_HEADER);
        users.values().stream()
                .sorted(Comparator.comparing(User::getUserId))
                .map(user -> List.of(
                        user.getUserId(),
                        user.getUsername(),
                        user.getPasswordSalt(),
                        user.getPasswordHash(),
                        user.getRole().name(),
                        user.getUserStatus().name(),
                        DateTimes.format(user.getCreatedAt())))
                .forEach(rows::add);
        return CsvCodec.write(rows);
    }

    private String serializeDishes() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(DISH_HEADER);
        dishes.values().stream()
                .sorted(Comparator.comparing(Dish::getDishId))
                .map(dish -> List.of(
                        dish.getDishId(),
                        dish.getMerchantId(),
                        dish.getDishName(),
                        Rules.moneyText(dish.getUnitPrice()),
                        Integer.toString(dish.getStock()),
                        dish.getSaleStatus().name(),
                        DateTimes.format(dish.getCreatedAt()),
                        DateTimes.format(dish.getUpdatedAt())))
                .forEach(rows::add);
        return CsvCodec.write(rows);
    }

    private String serializeOrders() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(ORDER_HEADER);
        orders.values().stream()
                .sorted(Comparator.comparing(Order::getOrderId))
                .map(order -> List.of(
                        order.getOrderId(),
                        order.getStudentId(),
                        order.getMerchantId(),
                        DateTimes.format(order.getOrderTime()),
                        order.getOrderStatus().name(),
                        Rules.moneyText(order.getTotalAmount()),
                        DateTimes.format(order.getStatusUpdatedAt())))
                .forEach(rows::add);
        return CsvCodec.write(rows);
    }

    private String serializeItems() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(ITEM_HEADER);
        orderItems.values().stream()
                .sorted(Comparator.comparing(OrderItem::getOrderItemId))
                .map(item -> List.of(
                        item.getOrderItemId(),
                        item.getOrderId(),
                        item.getDishId(),
                        item.getDishName(),
                        Integer.toString(item.getQuantity()),
                        Rules.moneyText(item.getUnitPrice()),
                        Rules.moneyText(item.getSubtotal())))
                .forEach(rows::add);
        return CsvCodec.write(rows);
    }

    private State snapshot() {
        LinkedHashMap<String, User> usersCopy = new LinkedHashMap<>(users);
        LinkedHashMap<String, Dish> dishesCopy = new LinkedHashMap<>();
        dishes.forEach((id, dish) -> dishesCopy.put(id, new Dish(dish)));
        LinkedHashMap<String, Order> ordersCopy = new LinkedHashMap<>();
        orders.forEach((id, order) -> ordersCopy.put(id, new Order(order)));
        LinkedHashMap<String, OrderItem> itemsCopy = new LinkedHashMap<>(orderItems);
        return new State(usersCopy, dishesCopy, ordersCopy, itemsCopy);
    }

    private void restore(State state) {
        users = state.users;
        dishes = state.dishes;
        orders = state.orders;
        orderItems = state.orderItems;
    }

    private static String nextId(String prefix, Collection<String> ids) {
        int max = 0;
        for (String id : ids) {
            if (id.startsWith(prefix) && id.length() == prefix.length() + 6) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // validateAll会提供更明确的错误。
                }
            }
        }
        if (max >= 999_999) {
            throw new BusinessException(prefix + "编号已耗尽");
        }
        return prefix + String.format("%06d", max + 1);
    }

    private static <T> void putUnique(
            Map<String, T> map, String key, T value, String fieldName) {
        if (map.putIfAbsent(key, value) != null) {
            throw new DataValidationException(fieldName + "重复: " + key);
        }
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> enumType, String value, String fieldName) {
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException e) {
            throw new DataValidationException(fieldName + "枚举值非法: " + value, e);
        }
    }

    private static void requireId(String id, Pattern pattern, String fieldName) {
        if (!pattern.matcher(id).matches()) {
            throw new DataValidationException(fieldName + "格式错误: " + id);
        }
    }

    private static void requireBase64(String value, String fieldName, String recordId) {
        try {
            if (Base64.getDecoder().decode(value).length == 0) {
                throw new IllegalArgumentException("empty");
            }
        } catch (IllegalArgumentException e) {
            throw new DataValidationException(fieldName + "不是有效Base64: " + recordId, e);
        }
    }

    private static void requirePositiveMoney(
            BigDecimal value, String fieldName, String recordId) {
        if (value.scale() != 2 || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DataValidationException(
                    fieldName + "必须大于0且固定两位小数: " + recordId);
        }
    }

    private static final class State {
        private final LinkedHashMap<String, User> users;
        private final LinkedHashMap<String, Dish> dishes;
        private final LinkedHashMap<String, Order> orders;
        private final LinkedHashMap<String, OrderItem> orderItems;

        private State(
                LinkedHashMap<String, User> users,
                LinkedHashMap<String, Dish> dishes,
                LinkedHashMap<String, Order> orders,
                LinkedHashMap<String, OrderItem> orderItems) {
            this.users = users;
            this.dishes = dishes;
            this.orders = orders;
            this.orderItems = orderItems;
        }
    }
}
