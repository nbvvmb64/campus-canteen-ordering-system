package edu.hitsz.canteen.service;

import edu.hitsz.canteen.exception.BusinessException;
import edu.hitsz.canteen.model.Dish;
import edu.hitsz.canteen.model.Order;
import edu.hitsz.canteen.model.OrderItem;
import edu.hitsz.canteen.model.OrderLineRequest;
import edu.hitsz.canteen.model.OrderPreview;
import edu.hitsz.canteen.model.OrderPreviewLine;
import edu.hitsz.canteen.model.OrderStatus;
import edu.hitsz.canteen.model.Role;
import edu.hitsz.canteen.model.SaleStatus;
import edu.hitsz.canteen.model.User;
import edu.hitsz.canteen.persistence.CsvDatabase;
import edu.hitsz.canteen.util.DateTimes;
import edu.hitsz.canteen.util.Rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OrderService {
    private final CsvDatabase database;

    public OrderService(CsvDatabase database) {
        this.database = database;
    }

    public Order createOrder(User student, List<OrderLineRequest> requests) {
        User actor = AccessControl.requireRole(database, student, Role.STUDENT);
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException("订单至少需要一个菜品");
        }

        return database.transaction(() -> {
            AccessControl.requireRole(database, actor, Role.STUDENT);
            PreparedOrder prepared = prepareOrder(requests);

            String orderId = database.nextOrderId();
            var now = DateTimes.now();
            for (Map.Entry<String, Integer> entry : prepared.quantities.entrySet()) {
                Dish dish = prepared.selectedDishes.get(entry.getKey());
                int quantity = entry.getValue();
                BigDecimal subtotal = Rules.money(
                        dish.getUnitPrice().multiply(BigDecimal.valueOf(quantity)));
                OrderItem item = new OrderItem(
                        database.nextOrderItemId(),
                        orderId,
                        dish.getDishId(),
                        dish.getDishName(),
                        quantity,
                        dish.getUnitPrice(),
                        subtotal);
                database.addOrderItem(item);
                dish.setStock(dish.getStock() - quantity, now);
            }

            Order order = new Order(
                    orderId,
                    actor.getUserId(),
                    prepared.merchantId,
                    now,
                    OrderStatus.PLACED,
                    prepared.totalAmount,
                    now);
            database.addOrder(order);
            return order;
        });
    }

    public OrderPreview previewOrder(User student, List<OrderLineRequest> requests) {
        User actor = AccessControl.requireRole(database, student, Role.STUDENT);
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException("购物车中暂无菜品");
        }
        synchronized (database) {
            AccessControl.requireRole(database, actor, Role.STUDENT);
            PreparedOrder prepared = prepareOrder(requests);
            return new OrderPreview(
                    prepared.merchantId,
                    prepared.previewLines,
                    prepared.totalAmount);
        }
    }

    public Order cancelOrder(User student, String orderId) {
        User actor = AccessControl.requireRole(database, student, Role.STUDENT);
        return database.transaction(() -> {
            Order order = requireOrder(orderId);
            if (!order.getStudentId().equals(actor.getUserId())) {
                throw new BusinessException("不能取消其他学生的订单");
            }
            if (order.getOrderStatus() == OrderStatus.CANCELLED) {
                throw new BusinessException("订单已经取消，不能重复取消");
            }
            if (order.getOrderStatus() != OrderStatus.PLACED) {
                throw new BusinessException("只有PLACED状态的订单可以取消");
            }
            List<OrderItem> items = database.orderItemsFor(orderId);
            var now = DateTimes.now();
            for (OrderItem item : items) {
                Dish dish = database.findDish(item.getDishId())
                        .orElseThrow(() -> new BusinessException(
                                "订单引用的菜品不存在: " + item.getDishId()));
                try {
                    int restoredStock = Math.addExact(dish.getStock(), item.getQuantity());
                    dish.setStock(restoredStock, now);
                } catch (ArithmeticException e) {
                    throw new BusinessException("恢复库存时发生整数溢出", e);
                }
            }
            order.setStatus(OrderStatus.CANCELLED, now);
            return order;
        });
    }

    public Order updateOrderStatus(
            User merchant, String orderId, OrderStatus targetStatus) {
        User actor = AccessControl.requireRole(database, merchant, Role.MERCHANT);
        if (targetStatus == null) {
            throw new BusinessException("目标订单状态不能为空");
        }
        return database.transaction(() -> {
            Order order = requireOrder(orderId);
            if (!order.getMerchantId().equals(actor.getUserId())) {
                throw new BusinessException("不能处理其他商家的订单");
            }
            boolean allowed = order.getOrderStatus() == OrderStatus.PLACED
                    && targetStatus == OrderStatus.PREPARING
                    || order.getOrderStatus() == OrderStatus.PREPARING
                    && targetStatus == OrderStatus.COMPLETED;
            if (!allowed) {
                throw new BusinessException(
                        "非法订单状态流转: "
                                + order.getOrderStatus() + " -> " + targetStatus);
            }
            order.setStatus(targetStatus, DateTimes.now());
            return order;
        });
    }

    public List<Order> listStudentOrders(User student) {
        User actor = AccessControl.requireRole(database, student, Role.STUDENT);
        return database.orders().stream()
                .filter(order -> order.getStudentId().equals(actor.getUserId()))
                .sorted(Comparator.comparing(Order::getOrderId))
                .toList();
    }

    public List<Order> listMerchantOrders(User merchant) {
        User actor = AccessControl.requireRole(database, merchant, Role.MERCHANT);
        return database.orders().stream()
                .filter(order -> order.getMerchantId().equals(actor.getUserId()))
                .sorted(Comparator.comparing(Order::getOrderId))
                .toList();
    }

    public List<OrderItem> getOrderItems(User actor, String orderId) {
        Order order = requireOrder(orderId);
        if (actor == null) {
            throw new BusinessException("请先登录");
        }
        if (actor.getRole() == Role.STUDENT) {
            User student = AccessControl.requireRole(database, actor, Role.STUDENT);
            if (!order.getStudentId().equals(student.getUserId())) {
                throw new BusinessException("不能查看其他学生的订单明细");
            }
        } else {
            User merchant = AccessControl.requireRole(database, actor, Role.MERCHANT);
            if (!order.getMerchantId().equals(merchant.getUserId())) {
                throw new BusinessException("不能查看其他商家的订单明细");
            }
        }
        return database.orderItemsFor(orderId);
    }

    private LinkedHashMap<String, Integer> mergeRequests(List<OrderLineRequest> requests) {
        LinkedHashMap<String, Integer> merged = new LinkedHashMap<>();
        for (OrderLineRequest request : requests) {
            if (request == null || request.getDishId().isBlank()) {
                throw new BusinessException("菜品编号不能为空");
            }
            if (request.getQuantity() <= 0) {
                throw new BusinessException("购买数量必须大于0");
            }
            try {
                merged.merge(
                        request.getDishId().trim(),
                        request.getQuantity(),
                        Math::addExact);
            } catch (ArithmeticException e) {
                throw new BusinessException("购买数量过大", e);
            }
        }
        return merged;
    }

    private PreparedOrder prepareOrder(List<OrderLineRequest> requests) {
        LinkedHashMap<String, Integer> merged = mergeRequests(requests);
        LinkedHashMap<String, Dish> selectedDishes = new LinkedHashMap<>();
        List<OrderPreviewLine> previewLines = new ArrayList<>();
        String merchantId = null;
        BigDecimal total = BigDecimal.ZERO.setScale(2);

        for (Map.Entry<String, Integer> entry : merged.entrySet()) {
            Dish dish = database.findDish(entry.getKey())
                    .orElseThrow(() -> new BusinessException(
                            "菜品不存在: " + entry.getKey()));
            if (dish.getSaleStatus() != SaleStatus.ON_SALE) {
                throw new BusinessException("菜品已下架: " + dish.getDishName());
            }
            if (merchantId == null) {
                merchantId = dish.getMerchantId();
            } else if (!merchantId.equals(dish.getMerchantId())) {
                throw new BusinessException("一张订单只能包含同一商家的菜品");
            }
            if (dish.getStock() < entry.getValue()) {
                throw new BusinessException(
                        "菜品库存不足: " + dish.getDishName()
                                + "，当前库存" + dish.getStock());
            }

            int quantity = entry.getValue();
            BigDecimal subtotal = Rules.money(
                    dish.getUnitPrice().multiply(BigDecimal.valueOf(quantity)));
            selectedDishes.put(dish.getDishId(), dish);
            previewLines.add(new OrderPreviewLine(
                    dish.getDishId(),
                    dish.getDishName(),
                    dish.getUnitPrice(),
                    quantity,
                    subtotal));
            total = total.add(subtotal);
        }
        return new PreparedOrder(
                merged,
                selectedDishes,
                merchantId,
                previewLines,
                Rules.money(total));
    }

    private Order requireOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new BusinessException("订单号不能为空");
        }
        return database.findOrder(orderId.trim())
                .orElseThrow(() -> new BusinessException("订单不存在"));
    }

    private static final class PreparedOrder {
        private final LinkedHashMap<String, Integer> quantities;
        private final LinkedHashMap<String, Dish> selectedDishes;
        private final String merchantId;
        private final List<OrderPreviewLine> previewLines;
        private final BigDecimal totalAmount;

        private PreparedOrder(
                LinkedHashMap<String, Integer> quantities,
                LinkedHashMap<String, Dish> selectedDishes,
                String merchantId,
                List<OrderPreviewLine> previewLines,
                BigDecimal totalAmount) {
            this.quantities = quantities;
            this.selectedDishes = selectedDishes;
            this.merchantId = merchantId;
            this.previewLines = previewLines;
            this.totalAmount = totalAmount;
        }
    }
}
