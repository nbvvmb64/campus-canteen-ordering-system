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
import edu.hitsz.canteen.persistence.CsvDatabase;
import edu.hitsz.canteen.persistence.LegacyWriteGate;
import edu.hitsz.canteen.service.AuthService;
import edu.hitsz.canteen.service.DishService;
import edu.hitsz.canteen.service.OrderService;
import edu.hitsz.canteen.util.DateTimes;
import edu.hitsz.canteen.util.Rules;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class App {
    private final ConsoleIo io;
    private final AuthService authService;
    private final DishService dishService;
    private final OrderService orderService;

    private App(CsvDatabase database) {
        this.io = new ConsoleIo();
        this.authService = new AuthService(database);
        this.dishService = new DishService(database);
        this.orderService = new OrderService(database);
    }

    public static void main(String[] args) {
        try {
            Path dataDir = resolveDataDir(args);
            LegacyWriteGate.rejectIfCutOver(dataDir);
            CsvDatabase database = new CsvDatabase(dataDir);
            new App(database).run();
        } catch (DataValidationException e) {
            System.err.println("数据校验失败：" + e.getMessage());
            System.exit(2);
        } catch (RuntimeException e) {
            System.err.println("系统启动失败：" + e.getMessage());
            System.exit(1);
        }
    }

    private void run() {
        io.println("校园餐厅订餐系统（控制台版）");
        while (true) {
            io.println("");
            io.println("1. 学生注册");
            io.println("2. 商家注册");
            io.println("3. 登录");
            io.println("0. 退出");
            switch (io.readChoice("请选择：", 0, 3)) {
                case 1 -> register(Role.STUDENT);
                case 2 -> register(Role.MERCHANT);
                case 3 -> login();
                case 0 -> {
                    io.println("已退出系统。");
                    return;
                }
                default -> throw new IllegalStateException("不可达分支");
            }
        }
    }

    private void register(Role role) {
        try {
            String username = io.readLine("账号：");
            char[] password = io.readSecret("密码（8～128个字符）：");
            try {
                User user = authService.register(username, password, role);
                io.println("注册成功，用户编号：" + user.getUserId());
            } finally {
                Arrays.fill(password, '\0');
            }
        } catch (BusinessException e) {
            io.println("注册失败：" + e.getMessage());
        }
    }

    private void login() {
        try {
            String username = io.readLine("账号：");
            char[] password = io.readSecret("密码：");
            User user;
            try {
                user = authService.login(username, password);
            } finally {
                Arrays.fill(password, '\0');
            }
            io.println("登录成功，角色：" + user.getRole());
            if (user.getRole() == Role.STUDENT) {
                studentMenu(user);
            } else {
                merchantMenu(user);
            }
        } catch (BusinessException e) {
            io.println("登录失败：" + e.getMessage());
        }
    }

    private void studentMenu(User student) {
        while (true) {
            io.println("");
            io.println("【学生菜单】");
            io.println("1. 查看在售菜单");
            io.println("2. 创建多菜品订单");
            io.println("3. 查看我的订单");
            io.println("4. 取消订单");
            io.println("0. 退出登录");
            int choice = io.readChoice("请选择：", 0, 4);
            try {
                switch (choice) {
                    case 1 -> printDishes(dishService.listAvailableDishes(student));
                    case 2 -> createOrder(student);
                    case 3 -> printOrders(student, orderService.listStudentOrders(student));
                    case 4 -> cancelOrder(student);
                    case 0 -> {
                        return;
                    }
                    default -> throw new IllegalStateException("不可达分支");
                }
            } catch (BusinessException e) {
                io.println("操作失败：" + e.getMessage());
            }
        }
    }

    private void merchantMenu(User merchant) {
        while (true) {
            io.println("");
            io.println("【商家菜单】");
            io.println("1. 查看我的菜品");
            io.println("2. 添加菜品");
            io.println("3. 修改菜品名称、价格和库存");
            io.println("4. 删除菜品（逻辑下架）");
            io.println("5. 设置菜品上架/下架");
            io.println("6. 查看本店订单");
            io.println("7. 更新订单状态");
            io.println("8. 查看低库存菜品");
            io.println("0. 退出登录");
            int choice = io.readChoice("请选择：", 0, 8);
            try {
                switch (choice) {
                    case 1 -> printDishes(dishService.listMerchantDishes(merchant));
                    case 2 -> addDish(merchant);
                    case 3 -> updateDish(merchant);
                    case 4 -> deleteDish(merchant);
                    case 5 -> setSaleStatus(merchant);
                    case 6 -> printOrders(merchant, orderService.listMerchantOrders(merchant));
                    case 7 -> updateOrderStatus(merchant);
                    case 8 -> showLowStock(merchant);
                    case 0 -> {
                        return;
                    }
                    default -> throw new IllegalStateException("不可达分支");
                }
            } catch (BusinessException e) {
                io.println("操作失败：" + e.getMessage());
            }
        }
    }

    private void createOrder(User student) {
        printDishes(dishService.listAvailableDishes(student));
        io.println("逐项输入菜品；直接回车结束。重复菜品会自动合并数量。");
        List<OrderLineRequest> requests = new ArrayList<>();
        while (true) {
            String dishId = io.readLine("菜品编号：").trim();
            if (dishId.isEmpty()) {
                break;
            }
            int quantity = io.readInt("购买数量：");
            requests.add(new OrderLineRequest(dishId, quantity));
        }
        Order order = orderService.createOrder(student, requests);
        io.println("下单成功，订单号：" + order.getOrderId()
                + "，总金额：" + Rules.moneyText(order.getTotalAmount()));
        printOrderItems(orderService.getOrderItems(student, order.getOrderId()));
    }

    private void cancelOrder(User student) {
        String orderId = io.readLine("订单号：");
        Order order = orderService.cancelOrder(student, orderId);
        io.println("订单已取消，库存已恢复。当前状态：" + order.getOrderStatus());
    }

    private void addDish(User merchant) {
        String name = io.readLine("菜品名称：");
        BigDecimal price = Rules.requireInputPrice(io.readLine("单价："));
        int stock = io.readInt("库存：");
        Dish dish = dishService.addDish(merchant, name, price, stock);
        io.println("添加成功，菜品编号：" + dish.getDishId());
    }

    private void updateDish(User merchant) {
        String dishId = io.readLine("菜品编号：");
        String name = io.readLine("新名称：");
        BigDecimal price = Rules.requireInputPrice(io.readLine("新单价："));
        int stock = io.readInt("新库存：");
        Dish dish = dishService.updateDish(merchant, dishId, name, price, stock);
        io.println("修改成功，更新时间：" + DateTimes.format(dish.getUpdatedAt()));
    }

    private void deleteDish(User merchant) {
        String dishId = io.readLine("菜品编号：");
        Dish dish = dishService.deleteDish(merchant, dishId);
        io.println("菜品已逻辑删除，状态：" + dish.getSaleStatus());
    }

    private void setSaleStatus(User merchant) {
        String dishId = io.readLine("菜品编号：");
        int status = io.readChoice("1. 上架  2. 下架：", 1, 2);
        Dish dish = dishService.setSaleStatus(
                merchant,
                dishId,
                status == 1 ? SaleStatus.ON_SALE : SaleStatus.OFF_SALE);
        io.println("销售状态已更新为：" + dish.getSaleStatus());
    }

    private void updateOrderStatus(User merchant) {
        String orderId = io.readLine("订单号：");
        int status = io.readChoice("1. PREPARING  2. COMPLETED：", 1, 2);
        Order order = orderService.updateOrderStatus(
                merchant,
                orderId,
                status == 1 ? OrderStatus.PREPARING : OrderStatus.COMPLETED);
        io.println("订单状态已更新为：" + order.getOrderStatus());
    }

    private void showLowStock(User merchant) {
        int threshold = io.readInt("低库存阈值：");
        printDishes(dishService.listLowStock(merchant, threshold));
    }

    private void printDishes(List<Dish> dishes) {
        if (dishes.isEmpty()) {
            io.println("暂无菜品。");
            return;
        }
        io.println("菜品编号 | 商家编号 | 名称 | 单价 | 库存 | 状态");
        for (Dish dish : dishes) {
            io.println(String.join(" | ",
                    dish.getDishId(),
                    dish.getMerchantId(),
                    dish.getDishName(),
                    Rules.moneyText(dish.getUnitPrice()),
                    Integer.toString(dish.getStock()),
                    dish.getSaleStatus().name()));
        }
    }

    private void printOrders(User actor, List<Order> orders) {
        if (orders.isEmpty()) {
            io.println("暂无订单。");
            return;
        }
        for (Order order : orders) {
            io.println("");
            io.println(String.join(" | ",
                    order.getOrderId(),
                    "学生=" + order.getStudentId(),
                    "商家=" + order.getMerchantId(),
                    "状态=" + order.getOrderStatus(),
                    "金额=" + Rules.moneyText(order.getTotalAmount()),
                    "下单时间=" + DateTimes.format(order.getOrderTime())));
            printOrderItems(orderService.getOrderItems(actor, order.getOrderId()));
        }
    }

    private void printOrderItems(List<OrderItem> items) {
        for (OrderItem item : items) {
            io.println("  - " + item.getDishName()
                    + " × " + item.getQuantity()
                    + "，单价 " + Rules.moneyText(item.getUnitPrice())
                    + "，小计 " + Rules.moneyText(item.getSubtotal()));
        }
    }

    private static Path resolveDataDir(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--data-dir".equals(args[i]) && i + 1 < args.length) {
                return Path.of(args[i + 1]);
            }
        }
        String environmentValue = System.getenv("CANTEEN_DATA_DIR");
        if (environmentValue != null && !environmentValue.isBlank()) {
            return Path.of(environmentValue);
        }
        return Path.of("data");
    }

    private static final class ConsoleIo {
        private final Console console = System.console();
        private final BufferedReader reader =
                console == null ? new BufferedReader(new InputStreamReader(System.in)) : null;

        String readLine(String prompt) {
            try {
                if (console != null) {
                    String value = console.readLine("%s", prompt);
                    return value == null ? "" : value;
                }
                System.out.print(prompt);
                String value = reader.readLine();
                return value == null ? "" : value;
            } catch (IOException e) {
                throw new BusinessException("读取控制台输入失败", e);
            }
        }

        char[] readSecret(String prompt) {
            if (console != null) {
                char[] value = console.readPassword("%s", prompt);
                return value == null ? new char[0] : value;
            }
            return readLine(prompt).toCharArray();
        }

        int readInt(String prompt) {
            while (true) {
                try {
                    return Integer.parseInt(readLine(prompt).trim());
                } catch (NumberFormatException e) {
                    println("请输入十进制整数。");
                }
            }
        }

        int readChoice(String prompt, int min, int max) {
            while (true) {
                int value = readInt(prompt);
                if (value >= min && value <= max) {
                    return value;
                }
                println("请输入" + min + "～" + max + "之间的选项。");
            }
        }

        void println(String text) {
            System.out.println(text);
        }
    }
}
