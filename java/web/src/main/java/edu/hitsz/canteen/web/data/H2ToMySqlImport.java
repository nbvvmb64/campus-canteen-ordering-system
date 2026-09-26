package edu.hitsz.canteen.web.data;

import edu.hitsz.canteen.web.ProjectPaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** Offline, one-way copy from an isolated H2 file to an empty project-local MySQL schema. */
@Service
public class H2ToMySqlImport {
    static final List<String> TABLES = List.of("users", "dishes", "orders", "order_items",
            "order_idempotency", "business_revision", "id_counters");
    private final ProjectPaths paths;
    private final DataSource target;
    private final String sourcePath;

    public H2ToMySqlImport(ProjectPaths paths, DataSource target,
                           @Value("${canteen.h2-source:${CANTEEN_H2_SOURCE:}}") String sourcePath) {
        this.paths = paths;
        this.target = target;
        this.sourcePath = sourcePath;
    }

    public void run() throws Exception {
        if (sourcePath.isBlank()) throw new IllegalStateException("必须指定隔离 H2 副本");
        Path source = Path.of(sourcePath).toAbsolutePath().normalize();
        Path root = paths.root().toRealPath();
        if (!source.startsWith(root) || source.equals(paths.database().toAbsolutePath().normalize())
                || !Files.isRegularFile(Path.of(source + ".mv.db")))
            throw new IllegalStateException("H2 来源必须是项目内、非正式库的现有隔离副本");
        if (Files.isRegularFile(paths.engineMarker())
                && "MYSQL".equalsIgnoreCase(Files.readString(paths.engineMarker()).trim()))
            throw new IllegalStateException("MySQL 已被标为正式数据源，禁止再次导入");

        String url = "jdbc:h2:file:" + source.toString().replace('\\', '/')
                + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r";
        try (Connection from = DriverManager.getConnection(url, "sa", "");
             Connection to = target.getConnection()) {
            from.setReadOnly(true);
            to.setAutoCommit(false);
            try {
                for (String table : TABLES) {
                    if (count(to, table) != 0) throw new IllegalStateException("目标表非空: " + table);
                }
                if (count(from, "business_revision") != 1)
                    throw new IllegalStateException("H2 业务修订行异常");
                transferAndCompare(from, to);
                to.commit();
                System.out.println("隔离 H2 七表导入和逐字段校验完成；未切换正式数据源");
            } catch (Exception error) {
                to.rollback();
                throw error;
            }
        }
    }

    static int count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    static void transferAndCompare(Connection from, Connection to) throws SQLException {
        for (String table : TABLES) copy(from, to, table);
        for (String table : TABLES) compare(from, to, table);
    }

    private static void copy(Connection from, Connection to, String table) throws SQLException {
        try (var select = from.createStatement(); var rows = select.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData meta = rows.getMetaData();
            int columns = meta.getColumnCount();
            StringBuilder sql = new StringBuilder("INSERT INTO " + table + " (");
            for (int i = 1; i <= columns; i++) {
                if (i > 1) sql.append(',');
                sql.append(meta.getColumnLabel(i));
            }
            sql.append(") VALUES (");
            for (int i = 1; i <= columns; i++) {
                if (i > 1) sql.append(',');
                sql.append('?');
            }
            sql.append(')');
            try (PreparedStatement insert = to.prepareStatement(sql.toString())) {
                while (rows.next()) {
                    for (int i = 1; i <= columns; i++) insert.setObject(i, rows.getObject(i));
                    insert.executeUpdate();
                }
            }
        }
    }

    private static void compare(Connection from, Connection to, String table) throws SQLException {
        List<List<Object>> source = rows(from, table);
        List<List<Object>> target = rows(to, table);
        if (source.size() != target.size()) throw new IllegalStateException("迁移行数不一致: " + table);
        for (int row = 0; row < source.size(); row++) {
            for (int column = 0; column < source.get(row).size(); column++) {
                if (!java.util.Objects.equals(source.get(row).get(column), target.get(row).get(column)))
                    throw new IllegalStateException("迁移字段不一致: " + table + " 行 " + row + " 列 " + column);
            }
        }
    }

    private static List<List<Object>> rows(Connection connection, String table) throws SQLException {
        String order = switch (table) {
            case "users" -> "user_id";
            case "dishes" -> "dish_id";
            case "orders" -> "order_id";
            case "order_items" -> "order_item_id";
            case "order_idempotency" -> "student_id,request_key";
            case "business_revision" -> "id";
            case "id_counters" -> "prefix";
            default -> throw new IllegalArgumentException(table);
        };
        List<List<Object>> result = new ArrayList<>();
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT * FROM " + table + " ORDER BY " + order)) {
            var meta = rs.getMetaData();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    Object value = switch (meta.getColumnType(i)) {
                        case Types.DECIMAL, Types.NUMERIC -> rs.getBigDecimal(i);
                        case Types.TIMESTAMP -> rs.getTimestamp(i) == null ? null : rs.getTimestamp(i).toLocalDateTime();
                        case Types.INTEGER, Types.BIGINT -> rs.getLong(i);
                        default -> rs.getString(i);
                    };
                    row.add(value);
                }
                result.add(row);
            }
        }
        return result;
    }
}
