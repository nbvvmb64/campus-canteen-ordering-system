package edu.hitsz.canteen.util;

import edu.hitsz.canteen.exception.BusinessException;
import edu.hitsz.canteen.exception.DataValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

public final class Rules {
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\r\\n\\u0000]");

    private Rules() {
    }

    public static String requireTrimmedText(
            String value, String fieldName, int minLength, int maxLength) {
        if (value == null) {
            throw new BusinessException(fieldName + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() < minLength || normalized.length() > maxLength) {
            throw new BusinessException(
                    fieldName + "长度必须为" + minLength + "～" + maxLength + "个字符");
        }
        if (CONTROL_CHARACTER.matcher(normalized).find()) {
            throw new BusinessException(fieldName + "不能包含换行或控制字符");
        }
        return normalized;
    }

    public static BigDecimal requireInputPrice(String value) {
        try {
            BigDecimal parsed = new BigDecimal(value.trim());
            if (parsed.scale() > 2) {
                throw new BusinessException("价格最多保留两位小数");
            }
            if (parsed.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("价格必须大于0.00");
            }
            return money(parsed);
        } catch (NumberFormatException | NullPointerException e) {
            throw new BusinessException("价格格式错误", e);
        }
    }

    public static BigDecimal parseStoredMoney(String value, String fieldName, boolean positive) {
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (parsed.scale() != 2) {
                throw new DataValidationException(fieldName + "必须固定保留两位小数: " + value);
            }
            if (positive && parsed.compareTo(BigDecimal.ZERO) <= 0) {
                throw new DataValidationException(fieldName + "必须大于0.00: " + value);
            }
            if (!positive && parsed.compareTo(BigDecimal.ZERO) < 0) {
                throw new DataValidationException(fieldName + "不能小于0.00: " + value);
            }
            return money(parsed);
        } catch (NumberFormatException e) {
            throw new DataValidationException(fieldName + "金额格式错误: " + value, e);
        }
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static String moneyText(BigDecimal value) {
        return money(value).toPlainString();
    }

    public static int parseStoredInt(String value, String fieldName, boolean strictlyPositive) {
        try {
            int parsed = Integer.parseInt(value);
            if (strictlyPositive && parsed <= 0) {
                throw new DataValidationException(fieldName + "必须大于0: " + value);
            }
            if (!strictlyPositive && parsed < 0) {
                throw new DataValidationException(fieldName + "不能小于0: " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new DataValidationException(fieldName + "整数格式错误: " + value, e);
        }
    }
}
