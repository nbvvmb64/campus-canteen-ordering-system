package edu.hitsz.canteen.model;

import java.math.BigDecimal;
import java.util.Objects;

public final class OrderItem {
    private final String orderItemId;
    private final String orderId;
    private final String dishId;
    private final String dishName;
    private final int quantity;
    private final BigDecimal unitPrice;
    private final BigDecimal subtotal;

    public OrderItem(
            String orderItemId,
            String orderId,
            String dishId,
            String dishName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal) {
        this.orderItemId = Objects.requireNonNull(orderItemId);
        this.orderId = Objects.requireNonNull(orderId);
        this.dishId = Objects.requireNonNull(dishId);
        this.dishName = Objects.requireNonNull(dishName);
        this.quantity = quantity;
        this.unitPrice = Objects.requireNonNull(unitPrice);
        this.subtotal = Objects.requireNonNull(subtotal);
    }

    public String getOrderItemId() {
        return orderItemId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getDishId() {
        return dishId;
    }

    public String getDishName() {
        return dishName;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }
}
