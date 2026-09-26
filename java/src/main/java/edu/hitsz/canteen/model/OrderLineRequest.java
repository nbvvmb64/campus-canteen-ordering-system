package edu.hitsz.canteen.model;

import java.util.Objects;

public final class OrderLineRequest {
    private final String dishId;
    private final int quantity;

    public OrderLineRequest(String dishId, int quantity) {
        this.dishId = Objects.requireNonNull(dishId);
        this.quantity = quantity;
    }

    public String getDishId() {
        return dishId;
    }

    public int getQuantity() {
        return quantity;
    }
}
