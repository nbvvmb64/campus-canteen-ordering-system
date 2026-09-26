from __future__ import annotations

import codecs
import csv
import io
import re
from collections import defaultdict
from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path

from .errors import DataValidationError
from .models import Dataset, Dish, Order, OrderItem

DISH_HEADER = (
    "dish_id",
    "merchant_id",
    "dish_name",
    "unit_price",
    "stock",
    "sale_status",
    "created_at",
    "updated_at",
)
ORDER_HEADER = (
    "order_id",
    "student_id",
    "merchant_id",
    "order_time",
    "order_status",
    "total_amount",
    "status_updated_at",
)
ORDER_ITEM_HEADER = (
    "order_item_id",
    "order_id",
    "dish_id",
    "dish_name",
    "quantity",
    "unit_price",
    "subtotal",
)

ORDER_STATUSES = ("PLACED", "PREPARING", "COMPLETED", "CANCELLED")
SALE_STATUSES = ("ON_SALE", "OFF_SALE")

_ID_PATTERNS = {
    "user_id": re.compile(r"USR\d{6}\Z"),
    "dish_id": re.compile(r"DSH\d{6}\Z"),
    "order_id": re.compile(r"ORD\d{6}\Z"),
    "order_item_id": re.compile(r"ITM\d{6}\Z"),
}
_MONEY_PATTERN = re.compile(r"(?:0|[1-9]\d{0,9})\.\d{2}\Z")
_INTEGER_PATTERN = re.compile(r"(?:0|[1-9]\d*)\Z")
_DATETIME_FORMAT = "%Y-%m-%d %H:%M:%S"
_INT32_MAX = 2_147_483_647


def load_dataset(data_dir: Path | str) -> Dataset:
    """读取并完整校验 Java 维护的三个共享 CSV。"""

    directory = Path(data_dir).resolve()
    dish_rows = _read_rows(directory / "dishes.csv", DISH_HEADER)
    order_rows = _read_rows(directory / "orders.csv", ORDER_HEADER)
    item_rows = _read_rows(directory / "order_items.csv", ORDER_ITEM_HEADER)

    dishes = tuple(_parse_dish(path, row_number, row) for path, row_number, row in dish_rows)
    orders = tuple(_parse_order(path, row_number, row) for path, row_number, row in order_rows)
    items = tuple(_parse_item(path, row_number, row) for path, row_number, row in item_rows)

    _validate_primary_keys(dishes, orders, items)
    _validate_references_and_amounts(dishes, orders, items)
    return Dataset(dishes=dishes, orders=orders, order_items=items)


def _read_rows(
    path: Path,
    expected_header: tuple[str, ...],
) -> list[tuple[Path, int, tuple[str, ...]]]:
    if not path.is_file():
        raise DataValidationError(f"缺少必需文件: {path}")

    raw = path.read_bytes()
    if raw.startswith(codecs.BOM_UTF8):
        raise DataValidationError(f"{path.name} 不允许包含 UTF-8 BOM")
    try:
        content = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise DataValidationError(
            f"{path.name} 不是有效的 UTF-8 编码（字节位置 {exc.start}）"
        ) from exc

    if not content:
        raise DataValidationError(f"{path.name} 为空，必须至少包含固定表头")
    if not content.endswith("\r\n"):
        raise DataValidationError(f"{path.name} 必须使用 CRLF 换行并以 CRLF 结束")
    without_crlf = content.replace("\r\n", "")
    if "\r" in without_crlf or "\n" in without_crlf:
        raise DataValidationError(f"{path.name} 包含非 CRLF 换行")

    try:
        reader = csv.reader(io.StringIO(content, newline=""), strict=True)
        rows = list(reader)
    except csv.Error as exc:
        raise DataValidationError(f"{path.name} CSV 格式错误: {exc}") from exc

    if not rows:
        raise DataValidationError(f"{path.name} 为空，必须至少包含固定表头")
    if tuple(rows[0]) != expected_header:
        raise DataValidationError(
            f"{path.name} 表头或字段顺序不符合系统接口约定；"
            f"期望 {','.join(expected_header)}"
        )

    parsed_rows: list[tuple[Path, int, tuple[str, ...]]] = []
    for row_number, row in enumerate(rows[1:], start=2):
        if not row or all(value == "" for value in row):
            raise DataValidationError(f"{path.name} 第 {row_number} 行为空行")
        if len(row) != len(expected_header):
            raise DataValidationError(
                f"{path.name} 第 {row_number} 行字段数错误："
                f"期望 {len(expected_header)}，实际 {len(row)}"
            )
        parsed_rows.append((path, row_number, tuple(row)))
    return parsed_rows


def _parse_dish(path: Path, row_number: int, row: tuple[str, ...]) -> Dish:
    dish_id = _parse_id(row[0], "dish_id", path, row_number)
    merchant_id = _parse_id(row[1], "user_id", path, row_number, "merchant_id")
    dish_name = _parse_text(row[2], "dish_name", path, row_number, maximum=50)
    unit_price = _parse_money(row[3], "unit_price", path, row_number, positive=True)
    stock = _parse_int(row[4], "stock", path, row_number, minimum=0)
    sale_status = _parse_enum(
        row[5], "sale_status", SALE_STATUSES, path, row_number
    )
    created_at = _parse_datetime(row[6], "created_at", path, row_number)
    updated_at = _parse_datetime(row[7], "updated_at", path, row_number)
    if updated_at < created_at:
        _fail(path, row_number, "updated_at", "不能早于 created_at")
    return Dish(
        dish_id=dish_id,
        merchant_id=merchant_id,
        dish_name=dish_name,
        unit_price=unit_price,
        stock=stock,
        sale_status=sale_status,
        created_at=created_at,
        updated_at=updated_at,
    )


def _parse_order(path: Path, row_number: int, row: tuple[str, ...]) -> Order:
    order_id = _parse_id(row[0], "order_id", path, row_number)
    student_id = _parse_id(row[1], "user_id", path, row_number, "student_id")
    merchant_id = _parse_id(row[2], "user_id", path, row_number, "merchant_id")
    order_time = _parse_datetime(row[3], "order_time", path, row_number)
    order_status = _parse_enum(
        row[4], "order_status", ORDER_STATUSES, path, row_number
    )
    total_amount = _parse_money(
        row[5], "total_amount", path, row_number, positive=True
    )
    status_updated_at = _parse_datetime(
        row[6], "status_updated_at", path, row_number
    )
    if status_updated_at < order_time:
        _fail(path, row_number, "status_updated_at", "不能早于 order_time")
    return Order(
        order_id=order_id,
        student_id=student_id,
        merchant_id=merchant_id,
        order_time=order_time,
        order_status=order_status,
        total_amount=total_amount,
        status_updated_at=status_updated_at,
    )


def _parse_item(path: Path, row_number: int, row: tuple[str, ...]) -> OrderItem:
    return OrderItem(
        order_item_id=_parse_id(row[0], "order_item_id", path, row_number),
        order_id=_parse_id(row[1], "order_id", path, row_number),
        dish_id=_parse_id(row[2], "dish_id", path, row_number),
        dish_name=_parse_text(
            row[3], "dish_name", path, row_number, maximum=50
        ),
        quantity=_parse_int(
            row[4], "quantity", path, row_number, minimum=1
        ),
        unit_price=_parse_money(
            row[5], "unit_price", path, row_number, positive=True
        ),
        subtotal=_parse_money(
            row[6], "subtotal", path, row_number, positive=True
        ),
    )


def _validate_primary_keys(
    dishes: tuple[Dish, ...],
    orders: tuple[Order, ...],
    items: tuple[OrderItem, ...],
) -> None:
    _require_unique((dish.dish_id for dish in dishes), "dishes.csv dish_id")
    _require_unique((order.order_id for order in orders), "orders.csv order_id")
    _require_unique(
        (item.order_item_id for item in items),
        "order_items.csv order_item_id",
    )
    _require_unique(
        ((item.order_id, item.dish_id) for item in items),
        "order_items.csv (order_id,dish_id)",
    )


def _validate_references_and_amounts(
    dishes: tuple[Dish, ...],
    orders: tuple[Order, ...],
    items: tuple[OrderItem, ...],
) -> None:
    dishes_by_id = {dish.dish_id: dish for dish in dishes}
    orders_by_id = {order.order_id: order for order in orders}
    items_by_order: dict[str, list[OrderItem]] = defaultdict(list)

    for item in items:
        order = orders_by_id.get(item.order_id)
        if order is None:
            raise DataValidationError(
                f"订单明细 {item.order_item_id} 引用不存在的 order_id: {item.order_id}"
            )
        dish = dishes_by_id.get(item.dish_id)
        if dish is None:
            raise DataValidationError(
                f"订单明细 {item.order_item_id} 引用不存在的 dish_id: {item.dish_id}"
            )
        if dish.merchant_id != order.merchant_id:
            raise DataValidationError(
                f"订单明细 {item.order_item_id} 的菜品不属于订单商家"
            )
        expected_subtotal = item.unit_price * item.quantity
        if expected_subtotal != item.subtotal:
            raise DataValidationError(
                f"订单明细 {item.order_item_id} 小计不一致："
                f"期望 {expected_subtotal:.2f}，实际 {item.subtotal:.2f}"
            )
        items_by_order[item.order_id].append(item)

    for order in orders:
        order_items = items_by_order.get(order.order_id, [])
        if not order_items:
            raise DataValidationError(f"订单 {order.order_id} 没有订单明细")
        expected_total = sum(
            (item.subtotal for item in order_items),
            start=Decimal("0.00"),
        )
        if expected_total != order.total_amount:
            raise DataValidationError(
                f"订单 {order.order_id} 总金额不一致："
                f"期望 {expected_total:.2f}，实际 {order.total_amount:.2f}"
            )


def _parse_id(
    value: str,
    id_type: str,
    path: Path,
    row_number: int,
    field_name: str | None = None,
) -> str:
    pattern = _ID_PATTERNS[id_type]
    if not pattern.fullmatch(value):
        readable_pattern = pattern.pattern.removesuffix("\\Z")
        _fail(
            path,
            row_number,
            field_name or id_type,
            f"格式错误，必须符合 {readable_pattern}",
        )
    return value


def _parse_text(
    value: str,
    field_name: str,
    path: Path,
    row_number: int,
    maximum: int,
) -> str:
    if not value or value.strip() != value or len(value) > maximum:
        _fail(
            path,
            row_number,
            field_name,
            f"不能为空、不能有首尾空白，且长度不能超过 {maximum}",
        )
    return value


def _parse_money(
    value: str,
    field_name: str,
    path: Path,
    row_number: int,
    positive: bool,
) -> Decimal:
    if not _MONEY_PATTERN.fullmatch(value):
        _fail(
            path,
            row_number,
            field_name,
            "必须是 decimal(12,2) 且固定保留两位小数",
        )
    try:
        amount = Decimal(value)
    except InvalidOperation as exc:
        _fail(path, row_number, field_name, "不是有效十进制定点数")
        raise AssertionError from exc
    if positive and amount <= 0:
        _fail(path, row_number, field_name, "必须大于 0.00")
    return amount


def _parse_int(
    value: str,
    field_name: str,
    path: Path,
    row_number: int,
    minimum: int,
) -> int:
    if not _INTEGER_PATTERN.fullmatch(value):
        _fail(path, row_number, field_name, "必须是不带符号的十进制整数")
    parsed = int(value)
    if parsed < minimum or parsed > _INT32_MAX:
        _fail(
            path,
            row_number,
            field_name,
            f"必须位于 {minimum} 到 {_INT32_MAX} 之间",
        )
    return parsed


def _parse_datetime(
    value: str,
    field_name: str,
    path: Path,
    row_number: int,
) -> datetime:
    try:
        parsed = datetime.strptime(value, _DATETIME_FORMAT)
    except ValueError as exc:
        _fail(
            path,
            row_number,
            field_name,
            "必须使用 yyyy-MM-dd HH:mm:ss 格式",
        )
        raise AssertionError from exc
    if parsed.strftime(_DATETIME_FORMAT) != value:
        _fail(
            path,
            row_number,
            field_name,
            "必须使用 yyyy-MM-dd HH:mm:ss 格式",
        )
    return parsed


def _parse_enum(
    value: str,
    field_name: str,
    allowed: tuple[str, ...],
    path: Path,
    row_number: int,
) -> str:
    if value not in allowed:
        _fail(
            path,
            row_number,
            field_name,
            f"只能取 {', '.join(allowed)}",
        )
    return value


def _require_unique(values: object, label: str) -> None:
    seen: set[object] = set()
    for value in values:
        if value in seen:
            raise DataValidationError(f"{label} 重复: {value}")
        seen.add(value)


def _fail(
    path: Path,
    row_number: int,
    field_name: str,
    message: str,
) -> None:
    raise DataValidationError(
        f"{path.name} 第 {row_number} 行字段 {field_name}: {message}"
    )
