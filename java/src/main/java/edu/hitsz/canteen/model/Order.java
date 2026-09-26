package edu.hitsz.canteen.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

public final class Order {
    private final String orderId;
    private final String studentId;
    private final String merchantId;
    private final LocalDateTime orderTime;
    private OrderStatus orderStatus;
    private final BigDecimal totalAmount;
    private LocalDateTime statusUpdatedAt;

    public Order(
            String orderId,
            String studentId,
            String merchantId,
            LocalDateTime orderTime,
            OrderStatus orderStatus,
            BigDecimal totalAmount,
            LocalDateTime statusUpdatedAt) {
        this.orderId = Objects.requireNonNull(orderId);
        this.studentId = Objects.requireNonNull(studentId);
        this.merchantId = Objects.requireNonNull(merchantId);
        this.orderTime = Objects.requireNonNull(orderTime);
        this.orderStatus = Objects.requireNonNull(orderStatus);
        this.totalAmount = Objects.requireNonNull(totalAmount);
        this.statusUpdatedAt = Objects.requireNonNull(statusUpdatedAt);
    }

    public Order(Order other) {
        this(
                other.orderId,
                other.studentId,
                other.merchantId,
                other.orderTime,
                other.orderStatus,
                other.totalAmount,
                other.statusUpdatedAt);
    }

    public String getOrderId() {
        return orderId;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public LocalDateTime getOrderTime() {
        return orderTime;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public LocalDateTime getStatusUpdatedAt() {
        return statusUpdatedAt;
    }

    public void setStatus(OrderStatus orderStatus, LocalDateTime statusUpdatedAt) {
        this.orderStatus = Objects.requireNonNull(orderStatus);
        this.statusUpdatedAt = Objects.requireNonNull(statusUpdatedAt);
    }
}
