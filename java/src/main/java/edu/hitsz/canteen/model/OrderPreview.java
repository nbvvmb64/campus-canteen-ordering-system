package edu.hitsz.canteen.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public final class OrderPreview {
    private final String merchantId;
    private final List<OrderPreviewLine> lines;
    private final BigDecimal totalAmount;

    public OrderPreview(
            String merchantId,
            List<OrderPreviewLine> lines,
            BigDecimal totalAmount) {
        this.merchantId = Objects.requireNonNull(merchantId);
        this.lines = List.copyOf(lines);
        this.totalAmount = Objects.requireNonNull(totalAmount);
    }

    public String getMerchantId() {
        return merchantId;
    }

    public List<OrderPreviewLine> getLines() {
        return lines;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }
}
