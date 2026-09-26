package edu.hitsz.canteen.web;

import edu.hitsz.canteen.model.*;
import edu.hitsz.canteen.util.DateTimes;
import edu.hitsz.canteen.web.data.DbStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MerchantDashboard {
    private static final int LOW_STOCK = 15;
    private static final int RESTOCK_TARGET = 30;
    private final DbStore db;
    private final BusinessService business;
    public MerchantDashboard(DbStore db, BusinessService business) { this.db=db; this.business=business; }

    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> get(String merchantId) {
        db.gate(); // First business SQL; no writer can commit until this read is complete.
        business.actor(merchantId,Role.MERCHANT);
        List<Dish> dishes = db.merchantDishes(merchantId);
        List<Order> orders = db.merchantOrders(merchantId);
        List<OrderItem> items = db.merchantItems(merchantId);
        Map<String,Order> orderById = orders.stream().collect(Collectors.toMap(Order::getOrderId,o->o));
        List<Order> valid = orders.stream().filter(o -> o.getOrderStatus()!=OrderStatus.CANCELLED).toList();
        BigDecimal revenue = valid.stream().map(Order::getTotalAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
        long quantity = items.stream().filter(i -> orderById.get(i.getOrderId()).getOrderStatus()!=OrderStatus.CANCELLED)
                .mapToLong(OrderItem::getQuantity).sum();
        long cancelled = orders.size()-valid.size();
        Map<String,Object> summary = new LinkedHashMap<>();
        summary.put("total_order_count",orders.size());
        summary.put("valid_order_count",valid.size());
        summary.put("cancelled_order_count",cancelled);
        summary.put("cancellation_rate_percent",percent(cancelled,orders.size()));
        summary.put("valid_sales_quantity",quantity);
        summary.put("valid_sales_total",money(revenue));
        summary.put("average_valid_order_amount",money(average(revenue,valid.size())));
        summary.put("on_sale_dish_count",dishes.stream().filter(d->d.getSaleStatus()==SaleStatus.ON_SALE).count());
        summary.put("low_stock_dish_count",dishes.stream().filter(d->d.getStock()>0&&d.getStock()<LOW_STOCK).count());
        summary.put("out_of_stock_dish_count",dishes.stream().filter(d->d.getStock()==0).count());

        TreeMap<LocalDate,List<Order>> byDay = new TreeMap<>();
        for (Order o : orders) byDay.computeIfAbsent(o.getOrderTime().toLocalDate(),unused->new ArrayList<>()).add(o);
        List<Map<String,Object>> daily = new ArrayList<>();
        for (var day : byDay.entrySet()) {
            List<Order> dayOrders=day.getValue();
            List<Order> dayValid=dayOrders.stream().filter(o->o.getOrderStatus()!=OrderStatus.CANCELLED).toList();
            Set<String> ids=dayValid.stream().map(Order::getOrderId).collect(Collectors.toSet());
            BigDecimal dayRevenue=dayValid.stream().map(Order::getTotalAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("date",day.getKey().toString());
            row.put("total_order_count",dayOrders.size());
            row.put("valid_order_count",dayValid.size());
            row.put("cancelled_order_count",dayOrders.size()-dayValid.size());
            row.put("valid_sales_quantity",items.stream().filter(i->ids.contains(i.getOrderId())).mapToLong(OrderItem::getQuantity).sum());
            row.put("valid_sales_amount",money(dayRevenue));
            daily.add(row);
        }
        Map<String,List<OrderItem>> validItems = items.stream()
                .filter(i->orderById.get(i.getOrderId()).getOrderStatus()!=OrderStatus.CANCELLED)
                .collect(Collectors.groupingBy(OrderItem::getDishId));
        List<Map<String,Object>> dishRows=new ArrayList<>();
        List<Map<String,Object>> inventoryRows=new ArrayList<>();
        for (Dish dish : dishes) {
            List<OrderItem> sold=validItems.getOrDefault(dish.getDishId(),List.of());
            long soldQuantity=sold.stream().mapToLong(OrderItem::getQuantity).sum();
            BigDecimal soldRevenue=sold.stream().map(OrderItem::getSubtotal).reduce(BigDecimal.ZERO,BigDecimal::add);
            dishRows.add(Map.of("dish_id",dish.getDishId(),"dish_name",dish.getDishName(),
                    "sale_status",dish.getSaleStatus().name(),"stock",dish.getStock(),
                    "valid_sales_quantity",soldQuantity,"valid_sales_amount",money(soldRevenue)));
            String stockStatus=dish.getStock()==0?"OUT_OF_STOCK":dish.getStock()<LOW_STOCK?"LOW_STOCK":"NORMAL";
            inventoryRows.add(Map.of("dish_id",dish.getDishId(),"dish_name",dish.getDishName(),
                    "stock",dish.getStock(),"stock_status",stockStatus,"valid_sales_quantity",soldQuantity,
                    "recommended_restock_quantity",dish.getStock()<LOW_STOCK?Math.max(RESTOCK_TARGET-dish.getStock(),0):0));
        }
        inventoryRows.sort(Comparator.comparingInt(row->switch ((String)row.get("stock_status")) {
            case "OUT_OF_STOCK" -> 0; case "LOW_STOCK" -> 1; default -> 2;
        }));
        List<Map<String,Object>> statusRows=new ArrayList<>();
        for (OrderStatus status : OrderStatus.values()) statusRows.add(Map.of("order_status",status.name(),
                "order_count",orders.stream().filter(o->o.getOrderStatus()==status).count()));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("api_version","merchant-dashboard.v1");
        result.put("as_of",ZonedDateTime.now(DateTimes.ZONE).withNano(0).toOffsetDateTime().toString());
        result.put("scope",Map.of("role","MERCHANT","merchant_id",merchantId));
        result.put("definitions",Map.of("effective_order_statuses",List.of("PLACED","PREPARING","COMPLETED"),
                "cancelled_orders_excluded",true,"money_unit","CNY","low_stock_threshold",LOW_STOCK,
                "restock_target",RESTOCK_TARGET));
        result.put("summary",summary);
        result.put("sales",Map.of("daily",daily,"dishes",dishRows));
        result.put("orders",Map.of("status_distribution",statusRows));
        result.put("inventory",Map.of("items",inventoryRows));
        return result;
    }
    private static String money(BigDecimal value) { return value.setScale(2,RoundingMode.HALF_UP).toPlainString(); }
    private static BigDecimal average(BigDecimal amount,int count) {
        return count==0?BigDecimal.ZERO:amount.divide(BigDecimal.valueOf(count),2,RoundingMode.HALF_UP);
    }
    private static String percent(long numerator,long denominator) {
        return denominator==0?"0.00":BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator),2,RoundingMode.HALF_UP).toPlainString();
    }
}
