from __future__ import annotations

from collections import defaultdict
from decimal import Decimal, ROUND_HALF_UP

from .loader import ORDER_STATUSES
from .models import (
    AnalysisResult,
    DailySales,
    DailyOrderAnalysis,
    Dataset,
    DishPerformance,
    DishSales,
    DistributionBucket,
    HourlyOrderDistribution,
    Insight,
    InventoryAnalysis,
    LowStockAlert,
    MerchantOrderStatus,
    MerchantPerformance,
    Notification,
    Order,
    OrderStatusAnalysis,
)

_STATUS_MESSAGES = {
    "PLACED": "订单 {order_id} 已下单，金额 {amount} 元，商家将尽快处理。",
    "PREPARING": "订单 {order_id} 正在制作，请耐心等待。",
    "COMPLETED": "订单 {order_id} 已完成，感谢使用校园餐厅订餐系统。",
    "CANCELLED": "订单 {order_id} 已取消，相关库存已按业务规则恢复。",
}

_ZERO_MONEY = Decimal("0.00")
_TWO_PLACES = Decimal("0.01")


def analyze_dataset(
    dataset: Dataset,
    *,
    low_stock_threshold: int,
    restock_target: int,
) -> AnalysisResult:
    if low_stock_threshold < 0:
        raise ValueError("low_stock_threshold 不能为负数")
    if restock_target <= low_stock_threshold:
        raise ValueError("restock_target 必须大于 low_stock_threshold")

    valid_orders = tuple(
        order for order in dataset.orders if order.order_status != "CANCELLED"
    )
    valid_order_ids = {order.order_id for order in valid_orders}
    dishes_by_id = {dish.dish_id: dish for dish in dataset.dishes}
    orders_by_id = {order.order_id: order for order in dataset.orders}
    items_by_order: dict[str, list[object]] = defaultdict(list)
    for item in dataset.order_items:
        items_by_order[item.order_id].append(item)

    quantity_by_dish: dict[str, int] = defaultdict(int)
    revenue_by_dish: dict[str, Decimal] = defaultdict(
        lambda: Decimal("0.00")
    )
    for item in dataset.order_items:
        if item.order_id not in valid_order_ids:
            continue
        quantity_by_dish[item.dish_id] += item.quantity
        revenue_by_dish[item.dish_id] += item.subtotal

    dish_sales = tuple(
        DishSales(
            dish_id=dish_id,
            dish_name=dishes_by_id[dish_id].dish_name,
            quantity=quantity_by_dish[dish_id],
            sales_amount=revenue_by_dish[dish_id],
        )
        for dish_id in quantity_by_dish
    )
    by_quantity = tuple(
        sorted(
            dish_sales,
            key=lambda row: (-row.quantity, -row.sales_amount, row.dish_id),
        )
    )
    by_revenue = tuple(
        sorted(
            dish_sales,
            key=lambda row: (-row.sales_amount, -row.quantity, row.dish_id),
        )
    )

    valid_sales_quantity = sum(quantity_by_dish.values())
    valid_total = sum(
        (order.total_amount for order in valid_orders),
        start=_ZERO_MONEY,
    )
    average_valid_order_amount = _average_money(
        valid_total,
        len(valid_orders),
    )
    dish_performance = tuple(
        sorted(
            (
                DishPerformance(
                    dish_id=dish.dish_id,
                    merchant_id=dish.merchant_id,
                    dish_name=dish.dish_name,
                    sale_status=dish.sale_status,
                    stock=dish.stock,
                    quantity=quantity_by_dish[dish.dish_id],
                    sales_amount=revenue_by_dish[dish.dish_id],
                    quantity_share_percent=_percent(
                        quantity_by_dish[dish.dish_id],
                        valid_sales_quantity,
                    ),
                    revenue_share_percent=_percent(
                        revenue_by_dish[dish.dish_id],
                        valid_total,
                    ),
                )
                for dish in dataset.dishes
            ),
            key=lambda row: (
                -row.quantity,
                -row.sales_amount,
                row.dish_id,
            ),
        )
    )

    daily_counts: dict[object, int] = defaultdict(int)
    daily_revenue: dict[object, Decimal] = defaultdict(
        lambda: Decimal("0.00")
    )
    for order in valid_orders:
        sales_date = order.order_time.date()
        daily_counts[sales_date] += 1
        daily_revenue[sales_date] += order.total_amount
    daily_sales = tuple(
        DailySales(
            sales_date=sales_date,
            order_count=daily_counts[sales_date],
            sales_amount=daily_revenue[sales_date],
        )
        for sales_date in sorted(daily_counts)
    )

    all_daily_counts: dict[object, int] = defaultdict(int)
    cancelled_daily_counts: dict[object, int] = defaultdict(int)
    daily_quantities: dict[object, int] = defaultdict(int)
    for order in dataset.orders:
        sales_date = order.order_time.date()
        all_daily_counts[sales_date] += 1
        if order.order_status == "CANCELLED":
            cancelled_daily_counts[sales_date] += 1
        else:
            daily_quantities[sales_date] += sum(
                item.quantity for item in items_by_order[order.order_id]
            )
    daily_order_analysis = tuple(
        DailyOrderAnalysis(
            sales_date=sales_date,
            total_order_count=all_daily_counts[sales_date],
            valid_order_count=daily_counts[sales_date],
            cancelled_order_count=cancelled_daily_counts[sales_date],
            cancellation_rate_percent=_percent(
                cancelled_daily_counts[sales_date],
                all_daily_counts[sales_date],
            ),
            valid_sales_quantity=daily_quantities[sales_date],
            valid_sales_amount=daily_revenue[sales_date],
            average_valid_order_amount=_average_money(
                daily_revenue[sales_date],
                daily_counts[sales_date],
            ),
        )
        for sales_date in sorted(all_daily_counts)
    )

    status_counts = {status: 0 for status in ORDER_STATUSES}
    for order in dataset.orders:
        status_counts[order.order_status] += 1
    order_status_analysis = tuple(
        OrderStatusAnalysis(
            order_status=status,
            order_count=status_counts[status],
            share_percent=_percent(
                status_counts[status],
                len(dataset.orders),
            ),
        )
        for status in ORDER_STATUSES
    )

    merchant_ids = sorted(
        {dish.merchant_id for dish in dataset.dishes}
        | {order.merchant_id for order in dataset.orders}
    )
    merchant_performance_rows: list[MerchantPerformance] = []
    merchant_status_rows: list[MerchantOrderStatus] = []
    for merchant_id in merchant_ids:
        merchant_orders = tuple(
            order
            for order in dataset.orders
            if order.merchant_id == merchant_id
        )
        merchant_valid_orders = tuple(
            order
            for order in merchant_orders
            if order.order_status != "CANCELLED"
        )
        merchant_valid_ids = {
            order.order_id for order in merchant_valid_orders
        }
        merchant_dishes = tuple(
            dish
            for dish in dataset.dishes
            if dish.merchant_id == merchant_id
        )
        merchant_quantity = sum(
            item.quantity
            for item in dataset.order_items
            if item.order_id in merchant_valid_ids
        )
        merchant_sales = sum(
            (order.total_amount for order in merchant_valid_orders),
            start=_ZERO_MONEY,
        )
        merchant_cancelled = sum(
            1
            for order in merchant_orders
            if order.order_status == "CANCELLED"
        )
        merchant_performance_rows.append(
            MerchantPerformance(
                merchant_id=merchant_id,
                total_order_count=len(merchant_orders),
                valid_order_count=len(merchant_valid_orders),
                cancelled_order_count=merchant_cancelled,
                cancellation_rate_percent=_percent(
                    merchant_cancelled,
                    len(merchant_orders),
                ),
                valid_sales_quantity=merchant_quantity,
                valid_sales_amount=merchant_sales,
                average_valid_order_amount=_average_money(
                    merchant_sales,
                    len(merchant_valid_orders),
                ),
                dish_count=len(merchant_dishes),
                on_sale_dish_count=sum(
                    dish.sale_status == "ON_SALE"
                    for dish in merchant_dishes
                ),
                low_stock_dish_count=sum(
                    0 < dish.stock < low_stock_threshold
                    for dish in merchant_dishes
                ),
                out_of_stock_dish_count=sum(
                    dish.stock == 0 for dish in merchant_dishes
                ),
            )
        )
        merchant_status_counts = {status: 0 for status in ORDER_STATUSES}
        for order in merchant_orders:
            merchant_status_counts[order.order_status] += 1
        merchant_status_rows.extend(
            MerchantOrderStatus(
                merchant_id=merchant_id,
                order_status=status,
                order_count=merchant_status_counts[status],
            )
            for status in ORDER_STATUSES
        )
    merchant_performance = tuple(
        sorted(
            merchant_performance_rows,
            key=lambda row: (
                -row.valid_sales_amount,
                -row.valid_order_count,
                row.merchant_id,
            ),
        )
    )

    hourly_total = {hour: 0 for hour in range(24)}
    hourly_valid = {hour: 0 for hour in range(24)}
    hourly_cancelled = {hour: 0 for hour in range(24)}
    for order in dataset.orders:
        hour = order.order_time.hour
        hourly_total[hour] += 1
        if order.order_status == "CANCELLED":
            hourly_cancelled[hour] += 1
        else:
            hourly_valid[hour] += 1
    hourly_distribution = tuple(
        HourlyOrderDistribution(
            hour=hour,
            total_order_count=hourly_total[hour],
            valid_order_count=hourly_valid[hour],
            cancelled_order_count=hourly_cancelled[hour],
        )
        for hour in range(24)
    )

    item_kind_counts: dict[int, int] = defaultdict(int)
    item_quantity_counts: dict[int, int] = defaultdict(int)
    for order in valid_orders:
        order_items = items_by_order[order.order_id]
        item_kind_counts[len(order_items)] += 1
        item_quantity_counts[sum(item.quantity for item in order_items)] += 1
    item_kind_distribution = _distribution(item_kind_counts, len(valid_orders))
    item_quantity_distribution = _distribution(
        item_quantity_counts,
        len(valid_orders),
    )
    single_item_order_count = item_kind_counts[1]
    multi_item_order_count = max(
        len(valid_orders) - single_item_order_count,
        0,
    )

    low_stock_alerts = tuple(
        LowStockAlert(
            dish_id=dish.dish_id,
            merchant_id=dish.merchant_id,
            dish_name=dish.dish_name,
            stock=dish.stock,
            threshold=low_stock_threshold,
            recommended_restock_quantity=max(restock_target - dish.stock, 0),
            sale_status=dish.sale_status,
        )
        for dish in sorted(dataset.dishes, key=lambda row: (row.stock, row.dish_id))
        if 0 < dish.stock < low_stock_threshold
    )

    inventory_rows: list[InventoryAnalysis] = []
    for dish in dataset.dishes:
        quantity = quantity_by_dish[dish.dish_id]
        if dish.stock == 0:
            stock_status = "OUT_OF_STOCK"
            risk_level = "OUT_OF_STOCK"
            risk_reason = "当前库存为0，列为最高补货风险。"
        elif dish.stock < low_stock_threshold and quantity > 0:
            stock_status = "LOW_STOCK"
            risk_level = "LOW_STOCK_WITH_SALES"
            risk_reason = "库存低于阈值且已有有效销量，建议优先关注。"
        elif dish.stock < low_stock_threshold:
            stock_status = "LOW_STOCK"
            risk_level = "LOW_STOCK"
            risk_reason = "库存低于阈值，但当前有效销量为0。"
        else:
            stock_status = "NORMAL"
            risk_level = "NORMAL"
            risk_reason = "库存未低于预警阈值。"
        inventory_rows.append(
            InventoryAnalysis(
                dish_id=dish.dish_id,
                merchant_id=dish.merchant_id,
                dish_name=dish.dish_name,
                sale_status=dish.sale_status,
                stock=dish.stock,
                stock_status=stock_status,
                valid_sales_quantity=quantity,
                valid_sales_amount=revenue_by_dish[dish.dish_id],
                sales_to_stock_ratio=(
                    (Decimal(quantity) / Decimal(dish.stock)).quantize(
                        _TWO_PLACES,
                        rounding=ROUND_HALF_UP,
                    )
                    if dish.stock > 0
                    else None
                ),
                recommended_restock_quantity=(
                    max(restock_target - dish.stock, 0)
                    if dish.stock < low_stock_threshold
                    else 0
                ),
                risk_level=risk_level,
                risk_reason=risk_reason,
            )
        )
    risk_order = {
        "OUT_OF_STOCK": 0,
        "LOW_STOCK_WITH_SALES": 1,
        "LOW_STOCK": 2,
        "NORMAL": 3,
    }
    inventory_analysis = tuple(
        sorted(
            inventory_rows,
            key=lambda row: (
                risk_order[row.risk_level],
                -(
                    row.sales_to_stock_ratio
                    if row.sales_to_stock_ratio is not None
                    else Decimal("999999")
                ),
                row.stock,
                row.dish_id,
            ),
        )
    )

    notifications = _build_notifications(dataset.orders)
    cancellation_rate = _percent(
        status_counts["CANCELLED"],
        len(dataset.orders),
    )
    on_sale_count = sum(
        dish.sale_status == "ON_SALE" for dish in dataset.dishes
    )
    low_stock_count = sum(
        0 < dish.stock < low_stock_threshold for dish in dataset.dishes
    )
    out_of_stock_count = sum(dish.stock == 0 for dish in dataset.dishes)
    limitations = _build_limitations(
        order_count=len(dataset.orders),
        order_date_count=len(all_daily_counts),
        merchant_count=len(merchant_ids),
    )
    insights = _build_insights(
        dish_sales_by_quantity=by_quantity,
        daily_sales=daily_sales,
        total_order_count=len(dataset.orders),
        cancelled_order_count=status_counts["CANCELLED"],
        cancellation_rate_percent=cancellation_rate,
        low_stock_count=low_stock_count,
        out_of_stock_count=out_of_stock_count,
        inventory_analysis=inventory_analysis,
        merchant_performance=merchant_performance,
    )
    return AnalysisResult(
        input_dish_count=len(dataset.dishes),
        input_order_count=len(dataset.orders),
        input_order_item_count=len(dataset.order_items),
        low_stock_threshold=low_stock_threshold,
        restock_target=restock_target,
        valid_order_count=len(valid_orders),
        cancelled_order_count=status_counts["CANCELLED"],
        cancellation_rate_percent=cancellation_rate,
        valid_sales_quantity=valid_sales_quantity,
        valid_sales_total=valid_total,
        average_valid_order_amount=average_valid_order_amount,
        on_sale_dish_count=on_sale_count,
        low_stock_dish_count=low_stock_count,
        out_of_stock_dish_count=out_of_stock_count,
        dish_sales_by_quantity=by_quantity,
        dish_sales_by_revenue=by_revenue,
        dish_performance=dish_performance,
        daily_sales=daily_sales,
        daily_order_analysis=daily_order_analysis,
        order_status_counts=tuple(status_counts.items()),
        order_status_analysis=order_status_analysis,
        merchant_performance=merchant_performance,
        merchant_order_status=tuple(merchant_status_rows),
        hourly_order_distribution=hourly_distribution,
        item_kind_distribution=item_kind_distribution,
        item_quantity_distribution=item_quantity_distribution,
        single_item_order_count=single_item_order_count,
        multi_item_order_count=multi_item_order_count,
        inventory_analysis=inventory_analysis,
        insights=insights,
        limitations=limitations,
        low_stock_alerts=low_stock_alerts,
        notifications=notifications,
    )


def _percent(numerator: object, denominator: object) -> Decimal:
    numerator_decimal = Decimal(numerator)
    denominator_decimal = Decimal(denominator)
    if denominator_decimal == 0:
        return _ZERO_MONEY
    return (
        numerator_decimal * Decimal("100") / denominator_decimal
    ).quantize(_TWO_PLACES, rounding=ROUND_HALF_UP)


def _average_money(total: Decimal, count: int) -> Decimal:
    if count == 0:
        return _ZERO_MONEY
    return (total / Decimal(count)).quantize(
        _TWO_PLACES,
        rounding=ROUND_HALF_UP,
    )


def _distribution(
    counts: dict[int, int],
    total: int,
) -> tuple[DistributionBucket, ...]:
    return tuple(
        DistributionBucket(
            bucket=str(bucket),
            order_count=counts[bucket],
            share_percent=_percent(counts[bucket], total),
        )
        for bucket in sorted(counts)
    )


def _build_limitations(
    *,
    order_count: int,
    order_date_count: int,
    merchant_count: int,
) -> tuple[str, ...]:
    limitations: list[str] = []
    if order_count < 10:
        limitations.append(
            f"当前仅有 {order_count} 张订单，时段分布只反映现有样本，"
            "不足以判断稳定下单高峰。"
        )
    if order_date_count < 3:
        limitations.append(
            f"当前订单仅覆盖 {order_date_count} 个下单日期，"
            "销售变化不能视为长期趋势。"
        )
    if merchant_count < 2:
        limitations.append(
            "当前数据只有一个商家，商家区域展示其经营数据，"
            "不作商家间优劣比较。"
        )
    return tuple(limitations)


def _build_insights(
    *,
    dish_sales_by_quantity: tuple[DishSales, ...],
    daily_sales: tuple[DailySales, ...],
    total_order_count: int,
    cancelled_order_count: int,
    cancellation_rate_percent: Decimal,
    low_stock_count: int,
    out_of_stock_count: int,
    inventory_analysis: tuple[InventoryAnalysis, ...],
    merchant_performance: tuple[MerchantPerformance, ...],
) -> tuple[Insight, ...]:
    insights: list[Insight] = []
    if dish_sales_by_quantity:
        top = dish_sales_by_quantity[0]
        insights.append(
            Insight(
                category="sales",
                level="info",
                title="热销菜品",
                content=(
                    f"{top.dish_name} 的有效销量最高，为 {top.quantity} 份，"
                    f"对应有效销售额 {top.sales_amount:.2f} 元。"
                ),
            )
        )
    else:
        insights.append(
            Insight(
                category="sales",
                level="neutral",
                title="暂无有效销售",
                content="当前没有可计入有效销量和有效销售额的订单。",
            )
        )

    if len(daily_sales) >= 2:
        first = daily_sales[0]
        last = daily_sales[-1]
        difference = last.sales_amount - first.sales_amount
        direction = "增加" if difference > 0 else "减少" if difference < 0 else "持平"
        insights.append(
            Insight(
                category="trend",
                level="info",
                title="销售日期对比",
                content=(
                    f"{first.sales_date.isoformat()} 至 {last.sales_date.isoformat()}，"
                    f"每日有效销售额从 {first.sales_amount:.2f} 元变为 "
                    f"{last.sales_amount:.2f} 元，{direction} {abs(difference):.2f} 元。"
                    "当前日期样本较少，不据此判断长期趋势。"
                ),
            )
        )
    elif len(daily_sales) == 1:
        only = daily_sales[0]
        insights.append(
            Insight(
                category="trend",
                level="neutral",
                title="趋势样本不足",
                content=(
                    f"当前只有 {only.sales_date.isoformat()} 一个有效销售日期，"
                    "无法比较跨日趋势。"
                ),
            )
        )
    else:
        insights.append(
            Insight(
                category="trend",
                level="neutral",
                title="暂无销售趋势",
                content="当前没有有效销售日期。",
            )
        )

    insights.append(
        Insight(
            category="cancellation",
            level="warning" if cancelled_order_count else "neutral",
            title="取消情况",
            content=(
                f"共 {total_order_count} 张订单，其中取消 {cancelled_order_count} 张，"
                f"取消率为 {cancellation_rate_percent:.2f}%。"
            ),
        )
    )

    risky = next(
        (
            row
            for row in inventory_analysis
            if row.risk_level != "NORMAL"
        ),
        None,
    )
    if risky is None:
        inventory_content = "当前没有低库存或缺货菜品。"
        inventory_level = "neutral"
    else:
        inventory_content = (
            f"当前有 {low_stock_count} 个低库存菜品，另有 "
            f"{out_of_stock_count} 个缺货菜品。"
            f"风险排序首位为 {risky.dish_name}，建议补货 "
            f"{risky.recommended_restock_quantity} 份。该排序是风险建议，不是需求预测。"
        )
        inventory_level = "warning"
    insights.append(
        Insight(
            category="inventory",
            level=inventory_level,
            title="库存风险",
            content=inventory_content,
        )
    )

    if len(merchant_performance) == 1:
        merchant = merchant_performance[0]
        merchant_content = (
            f"当前仅有商家 {merchant.merchant_id}，其有效订单 "
            f"{merchant.valid_order_count} 张，有效销售额 {merchant.valid_sales_amount:.2f} 元。"
            "因缺少其他商家样本，不作排名优劣判断。"
        )
    elif merchant_performance:
        merchant = merchant_performance[0]
        merchant_content = (
            f"按有效销售额排序，{merchant.merchant_id} 位列第一，"
            f"有效销售额为 {merchant.valid_sales_amount:.2f} 元。"
        )
    else:
        merchant_content = "当前没有商家订单或菜品数据。"
    insights.append(
        Insight(
            category="merchant",
            level="info" if merchant_performance else "neutral",
            title="商家分析",
            content=merchant_content,
        )
    )
    return tuple(insights)


def _build_notifications(orders: tuple[Order, ...]) -> tuple[Notification, ...]:
    notifications: list[Notification] = []
    for order in sorted(orders, key=lambda row: (row.order_time, row.order_id)):
        notifications.append(
            _notification(order, "PLACED", order.order_time)
        )
        if order.order_status != "PLACED":
            notifications.append(
                _notification(
                    order,
                    order.order_status,
                    order.status_updated_at,
                )
            )
    return tuple(notifications)


def _notification(
    order: Order,
    status: str,
    event_time: object,
) -> Notification:
    message = _STATUS_MESSAGES[status].format(
        order_id=order.order_id,
        amount=f"{order.total_amount:.2f}",
    )
    return Notification(
        event_time=event_time,
        student_id=order.student_id,
        order_id=order.order_id,
        event_status=status,
        message=f"【模拟短信】同学 {order.student_id}：{message}",
    )
