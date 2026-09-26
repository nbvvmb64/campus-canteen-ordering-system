package edu.hitsz.canteen.model;

import java.math.BigDecimal;
import java.util.Objects;

public final class OrderPreviewLine {
    private final String dishId;
    private final String dishName;
    private final BigDecimal unitPrice;
    private final int quantity;
    private final BigDecimal subtotal;

    public OrderPreviewLine(
            String dishId,
            String dishName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal subtotal) {
        this.dishId = Objects.requireNonNull(dishId);
        this.dishName = Objects.requireNonNull(dishName);
        this.unitPrice = Objects.requireNonNull(unitPrice);
        this.quantity = quantity;
        this.subtotal = Objects.requireNonNull(subtotal);
    }

    public String getDishId() {
        return dishId;
    }

    public String getDishName() {
        return dishName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }
}
