package edu.hitsz.canteen.web.data;

import edu.hitsz.canteen.model.*;
import edu.hitsz.canteen.util.DateTimes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class DbStore {
    private final JdbcTemplate db;

    public DbStore(JdbcTemplate db) { this.db = db; }
    public JdbcTemplate jdbc() { return db; }

    private static LocalDateTime time(ResultSet rs, String name) throws SQLException {
        return rs.getTimestamp(name).toLocalDateTime();
    }
    private static Timestamp sqlTime(LocalDateTime time) { return Timestamp.valueOf(time); }
    private static final RowMapper<User> USER = (rs, n) -> new User(rs.getString("user_id"),
            rs.getString("username"), rs.getString("password_salt"), rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")), UserStatus.valueOf(rs.getString("user_status")),
            time(rs,"created_at"));
    private static final RowMapper<Dish> DISH = (rs, n) -> new Dish(rs.getString("dish_id"),
            rs.getString("merchant_id"), rs.getString("dish_name"), rs.getBigDecimal("unit_price"),
            rs.getInt("stock"), SaleStatus.valueOf(rs.getString("sale_status")),
            time(rs,"created_at"), time(rs,"updated_at"));
    private static final RowMapper<Order> ORDER = (rs, n) -> new Order(rs.getString("order_id"),
            rs.getString("student_id"), rs.getString("merchant_id"), time(rs,"order_time"),
            OrderStatus.valueOf(rs.getString("order_status")), rs.getBigDecimal("total_amount"),
            time(rs,"status_updated_at"));
    private static final RowMapper<OrderItem> ITEM = (rs, n) -> new OrderItem(rs.getString("order_item_id"),
            rs.getString("order_id"), rs.getString("dish_id"), rs.getString("dish_name"),
            rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("subtotal"));

    /** Must be the first business SQL in every application read/write transaction. */
    public long gate() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("业务版本行锁必须位于数据库事务内");
        try {
            if (DataSourceUtils.getConnection(db.getDataSource()).getAutoCommit())
                throw new IllegalStateException("业务版本行锁需要 autoCommit=false");
        } catch (SQLException e) { throw new IllegalStateException("无法核实数据库事务状态",e); }
        return db.queryForObject("SELECT revision FROM business_revision WHERE id=1 FOR UPDATE", Long.class);
    }
    public long currentRevision() {
        return db.queryForObject("SELECT revision FROM business_revision WHERE id=1", Long.class);
    }
    public void changed() { db.update("UPDATE business_revision SET revision=revision+1 WHERE id=1"); }
    public void exported(long revision) {
        db.update("UPDATE business_revision SET last_export_revision=?, export_error=NULL WHERE id=1", revision);
    }
    public void exportFailed(String message) {
        db.update("UPDATE business_revision SET export_error=? WHERE id=1", message.substring(0, Math.min(1000,message.length())));
    }
    public Optional<User> user(String id) { return one("SELECT * FROM users WHERE user_id=?", USER, id); }
    public Optional<User> username(String username) { return one("SELECT * FROM users WHERE username=?", USER, username); }
    public Optional<Dish> dish(String id) { return one("SELECT * FROM dishes WHERE dish_id=?", DISH, id); }
    public Optional<Order> order(String id) { return one("SELECT * FROM orders WHERE order_id=?", ORDER, id); }
    public record OrderRequest(String requestHash, String orderId) { }
    public Optional<OrderRequest> orderRequest(String studentId, String requestKey) {
        List<OrderRequest> rows = db.query("SELECT request_hash,order_id FROM order_idempotency "
                + "WHERE student_id=? AND request_key=?",
                (rs,n) -> new OrderRequest(rs.getString("request_hash"),rs.getString("order_id")),
                studentId,requestKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
    public void addOrderRequest(String studentId, String requestKey, String requestHash,
                                String orderId, LocalDateTime createdAt) {
        db.update("INSERT INTO order_idempotency(student_id,request_key,request_hash,order_id,created_at) "
                        + "VALUES (?,?,?,?,?)", studentId,requestKey,requestHash,orderId,sqlTime(createdAt));
    }
    public List<User> users() { return db.query("SELECT * FROM users ORDER BY user_id", USER); }
    public List<Dish> dishes() { return db.query("SELECT * FROM dishes ORDER BY dish_id", DISH); }
    public List<Order> orders() { return db.query("SELECT * FROM orders ORDER BY order_id", ORDER); }
    public List<OrderItem> items() { return db.query("SELECT * FROM order_items ORDER BY order_item_id", ITEM); }
    public List<Dish> merchantDishes(String merchantId) {
        return db.query("SELECT * FROM dishes WHERE merchant_id=? ORDER BY dish_id", DISH, merchantId);
    }
    public List<Dish> availableDishes() {
        return db.query("SELECT * FROM dishes WHERE sale_status='ON_SALE' ORDER BY merchant_id,dish_id", DISH);
    }
    public List<Order> studentOrders(String studentId) {
        return db.query("SELECT * FROM orders WHERE student_id=? ORDER BY order_id", ORDER, studentId);
    }
    public List<Order> merchantOrders(String merchantId) {
        return db.query("SELECT * FROM orders WHERE merchant_id=? ORDER BY order_id", ORDER, merchantId);
    }
    public List<OrderItem> orderItems(String orderId) {
        return db.query("SELECT * FROM order_items WHERE order_id=? ORDER BY order_item_id", ITEM, orderId);
    }
    public List<OrderItem> merchantItems(String merchantId) {
        return db.query("SELECT i.* FROM order_items i JOIN orders o ON o.order_id=i.order_id "
                + "WHERE o.merchant_id=? ORDER BY i.order_item_id", ITEM, merchantId);
    }
    public String nextId(String prefix) {
        Integer number = db.queryForObject("SELECT next_number FROM id_counters WHERE prefix=? FOR UPDATE",
                Integer.class, prefix);
        if (number == null || number > 999999) throw new IllegalStateException(prefix + "编号已耗尽");
        db.update("UPDATE id_counters SET next_number=? WHERE prefix=?", number + 1, prefix);
        return prefix + String.format("%06d", number);
    }
    public void addUser(User u) {
        db.update("INSERT INTO users VALUES (?,?,?,?,?,?,?)", u.getUserId(),u.getUsername(),u.getPasswordSalt(),
                u.getPasswordHash(),u.getRole().name(),u.getUserStatus().name(),sqlTime(u.getCreatedAt()));
    }
    public void addDish(Dish d) {
        db.update("INSERT INTO dishes VALUES (?,?,?,?,?,?,?,?)", d.getDishId(),d.getMerchantId(),d.getDishName(),
                d.getUnitPrice(),d.getStock(),d.getSaleStatus().name(),sqlTime(d.getCreatedAt()),sqlTime(d.getUpdatedAt()));
    }
    public void saveDish(Dish d) {
        db.update("UPDATE dishes SET dish_name=?,unit_price=?,stock=?,sale_status=?,updated_at=? WHERE dish_id=?",
                d.getDishName(),d.getUnitPrice(),d.getStock(),d.getSaleStatus().name(),sqlTime(d.getUpdatedAt()),d.getDishId());
    }
    public void addOrder(Order o) {
        db.update("INSERT INTO orders VALUES (?,?,?,?,?,?,?)", o.getOrderId(),o.getStudentId(),o.getMerchantId(),
                sqlTime(o.getOrderTime()),o.getOrderStatus().name(),o.getTotalAmount(),sqlTime(o.getStatusUpdatedAt()));
    }
    public void saveOrder(Order o) {
        db.update("UPDATE orders SET order_status=?,status_updated_at=? WHERE order_id=?",
                o.getOrderStatus().name(),sqlTime(o.getStatusUpdatedAt()),o.getOrderId());
    }
    public void addItem(OrderItem i) {
        db.update("INSERT INTO order_items VALUES (?,?,?,?,?,?,?)", i.getOrderItemId(),i.getOrderId(),i.getDishId(),
                i.getDishName(),i.getQuantity(),i.getUnitPrice(),i.getSubtotal());
    }
    private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object arg) {
        List<T> rows = db.query(sql, mapper, arg);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
