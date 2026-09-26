package edu.hitsz.canteen.util;

import edu.hitsz.canteen.exception.DataValidationException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class DateTimes {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DateTimes() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE).withNano(0);
    }

    public static String format(LocalDateTime value) {
        return value.format(FORMATTER);
    }

    public static LocalDateTime parse(String value, String fieldName) {
        try {
            return LocalDateTime.parse(value, FORMATTER);
        } catch (DateTimeParseException e) {
            throw new DataValidationException(fieldName + " 日期时间格式错误: " + value, e);
        }
    }
}
