from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal


@dataclass(frozen=True)
class Dish:
    dish_id: str
    merchant_id: str
    dish_name: str
    unit_price: Decimal
    stock: int
    sale_status: str
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class Order:
    order_id: str
    student_id: str
    merchant_id: str
    order_time: datetime
    order_status: str
    total_amount: Decimal
    status_updated_at: datetime


@dataclass(frozen=True)
class OrderItem:
    order_item_id: str
    order_id: str
    dish_id: str
    dish_name: str
    quantity: int
    unit_price: Decimal
    subtotal: Decimal


@dataclass(frozen=True)
class Dataset:
    dishes: tuple[Dish, ...]
    orders: tuple[Order, ...]
    order_items: tuple[OrderItem, ...]


@dataclass(frozen=True)
class DishSales:
    dish_id: str
    dish_name: str
    quantity: int
    sales_amount: Decimal


@dataclass(frozen=True)
class DailySales:
    sales_date: date
    order_count: int
    sales_amount: Decimal


@dataclass(frozen=True)
class DishPerformance:
    dish_id: str
    merchant_id: str
    dish_name: str
    sale_status: str
    stock: int
    quantity: int
    sales_amount: Decimal
    quantity_share_percent: Decimal
    revenue_share_percent: Decimal


@dataclass(frozen=True)
class DailyOrderAnalysis:
    sales_date: date
    total_order_count: int
    valid_order_count: int
    cancelled_order_count: int
    cancellation_rate_percent: Decimal
    valid_sales_quantity: int
    valid_sales_amount: Decimal
    average_valid_order_amount: Decimal


@dataclass(frozen=True)
class OrderStatusAnalysis:
    order_status: str
    order_count: int
    share_percent: Decimal


@dataclass(frozen=True)
class MerchantPerformance:
    merchant_id: str
    total_order_count: int
    valid_order_count: int
    cancelled_order_count: int
    cancellation_rate_percent: Decimal
    valid_sales_quantity: int
    valid_sales_amount: Decimal
    average_valid_order_amount: Decimal
    dish_count: int
    on_sale_dish_count: int
    low_stock_dish_count: int
    out_of_stock_dish_count: int


@dataclass(frozen=True)
class MerchantOrderStatus:
    merchant_id: str
    order_status: str
    order_count: int


@dataclass(frozen=True)
class HourlyOrderDistribution:
    hour: int
    total_order_count: int
    valid_order_count: int
    cancelled_order_count: int


@dataclass(frozen=True)
class DistributionBucket:
    bucket: str
    order_count: int
    share_percent: Decimal


@dataclass(frozen=True)
class InventoryAnalysis:
    dish_id: str
    merchant_id: str
    dish_name: str
    sale_status: str
    stock: int
    stock_status: str
    valid_sales_quantity: int
    valid_sales_amount: Decimal
    sales_to_stock_ratio: Decimal | None
    recommended_restock_quantity: int
    risk_level: str
    risk_reason: str


@dataclass(frozen=True)
class Insight:
    category: str
    level: str
    title: str
    content: str


@dataclass(frozen=True)
class LowStockAlert:
    dish_id: str
    merchant_id: str
    dish_name: str
    stock: int
    threshold: int
    recommended_restock_quantity: int
    sale_status: str


@dataclass(frozen=True)
class Notification:
    event_time: datetime
    student_id: str
    order_id: str
    event_status: str
    message: str


@dataclass(frozen=True)
class AnalysisResult:
    input_dish_count: int
    input_order_count: int
    input_order_item_count: int
    low_stock_threshold: int
    restock_target: int
    valid_order_count: int
    cancelled_order_count: int
    cancellation_rate_percent: Decimal
    valid_sales_quantity: int
    valid_sales_total: Decimal
    average_valid_order_amount: Decimal
    on_sale_dish_count: int
    low_stock_dish_count: int
    out_of_stock_dish_count: int
    dish_sales_by_quantity: tuple[DishSales, ...]
    dish_sales_by_revenue: tuple[DishSales, ...]
    dish_performance: tuple[DishPerformance, ...]
    daily_sales: tuple[DailySales, ...]
    daily_order_analysis: tuple[DailyOrderAnalysis, ...]
    order_status_counts: tuple[tuple[str, int], ...]
    order_status_analysis: tuple[OrderStatusAnalysis, ...]
    merchant_performance: tuple[MerchantPerformance, ...]
    merchant_order_status: tuple[MerchantOrderStatus, ...]
    hourly_order_distribution: tuple[HourlyOrderDistribution, ...]
    item_kind_distribution: tuple[DistributionBucket, ...]
    item_quantity_distribution: tuple[DistributionBucket, ...]
    single_item_order_count: int
    multi_item_order_count: int
    inventory_analysis: tuple[InventoryAnalysis, ...]
    insights: tuple[Insight, ...]
    limitations: tuple[str, ...]
    low_stock_alerts: tuple[LowStockAlert, ...]
    notifications: tuple[Notification, ...]
