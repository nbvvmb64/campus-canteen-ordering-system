package edu.hitsz.canteen;

import edu.hitsz.canteen.exception.BusinessException;
import edu.hitsz.canteen.model.Dish;
import edu.hitsz.canteen.model.Order;
import edu.hitsz.canteen.model.OrderLineRequest;
import edu.hitsz.canteen.model.OrderStatus;
import edu.hitsz.canteen.model.Role;
import edu.hitsz.canteen.model.User;
import edu.hitsz.canteen.persistence.CsvDatabase;
import edu.hitsz.canteen.persistence.LegacyWriteGate;
import edu.hitsz.canteen.service.AuthService;
import edu.hitsz.canteen.service.DishService;
import edu.hitsz.canteen.service.OrderService;
import edu.hitsz.canteen.util.Rules;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class DemoScenario {
    private DemoScenario() {
    }

    public static void main(String[] args) {
        Path dataDir = args.length > 0 ? Path.of(args[0]) : Path.of("data");
        LegacyWriteGate.rejectIfCutOver(dataDir);
        CsvDatabase database = new CsvDatabase(dataDir);
        if (database.hasBusinessData()) {
            throw new BusinessException(
                    "演示要求空数据目录；当前正式CSV已有数据，为避免覆盖已停止");
        }

        AuthService auth = new AuthService(database);
        DishService dishes = new DishService(database);
        OrderService orders = new OrderService(database);
        char[] generatedPassword =
                ("Demo-" + UUID.randomUUID() + "-Aa1").toCharArray();

        try {
            User merchant = auth.register("canteen01", generatedPassword, Role.MERCHANT);
            User student = auth.register("student001", generatedPassword, Role.STUDENT);
            merchant = auth.login("canteen01", generatedPassword);
            student = auth.login("student001", generatedPassword);
            System.out.println("1) 学生和商家注册、登录成功；密码仅保存PBKDF2派生结果。");

            Dish chicken = dishes.addDish(
                    merchant, "招牌鸡腿饭", new BigDecimal("18.50"), 20);
            Dish noodles = dishes.addDish(
                    merchant, "牛肉面（大碗）", new BigDecimal("16.00"), 15);
            Dish tea = dishes.addDish(
                    merchant, "柠檬茶,少冰", new BigDecimal("8.00"), 12);
            System.out.println("2) 商家已添加3个菜品，其中一个名称含英文逗号以验证CSV转义。");

            Order cancelled = orders.createOrder(
                    student,
                    List.of(
                            new OrderLineRequest(chicken.getDishId(), 2),
                            new OrderLineRequest(tea.getDishId(), 1)));
            System.out.println("3) 多菜品订单 " + cancelled.getOrderId()
                    + " 总金额=" + Rules.moneyText(cancelled.getTotalAmount())
                    + "，下单后库存已扣减。");
            orders.cancelOrder(student, cancelled.getOrderId());
            System.out.println("4) 订单 " + cancelled.getOrderId()
                    + " 已取消，库存已恢复。");
            try {
                orders.cancelOrder(student, cancelled.getOrderId());
                throw new IllegalStateException("重复取消未被拦截");
            } catch (BusinessException expected) {
                System.out.println("5) 重复取消已被拒绝：" + expected.getMessage());
            }

            Order preparing = orders.createOrder(
                    student,
                    List.of(
                            new OrderLineRequest(noodles.getDishId(), 1),
                            new OrderLineRequest(tea.getDishId(), 2)));
            orders.updateOrderStatus(
                    merchant, preparing.getOrderId(), OrderStatus.PREPARING);
            System.out.println("6) 订单 " + preparing.getOrderId()
                    + " 已进入PREPARING，金额="
                    + Rules.moneyText(preparing.getTotalAmount()) + "。");

            Order completed = orders.createOrder(
                    student,
                    List.of(new OrderLineRequest(chicken.getDishId(), 1)));
            orders.updateOrderStatus(
                    merchant, completed.getOrderId(), OrderStatus.PREPARING);
            orders.updateOrderStatus(
                    merchant, completed.getOrderId(), OrderStatus.COMPLETED);
            System.out.println("7) 订单 " + completed.getOrderId()
                    + " 已完成，金额="
                    + Rules.moneyText(completed.getTotalAmount()) + "。");

            System.out.println("8) 最终状态包含CANCELLED、PREPARING、COMPLETED；");
            System.out.println("   最终库存：鸡腿饭="
                    + database.findDish(chicken.getDishId()).orElseThrow().getStock()
                    + "，牛肉面="
                    + database.findDish(noodles.getDishId()).orElseThrow().getStock()
                    + "，柠檬茶="
                    + database.findDish(tea.getDishId()).orElseThrow().getStock());
            System.out.println("演示完成，正式CSV目录：" + database.getDataDir());
        } finally {
            Arrays.fill(generatedPassword, '\0');
        }
    }
}
