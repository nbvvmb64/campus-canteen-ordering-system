package edu.hitsz.canteen.web;

import edu.hitsz.canteen.exception.BusinessException;
import edu.hitsz.canteen.model.*;
import edu.hitsz.canteen.security.PasswordHasher;
import edu.hitsz.canteen.util.DateTimes;
import edu.hitsz.canteen.util.Rules;
import edu.hitsz.canteen.web.data.DbStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class BusinessService {
    private static final Pattern UUID_V4 = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private final DbStore db;
    private final PasswordHasher hasher = new PasswordHasher();
    public BusinessService(DbStore db) { this.db = db; }

    @Transactional(rollbackFor = Exception.class)
    public User register(String username, char[] password, Role role) {
        db.gate();
        String name = Rules.requireTrimmedText(username,"账号",3,30);
        if (password == null || password.length < 8 || password.length > 128)
            throw new BusinessException("密码长度必须为8～128个字符");
        if (role == null) throw new BusinessException("用户角色不能为空");
        if (db.username(name).isPresent()) throw new BusinessException("账号已存在");
        var digest = hasher.hash(password);
        User user = new User(db.nextId("USR"),name,digest.getSalt(),digest.getHash(),role,
                UserStatus.ACTIVE,DateTimes.now());
        db.addUser(user);
        db.changed();
        return user;
    }

    public User authenticate(String username, char[] password) {
        if (username == null || password == null) throw new BusinessException("账号或密码错误");
        User user = db.username(username).orElseThrow(() -> new BusinessException("账号或密码错误"));
        if (user.getUserStatus() != UserStatus.ACTIVE
                || !hasher.verify(password,user.getPasswordSalt(),user.getPasswordHash()))
            throw new BusinessException("账号或密码错误");
        return user;
    }

    public User actor(String id, Role role) {
        if (id == null) throw new BusinessException("请先登录");
        User user = db.user(id).orElseThrow(() -> new BusinessException("登录用户不存在"));
        if (user.getUserStatus() != UserStatus.ACTIVE) throw new ForbiddenException("账号已被禁用");
        if (role != null && user.getRole() != role) throw new ForbiddenException("权限不足");
        return user;
    }

    @Transactional(rollbackFor = Exception.class)
    public Dish addDish(String merchantId, String name, BigDecimal price, int stock) {
        db.gate();
        User merchant = actor(merchantId,Role.MERCHANT);
        String dishName = Rules.requireTrimmedText(name,"菜品名称",1,50);
        BigDecimal unitPrice = price(price);
        stock(stock);
        uniqueActiveName(merchant.getUserId(), dishName, null);
        var now = DateTimes.now();
        Dish dish = new Dish(db.nextId("DSH"),merchantId,dishName,unitPrice,stock,SaleStatus.ON_SALE,now,now);
        db.addDish(dish); db.changed(); return dish;
    }

    @Transactional(rollbackFor = Exception.class)
    public Dish updateDish(String merchantId, String dishId, String name, BigDecimal price, int stock) {
        db.gate(); actor(merchantId,Role.MERCHANT);
        Dish dish = ownedDish(merchantId,dishId);
        String dishName = Rules.requireTrimmedText(name,"菜品名称",1,50);
        BigDecimal unitPrice = price(price); stock(stock);
        if (dish.getSaleStatus() == SaleStatus.ON_SALE) uniqueActiveName(merchantId,dishName,dishId);
        dish.update(dishName,unitPrice,stock,DateTimes.now());
        db.saveDish(dish); db.changed(); return dish;
    }

    @Transactional(rollbackFor = Exception.class)
    public Dish saleStatus(String merchantId, String dishId, SaleStatus target) {
        db.gate(); actor(merchantId,Role.MERCHANT);
        if (target == null) throw new BusinessException("销售状态不能为空");
        Dish dish = ownedDish(merchantId,dishId);
        if (target == SaleStatus.ON_SALE) uniqueActiveName(merchantId,dish.getDishName(),dishId);
        dish.setSaleStatus(target,DateTimes.now());
        db.saveDish(dish); db.changed(); return dish;
    }

    @Transactional(rollbackFor = Exception.class)
    public Dish deleteDish(String merchantId, String dishId) {
        db.gate(); actor(merchantId,Role.MERCHANT);
        Dish dish = ownedDish(merchantId,dishId);
        if (dish.getSaleStatus()==SaleStatus.OFF_SALE) throw new BusinessException("菜品已经下架，不能重复删除");
        dish.setSaleStatus(SaleStatus.OFF_SALE,DateTimes.now());
        db.saveDish(dish); db.changed(); return dish;
    }

    public List<Dish> merchantDishes(String merchantId) {
        actor(merchantId,Role.MERCHANT); return db.merchantDishes(merchantId);
    }
    public List<Dish> availableDishes(String studentId) {
        actor(studentId,Role.STUDENT); return db.availableDishes();
    }

    @Transactional(rollbackFor = Exception.class)
    public Preview preview(String studentId, List<OrderLineRequest> requests) {
        db.gate(); actor(studentId,Role.STUDENT);
        return prepare(requests);
    }

    @Transactional(rollbackFor = Exception.class)
    public Order createOrder(String studentId, String requestKey, List<OrderLineRequest> requests) {
        db.gate(); actor(studentId,Role.STUDENT);
        String key = requestKey(requestKey);
        LinkedHashMap<String,Integer> merged = mergeRequests(requests);
        String hash = requestHash(merged);
        var prior = db.orderRequest(studentId,key);
        if (prior.isPresent()) {
            if (!prior.get().requestHash().equals(hash))
                throw new IdempotencyConflictException("该下单请求标识已用于不同订单内容");
            Order saved = db.order(prior.get().orderId())
                    .orElseThrow(() -> new IllegalStateException("幂等记录对应的订单不存在"));
            return new Order(saved.getOrderId(),saved.getStudentId(),saved.getMerchantId(),
                    saved.getOrderTime(),OrderStatus.PLACED,saved.getTotalAmount(),saved.getOrderTime());
        }
        Preview preview = prepare(merged);
        String orderId = db.nextId("ORD");
        var now = DateTimes.now();
        Order order = new Order(orderId,studentId,preview.merchantId(),now,OrderStatus.PLACED,
                preview.totalAmount(),now);
        db.addOrder(order);
        for (Line line : preview.lines()) {
            Dish dish = db.dish(line.dishId()).orElseThrow();
            db.addItem(new OrderItem(db.nextId("ITM"),orderId,dish.getDishId(),dish.getDishName(),
                    line.quantity(),dish.getUnitPrice(),line.subtotal()));
            dish.setStock(dish.getStock()-line.quantity(),now);
            db.saveDish(dish);
        }
        db.addOrderRequest(studentId,key,hash,orderId,now);
        db.changed();
        return db.order(orderId).orElseThrow();
    }

    @Transactional(rollbackFor = Exception.class)
    public Order cancel(String studentId, String orderId) {
        db.gate(); actor(studentId,Role.STUDENT);
        Order order = requiredOrder(orderId);
        if (!studentId.equals(order.getStudentId())) throw new ForbiddenException("不能取消其他学生的订单");
        if (order.getOrderStatus()!=OrderStatus.PLACED) throw new BusinessException("只有PLACED订单可取消");
        var now = DateTimes.now();
        for (OrderItem item : db.orderItems(orderId)) {
            Dish dish = db.dish(item.getDishId()).orElseThrow(() -> new BusinessException("订单菜品不存在"));
            int restored;
            try { restored = Math.addExact(dish.getStock(),item.getQuantity()); }
            catch (ArithmeticException e) { throw new BusinessException("恢复库存溢出",e); }
            dish.setStock(restored,now); db.saveDish(dish);
        }
        order.setStatus(OrderStatus.CANCELLED,now);
        db.saveOrder(order); db.changed(); return order;
    }

    @Transactional(rollbackFor = Exception.class)
    public Order updateOrderStatus(String merchantId, String orderId, OrderStatus target) {
        db.gate(); actor(merchantId,Role.MERCHANT);
        Order order = requiredOrder(orderId);
        if (!merchantId.equals(order.getMerchantId())) throw new ForbiddenException("不能处理其他商家的订单");
        boolean allowed = order.getOrderStatus()==OrderStatus.PLACED && target==OrderStatus.PREPARING
                || order.getOrderStatus()==OrderStatus.PREPARING && target==OrderStatus.COMPLETED;
        if (!allowed) throw new BusinessException("非法订单状态流转");
        order.setStatus(target,DateTimes.now()); db.saveOrder(order); db.changed(); return order;
    }

    public List<Order> studentOrders(String id) { actor(id,Role.STUDENT); return db.studentOrders(id); }
    public List<Order> merchantOrders(String id) { actor(id,Role.MERCHANT); return db.merchantOrders(id); }
    public List<OrderItem> orderItems(String id, String orderId) {
        User user = actor(id,null);
        Order order = requiredOrder(orderId);
        if (user.getRole()==Role.STUDENT && !id.equals(order.getStudentId())
                || user.getRole()==Role.MERCHANT && !id.equals(order.getMerchantId()))
            throw new ForbiddenException("不能查看其他用户的订单明细");
        return db.orderItems(orderId);
    }

    private Preview prepare(List<OrderLineRequest> requests) {
        return prepare(mergeRequests(requests));
    }
    private static LinkedHashMap<String,Integer> mergeRequests(List<OrderLineRequest> requests) {
        if (requests == null || requests.isEmpty()) throw new BusinessException("订单至少需要一个菜品");
        LinkedHashMap<String,Integer> merged = new LinkedHashMap<>();
        for (OrderLineRequest request : requests) {
            if (request == null || request.getDishId()==null || request.getDishId().isBlank())
                throw new BusinessException("菜品编号不能为空");
            if (request.getQuantity()<=0) throw new BusinessException("购买数量必须大于0");
            try { merged.merge(request.getDishId().trim(),request.getQuantity(),Math::addExact); }
            catch (ArithmeticException e) { throw new BusinessException("购买数量过大",e); }
        }
        return merged;
    }
    private Preview prepare(Map<String,Integer> merged) {
        String merchant = null;
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String,Integer> entry : merged.entrySet()) {
            Dish dish = db.dish(entry.getKey()).orElseThrow(() -> new BusinessException("菜品不存在"));
            if (dish.getSaleStatus()!=SaleStatus.ON_SALE) throw new BusinessException("菜品已下架");
            if (merchant==null) merchant=dish.getMerchantId();
            else if (!merchant.equals(dish.getMerchantId())) throw new BusinessException("一张订单只能包含同一商家菜品");
            if (dish.getStock()<entry.getValue()) throw new BusinessException("库存不足: "+dish.getDishName());
            BigDecimal subtotal = Rules.money(dish.getUnitPrice().multiply(BigDecimal.valueOf(entry.getValue())));
            total=total.add(subtotal);
            lines.add(new Line(dish.getDishId(),dish.getDishName(),dish.getUnitPrice(),entry.getValue(),subtotal));
        }
        return new Preview(merchant,lines,Rules.money(total));
    }
    private static String requestKey(String supplied) {
        if (supplied == null || !UUID_V4.matcher(supplied).matches())
            throw new BusinessException("Idempotency-Key 必须是 UUID v4");
        return UUID.fromString(supplied).toString();
    }
    private static String requestHash(Map<String,Integer> merged) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("canteen-order-lines-v1".getBytes(StandardCharsets.US_ASCII));
            for (var line : new TreeMap<>(merged).entrySet()) {
                byte[] id = line.getKey().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(id.length).array());
                digest.update(id);
                digest.update(ByteBuffer.allocate(4).putInt(line.getValue()).array());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 不可用",e); }
    }
    private Dish ownedDish(String merchantId,String dishId) {
        Dish dish = db.dish(dishId).orElseThrow(() -> new BusinessException("菜品不存在"));
        if (!merchantId.equals(dish.getMerchantId())) throw new ForbiddenException("不能管理其他商家的菜品");
        return dish;
    }
    private Order requiredOrder(String orderId) {
        if (orderId==null || orderId.isBlank()) throw new BusinessException("订单号不能为空");
        return db.order(orderId.trim()).orElseThrow(() -> new BusinessException("订单不存在"));
    }
    private void uniqueActiveName(String merchantId,String name,String exclude) {
        boolean duplicate = db.merchantDishes(merchantId).stream()
                .anyMatch(d -> d.getSaleStatus()==SaleStatus.ON_SALE && d.getDishName().equals(name)
                        && !d.getDishId().equals(exclude));
        if (duplicate) throw new BusinessException("同一商家不能存在完全同名的在售菜品");
    }
    private static BigDecimal price(BigDecimal price) {
        if (price==null || price.scale()>2 || price.compareTo(BigDecimal.ZERO)<=0)
            throw new BusinessException("价格必须大于0.00且最多两位小数");
        return Rules.money(price);
    }
    private static void stock(int stock) {
        if (stock<0) throw new BusinessException("库存不能为负数");
    }
    public record Line(String dishId,String dishName,BigDecimal unitPrice,int quantity,BigDecimal subtotal) { }
    public record Preview(String merchantId,List<Line> lines,BigDecimal totalAmount) { }
}
