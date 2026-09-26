package edu.hitsz.canteen.persistence;

import edu.hitsz.canteen.exception.DataValidationException;

import java.util.ArrayList;
import java.util.List;

public final class CsvCodec {
    private CsvCodec() {
    }

    public static List<List<String>> parse(String content, String sourceName) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean justClosedQuote = false;
        boolean recordHasToken = false;

        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (inQuotes) {
                if (current == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                        justClosedQuote = true;
                    }
                } else {
                    field.append(current);
                }
                continue;
            }

            if (justClosedQuote) {
                if (current == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                    justClosedQuote = false;
                    recordHasToken = true;
                    continue;
                }
                if (current == '\r' || current == '\n') {
                    row.add(field.toString());
                    rows.add(row);
                    row = new ArrayList<>();
                    field.setLength(0);
                    justClosedQuote = false;
                    recordHasToken = false;
                    if (current == '\r'
                            && i + 1 < content.length()
                            && content.charAt(i + 1) == '\n') {
                        i++;
                    }
                    continue;
                }
                throw new DataValidationException(
                        sourceName + " 中双引号字段结束后出现非法字符");
            }

            if (current == '"') {
                if (field.length() != 0) {
                    throw new DataValidationException(
                            sourceName + " 中未加引号字段包含非法双引号");
                }
                inQuotes = true;
                recordHasToken = true;
            } else if (current == ',') {
                row.add(field.toString());
                field.setLength(0);
                recordHasToken = true;
            } else if (current == '\r' || current == '\n') {
                row.add(field.toString());
                rows.add(row);
                row = new ArrayList<>();
                field.setLength(0);
                recordHasToken = false;
                if (current == '\r'
                        && i + 1 < content.length()
                        && content.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                field.append(current);
                recordHasToken = true;
            }
        }

        if (inQuotes) {
            throw new DataValidationException(sourceName + " 存在未闭合的双引号字段");
        }
        if (justClosedQuote || recordHasToken || !row.isEmpty() || field.length() > 0) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    public static String write(List<List<String>> rows) {
        StringBuilder output = new StringBuilder();
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    output.append(',');
                }
                output.append(escape(row.get(i)));
            }
            output.append("\r\n");
        }
        return output.toString();
    }

    private static String escape(String value) {
        boolean needsQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
