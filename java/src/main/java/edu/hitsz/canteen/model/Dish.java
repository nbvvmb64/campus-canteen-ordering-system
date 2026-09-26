package edu.hitsz.canteen.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

public final class Dish {
    private final String dishId;
    private final String merchantId;
    private String dishName;
    private BigDecimal unitPrice;
    private int stock;
    private SaleStatus saleStatus;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Dish(
            String dishId,
            String merchantId,
            String dishName,
            BigDecimal unitPrice,
            int stock,
            SaleStatus saleStatus,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this.dishId = Objects.requireNonNull(dishId);
        this.merchantId = Objects.requireNonNull(merchantId);
        this.dishName = Objects.requireNonNull(dishName);
        this.unitPrice = Objects.requireNonNull(unitPrice);
        this.stock = stock;
        this.saleStatus = Objects.requireNonNull(saleStatus);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public Dish(Dish other) {
        this(
                other.dishId,
                other.merchantId,
                other.dishName,
                other.unitPrice,
                other.stock,
                other.saleStatus,
                other.createdAt,
                other.updatedAt);
    }

    public String getDishId() {
        return dishId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getDishName() {
        return dishName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getStock() {
        return stock;
    }

    public SaleStatus getSaleStatus() {
        return saleStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void update(String dishName, BigDecimal unitPrice, int stock, LocalDateTime updatedAt) {
        this.dishName = Objects.requireNonNull(dishName);
        this.unitPrice = Objects.requireNonNull(unitPrice);
        this.stock = stock;
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public void setStock(int stock, LocalDateTime updatedAt) {
        this.stock = stock;
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public void setSaleStatus(SaleStatus saleStatus, LocalDateTime updatedAt) {
        this.saleStatus = Objects.requireNonNull(saleStatus);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }
}
