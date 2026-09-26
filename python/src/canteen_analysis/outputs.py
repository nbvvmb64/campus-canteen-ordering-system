from __future__ import annotations

import csv
import json
import os
import shutil
import uuid
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Iterable, Sequence
from zoneinfo import ZoneInfo

from .dashboard import build_dashboard_payload, write_dashboard
from .models import AnalysisResult, Dataset

_STATUS_LABELS = {
    "PLACED": "已下单",
    "PREPARING": "制作中",
    "COMPLETED": "已完成",
    "CANCELLED": "已取消",
}


def write_outputs(
    result: AnalysisResult,
    dataset: Dataset,
    output_dir: Path | str,
    *,
    data_dir: Path | str,
    top_n: int,
) -> tuple[Path, ...]:
    """在同级暂存目录生成完整成果，成功后再整体发布。

    生成失败会保留旧输出；发布失败会尝试恢复旧输出。
    """
    if top_n <= 0:
        raise ValueError("top_n 必须大于 0")

    root = Path(output_dir).resolve()
    parent = root.parent
    parent.mkdir(parents=True, exist_ok=True)
    token = uuid.uuid4().hex
    staging = parent / f".{root.name}-staging-{token}"
    backup = parent / f".{root.name}-backup-{token}"
    old_output_moved = False
    published = False
    try:
        staged_paths = _write_outputs_to_root(
            result,
            dataset,
            staging,
            data_dir=data_dir,
            top_n=top_n,
        )
        relative_paths = tuple(path.relative_to(staging) for path in staged_paths)
        if root.exists():
            root.replace(backup)
            old_output_moved = True
        staging.replace(root)
        published = True
        if backup.exists():
            shutil.rmtree(backup)
        return tuple(root / path for path in relative_paths)
    except Exception:
        if old_output_moved and not published and backup.exists():
            backup.replace(root)
        raise
    finally:
        if staging.exists():
            shutil.rmtree(staging)
        if backup.exists() and root.exists():
            shutil.rmtree(backup)


def _write_outputs_to_root(
    result: AnalysisResult,
    dataset: Dataset,
    output_dir: Path | str,
    *,
    data_dir: Path | str,
    top_n: int,
) -> tuple[Path, ...]:
    root = Path(output_dir).resolve()
    analysis_dir = root / "analysis"
    charts_dir = root / "charts"
    alerts_dir = root / "alerts"
    notifications_dir = root / "notifications"
    dashboard_dir = root / "dashboard"
    for directory in (
        analysis_dir,
        charts_dir,
        alerts_dir,
        notifications_dir,
        dashboard_dir,
    ):
        directory.mkdir(parents=True, exist_ok=True)

    generated_at = datetime.now(ZoneInfo("Asia/Shanghai"))
    dashboard_payload = build_dashboard_payload(
        result,
        data_dir,
        generated_at,
    )
    generated: list[Path] = []
    generated.append(
        _write_csv(
            analysis_dir / "summary.csv",
            ("metric", "value"),
            (
                ("input_dish_count", result.input_dish_count),
                ("input_order_count", result.input_order_count),
                ("input_order_item_count", result.input_order_item_count),
                ("valid_order_count", result.valid_order_count),
                ("cancelled_order_count", result.cancelled_order_count),
                (
                    "cancellation_rate_percent",
                    _money(result.cancellation_rate_percent),
                ),
                ("valid_sales_quantity", result.valid_sales_quantity),
                ("valid_sales_total", _money(result.valid_sales_total)),
                (
                    "average_valid_order_amount",
                    _money(result.average_valid_order_amount),
                ),
                ("on_sale_dish_count", result.on_sale_dish_count),
                ("low_stock_dish_count", result.low_stock_dish_count),
                ("out_of_stock_dish_count", result.out_of_stock_dish_count),
            ),
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "core_metrics.csv",
            ("metric", "metric_name", "value", "unit"),
            (
                ("total_order_count", "总订单数", result.input_order_count, "单"),
                ("valid_order_count", "有效订单数", result.valid_order_count, "单"),
                (
                    "cancelled_order_count",
                    "取消订单数",
                    result.cancelled_order_count,
                    "单",
                ),
                (
                    "cancellation_rate_percent",
                    "取消率",
                    _money(result.cancellation_rate_percent),
                    "%",
                ),
                (
                    "valid_sales_quantity",
                    "有效销量",
                    result.valid_sales_quantity,
                    "份",
                ),
                (
                    "valid_sales_total",
                    "有效销售额",
                    _money(result.valid_sales_total),
                    "元",
                ),
                (
                    "average_valid_order_amount",
                    "平均有效订单金额",
                    _money(result.average_valid_order_amount),
                    "元/单",
                ),
                (
                    "on_sale_dish_count",
                    "在售菜品数",
                    result.on_sale_dish_count,
                    "个",
                ),
                (
                    "low_stock_dish_count",
                    "低库存菜品数",
                    result.low_stock_dish_count,
                    "个",
                ),
                (
                    "out_of_stock_dish_count",
                    "缺货菜品数",
                    result.out_of_stock_dish_count,
                    "个",
                ),
            ),
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "dish_sales_by_quantity.csv",
            ("rank", "dish_id", "dish_name", "quantity", "sales_amount"),
            (
                (
                    index,
                    row.dish_id,
                    row.dish_name,
                    row.quantity,
                    _money(row.sales_amount),
                )
                for index, row in enumerate(
                    result.dish_sales_by_quantity,
                    start=1,
                )
            ),
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "dish_performance.csv",
            (
                "dish_id",
                "merchant_id",
                "dish_name",
                "sale_status",
                "stock",
                "valid_sales_quantity",
                "valid_sales_amount",
                "quantity_share_percent",
                "revenue_share_percent",
            ),
            (
                (
                    row.dish_id,
                    row.merchant_id,
                    row.dish_name,
                    row.sale_status,
                    row.stock,
                    row.quantity,
                    _money(row.sales_amount),
                    _money(row.quantity_share_percent),
                    _money(row.revenue_share_percent),
                )
                for row in result.dish_performance
            ),
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "dish_sales_by_revenue.csv",
            ("rank", "dish_id", "dish_name", "quantity", "sales_amount"),
            (
                (
                    index,
                    row.dish_id,
                    row.dish_name,
                    row.quantity,
                    _money(row.sales_amount),
                )
                for index, row in enumerate(
                    result.dish_sales_by_revenue,
                    start=1,
                )
            ),
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "daily_order_analysis.csv",
            (
                "date",
                "total_order_count",
                "valid_order_count",
                "cancelled_order_count",
                "cancellation_rate_percent",
                "valid_sales_quantity",
                "valid_sales_amount",
                "average_valid_order_amount",
            ),
            (
                (
                    row.sales_date.isoformat(),
                    row.total_order_count,
                    row.valid_order_count,
                    row.cancelled_order_count,
                    _money(row.cancellation_rate_percent),
                    row.valid_sales_quantity,
                    _money(row.valid_sales_amount),
                    _money(row.average_valid_order_amount),
                )
                for row in result.daily_order_analysis
            ),
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "daily_sales.csv",
            ("date", "valid_order_count", "sales_amount"),
            (
                (
                    row.sales_date.isoformat(),
                    row.order_count,
                    _money(row.sales_amount),
                )
                for row in result.daily_sales
            ),
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "merchant_performance.csv",
            (
                "merchant_id",
                "total_order_count",
                "valid_order_count",
                "cancelled_order_count",
                "cancellation_rate_percent",
                "valid_sales_quantity",
                "valid_sales_amount",
                "average_valid_order_amount",
                "dish_count",
                "on_sale_dish_count",
                "low_stock_dish_count",
                "out_of_stock_dish_count",
            ),
            (
                (
                    row.merchant_id,
                    row.total_order_count,
                    row.valid_order_count,
                    row.cancelled_order_count,
                    _money(row.cancellation_rate_percent),
                    row.valid_sales_quantity,
                    _money(row.valid_sales_amount),
                    _money(row.average_valid_order_amount),
                    row.dish_count,
                    row.on_sale_dish_count,
                    row.low_stock_dish_count,
                    row.out_of_stock_dish_count,
                )
                for row in result.merchant_performance
            ),
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "merchant_order_status.csv",
            ("merchant_id", "order_status", "status_name", "order_count"),
            (
                (
                    row.merchant_id,
                    row.order_status,
                    _STATUS_LABELS[row.order_status],
                    row.order_count,
                )
                for row in result.merchant_order_status
            ),
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "hourly_order_distribution.csv",
            (
                "hour",
                "hour_label",
                "total_order_count",
                "valid_order_count",
                "cancelled_order_count",
            ),
            (
                (
                    row.hour,
                    f"{row.hour:02d}:00",
                    row.total_order_count,
                    row.valid_order_count,
                    row.cancelled_order_count,
                )
                for row in result.hourly_order_distribution
            ),
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "order_composition.csv",
            ("metric", "bucket", "order_count", "share_percent"),
            (
                (
                    "order_type",
                    "single_item",
                    result.single_item_order_count,
                    _percent_value(
                        result.single_item_order_count,
                        result.valid_order_count,
                    ),
                ),
                (
                    "order_type",
                    "multi_item",
                    result.multi_item_order_count,
                    _percent_value(
                        result.multi_item_order_count,
                        result.valid_order_count,
                    ),
                ),
            )
            + tuple(
                (
                    "item_kind_count",
                    row.bucket,
                    row.order_count,
                    _money(row.share_percent),
                )
                for row in result.item_kind_distribution
            ),
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "order_quantity_distribution.csv",
            ("metric", "bucket", "order_count", "share_percent"),
            (
                (
                    "item_quantity",
                    row.bucket,
                    row.order_count,
                    _money(row.share_percent),
                )
                for row in result.item_quantity_distribution
            ),
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "inventory_analysis.csv",
            (
                "risk_rank",
                "dish_id",
                "merchant_id",
                "dish_name",
                "sale_status",
                "stock",
                "stock_status",
                "valid_sales_quantity",
                "valid_sales_amount",
                "sales_to_stock_ratio",
                "recommended_restock_quantity",
                "risk_level",
                "risk_reason",
            ),
            (
                (
                    index,
                    row.dish_id,
                    row.merchant_id,
                    row.dish_name,
                    row.sale_status,
                    row.stock,
                    row.stock_status,
                    row.valid_sales_quantity,
                    _money(row.valid_sales_amount),
                    (
                        _money(row.sales_to_stock_ratio)
                        if row.sales_to_stock_ratio is not None
                        else ""
                    ),
                    row.recommended_restock_quantity,
                    row.risk_level,
                    row.risk_reason,
                )
                for index, row in enumerate(result.inventory_analysis, start=1)
            ),
        )
    )
    generated.append(
        _write_json(
            analysis_dir / "dashboard_data.json",
            dashboard_payload,
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "order_status_counts.csv",
            ("order_status", "status_name", "order_count"),
            (
                (status, _STATUS_LABELS[status], count)
                for status, count in result.order_status_counts
            ),
        )
    )
    generated.append(
        _write_csv(
            analysis_dir / "order_status_analysis.csv",
            ("order_status", "status_name", "order_count", "share_percent"),
            (
                (
                    row.order_status,
                    _STATUS_LABELS[row.order_status],
                    row.order_count,
                    _money(row.share_percent),
                )
                for row in result.order_status_analysis
            ),
        )
    )
    generated.append(
        _write_json(
            analysis_dir / "analysis_report.json",
            {
                "validation_status": "PASSED",
                "summary": {
                    "input_dish_count": result.input_dish_count,
                    "input_order_count": result.input_order_count,
                    "input_order_item_count": result.input_order_item_count,
                    "low_stock_threshold": result.low_stock_threshold,
                    "restock_target": result.restock_target,
                    "valid_order_count": result.valid_order_count,
                    "cancelled_order_count": result.cancelled_order_count,
                    "cancellation_rate_percent": _money(
                        result.cancellation_rate_percent
                    ),
                    "valid_sales_quantity": result.valid_sales_quantity,
                    "valid_sales_total": _money(result.valid_sales_total),
                    "average_valid_order_amount": _money(
                        result.average_valid_order_amount
                    ),
                    "on_sale_dish_count": result.on_sale_dish_count,
                    "low_stock_dish_count": result.low_stock_dish_count,
                    "out_of_stock_dish_count": result.out_of_stock_dish_count,
                },
                "effective_sales_rule": (
                    "order_status in PLACED, PREPARING, COMPLETED"
                ),
                "cancelled_orders_excluded": True,
            },
        )
    )

    generated.append(
        _write_csv(
            alerts_dir / "low_stock_alerts.csv",
            (
                "dish_id",
                "merchant_id",
                "dish_name",
                "stock",
                "threshold",
                "recommended_restock_quantity",
                "sale_status",
            ),
            (
                (
                    row.dish_id,
                    row.merchant_id,
                    row.dish_name,
                    row.stock,
                    row.threshold,
                    row.recommended_restock_quantity,
                    row.sale_status,
                )
                for row in result.low_stock_alerts
            ),
        )
    )
    generated.append(
        _write_text(
            alerts_dir / "low_stock_alerts.txt",
            _low_stock_text(result),
        )
    )
    generated.append(
        _write_csv(
            notifications_dir / "order_notifications.csv",
            (
                "event_time",
                "student_id",
                "order_id",
                "event_status",
                "message",
            ),
            (
                (
                    row.event_time.strftime("%Y-%m-%d %H:%M:%S"),
                    row.student_id,
                    row.order_id,
                    row.event_status,
                    row.message,
                )
                for row in result.notifications
            ),
        )
    )
    generated.append(
        _write_text(
            notifications_dir / "order_notifications.txt",
            _notification_text(result),
        )
    )
    generated.extend(
        _write_charts(
            result,
            dataset,
            charts_dir,
            top_n=top_n,
        )
    )
    generated.extend(write_dashboard(dashboard_payload, dashboard_dir))
    return tuple(generated)


def _write_csv(
    path: Path,
    header: Sequence[object],
    rows: Iterable[Sequence[object]],
) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(
                handle,
                delimiter=",",
                quotechar='"',
                lineterminator="\r\n",
            )
            writer.writerow(header)
            writer.writerows(rows)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)
    return path


def _write_json(path: Path, payload: object) -> Path:
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    return _write_text(path, text)


def _write_text(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_text(text, encoding="utf-8", newline="\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)
    return path


def _low_stock_text(result: AnalysisResult) -> str:
    if not result.low_stock_alerts:
        return "当前无低库存菜品（判定规则：0 < stock < threshold）。\n"
    lines = ["低库存预警（判定规则：0 < stock < threshold）", ""]
    for row in result.low_stock_alerts:
        lines.append(
            f"- {row.dish_id} {row.dish_name}：当前库存 {row.stock}，"
            f"阈值 {row.threshold}，建议补货 {row.recommended_restock_quantity}。"
        )
    return "\n".join(lines) + "\n"


def _notification_text(result: AnalysisResult) -> str:
    if not result.notifications:
        return "当前没有可生成的订单状态模拟短信。\n"
    return "\n".join(
        f"[{row.event_time:%Y-%m-%d %H:%M:%S}] {row.message}"
        for row in result.notifications
    ) + "\n"


def _write_charts(
    result: AnalysisResult,
    dataset: Dataset,
    charts_dir: Path,
    *,
    top_n: int,
) -> tuple[Path, ...]:
    charts_dir.mkdir(parents=True, exist_ok=True)
    previous_config_dir = os.environ.get("MPLCONFIGDIR")
    matplotlib_config = charts_dir.parent / f".matplotlib-{uuid.uuid4().hex}"
    matplotlib_config.mkdir(parents=True)
    os.environ["MPLCONFIGDIR"] = str(matplotlib_config)
    try:
        try:
            import matplotlib

            matplotlib.use("Agg")
            from matplotlib import font_manager
            from matplotlib import pyplot as plt
        except ImportError as exc:
            raise RuntimeError(
                "生成图表需要 Matplotlib，请安装 python/requirements.txt "
                "中的依赖后重试"
            ) from exc

        available_fonts = {font.name for font in font_manager.fontManager.ttflist}
        preferred_fonts = (
            "Microsoft YaHei",
            "SimHei",
            "Noto Sans CJK SC",
            "Source Han Sans SC",
            "Arial Unicode MS",
            "DejaVu Sans",
        )
        selected_font = next(
            (font for font in preferred_fonts if font in available_fonts),
            "DejaVu Sans",
        )
        plt.rcParams["font.sans-serif"] = [selected_font]
        plt.rcParams["axes.unicode_minus"] = False
        plt.rcParams["figure.facecolor"] = "white"

        generated = [
            _chart_sales_quantity(plt, result, charts_dir, top_n),
            _chart_sales_revenue(plt, result, charts_dir, top_n),
            _chart_daily_trend(plt, result, charts_dir),
            _chart_daily_cancellation(plt, result, charts_dir),
            _chart_statuses(plt, result, charts_dir),
            _chart_order_composition(plt, result, charts_dir),
            _chart_merchants(plt, result, charts_dir),
            _chart_inventory(plt, result, dataset, charts_dir),
            _chart_inventory_risk(plt, result, charts_dir),
        ]
    finally:
        if previous_config_dir is None:
            os.environ.pop("MPLCONFIGDIR", None)
        else:
            os.environ["MPLCONFIGDIR"] = previous_config_dir
        shutil.rmtree(matplotlib_config, ignore_errors=False)
    return tuple(generated)


def _chart_sales_quantity(plt: object, result: AnalysisResult, charts_dir: Path, top_n: int) -> Path:
    rows = result.dish_sales_by_quantity[:top_n]
    fig, axis = plt.subplots(figsize=(9, 5.4))
    if rows:
        labels = [row.dish_name for row in reversed(rows)]
        values = [row.quantity for row in reversed(rows)]
        bars = axis.barh(labels, values, color="#2F80ED")
        axis.bar_label(bars, padding=4)
        axis.set_xlabel("有效销量（份）")
        axis.set_ylabel("菜品")
    else:
        _empty_chart(axis, "暂无有效订单销量数据")
    axis.set_title(f"热销菜品排行榜（按销量，Top {top_n}）")
    axis.grid(axis="x", alpha=0.2)
    return _save_figure(plt, fig, charts_dir / "dish_sales_quantity.png")


def _chart_sales_revenue(plt: object, result: AnalysisResult, charts_dir: Path, top_n: int) -> Path:
    rows = result.dish_sales_by_revenue[:top_n]
    fig, axis = plt.subplots(figsize=(9, 5.4))
    if rows:
        labels = [row.dish_name for row in reversed(rows)]
        values = [float(row.sales_amount) for row in reversed(rows)]
        bars = axis.barh(labels, values, color="#27AE60")
        axis.bar_label(
            bars,
            labels=[f"{value:.2f}" for value in values],
            padding=4,
        )
        axis.set_xlabel("有效销售额（元）")
        axis.set_ylabel("菜品")
    else:
        _empty_chart(axis, "暂无有效订单销售额数据")
    axis.set_title(f"热销菜品排行榜（按销售额，Top {top_n}）")
    axis.grid(axis="x", alpha=0.2)
    return _save_figure(plt, fig, charts_dir / "dish_sales_revenue.png")


def _chart_daily_trend(plt: object, result: AnalysisResult, charts_dir: Path) -> Path:
    fig, revenue_axis = plt.subplots(figsize=(10, 5.6))
    if result.daily_sales:
        dates = [row.sales_date.strftime("%m-%d") for row in result.daily_sales]
        revenues = [float(row.sales_amount) for row in result.daily_sales]
        counts = [row.order_count for row in result.daily_sales]
        positions = list(range(len(dates)))
        revenue_axis.plot(
            positions,
            revenues,
            color="#EB5757",
            marker="o",
            linewidth=2.2,
            label="销售额",
        )
        revenue_axis.set_ylabel("有效销售额（元）", color="#EB5757")
        revenue_axis.tick_params(axis="y", labelcolor="#EB5757")
        revenue_axis.set_xlabel("日期")
        revenue_axis.set_xticks(positions, dates)
        revenue_axis.set_xlim(-0.6, max(len(dates) - 0.4, 0.6))
        revenue_axis.set_ylim(
            bottom=0,
            top=max(max(revenues) * 1.18, 1),
        )
        count_axis = revenue_axis.twinx()
        count_axis.bar(
            positions,
            counts,
            color="#56CCF2",
            alpha=0.3,
            width=0.36,
            label="订单量",
        )
        count_axis.set_ylabel("有效订单量（单）", color="#2D9CDB")
        count_axis.tick_params(axis="y", labelcolor="#2D9CDB")
        count_axis.set_ylim(bottom=0, top=max(max(counts) * 1.25, 1))
    else:
        _empty_chart(revenue_axis, "暂无有效订单趋势数据")
    revenue_axis.set_title("每日有效订单量与销售额趋势")
    revenue_axis.grid(axis="y", alpha=0.2)
    return _save_figure(plt, fig, charts_dir / "daily_sales_trend.png")


def _chart_statuses(plt: object, result: AnalysisResult, charts_dir: Path) -> Path:
    fig, axis = plt.subplots(figsize=(8.5, 5.4))
    statuses = [status for status, _ in result.order_status_counts]
    counts = [count for _, count in result.order_status_counts]
    labels = [_STATUS_LABELS[status] for status in statuses]
    colors = ["#2F80ED", "#F2C94C", "#27AE60", "#BDBDBD"]
    bars = axis.bar(labels, counts, color=colors)
    axis.bar_label(bars, padding=4)
    axis.set_ylabel("订单数量（单）")
    axis.set_title("订单状态分布")
    axis.set_ylim(bottom=0)
    axis.grid(axis="y", alpha=0.2)
    return _save_figure(plt, fig, charts_dir / "order_status_distribution.png")


def _chart_daily_cancellation(
    plt: object,
    result: AnalysisResult,
    charts_dir: Path,
) -> Path:
    fig, axis = plt.subplots(figsize=(10, 5.4))
    rows = result.daily_order_analysis
    if rows:
        positions = list(range(len(rows)))
        labels = [row.sales_date.strftime("%m-%d") for row in rows]
        valid = [row.valid_order_count for row in rows]
        cancelled = [row.cancelled_order_count for row in rows]
        axis.bar(
            positions,
            valid,
            width=0.62,
            color="#2F80ED",
            label="有效订单",
        )
        axis.bar(
            positions,
            cancelled,
            width=0.62,
            bottom=valid,
            color="#BDBDBD",
            label="取消订单",
        )
        axis.set_xticks(positions, labels)
        axis.legend()
        axis.set_xlabel("下单日期")
        axis.set_ylabel("订单数量（单）")
    else:
        _empty_chart(axis, "暂无订单取消趋势数据")
    axis.set_title("按下单日期统计的订单与取消情况")
    axis.grid(axis="y", alpha=0.2)
    return _save_figure(
        plt,
        fig,
        charts_dir / "daily_cancellation_trend.png",
    )


def _chart_order_composition(
    plt: object,
    result: AnalysisResult,
    charts_dir: Path,
) -> Path:
    fig, axis = plt.subplots(figsize=(8.5, 5.4))
    values = [result.single_item_order_count, result.multi_item_order_count]
    labels = ["单菜品订单", "多菜品订单"]
    bars = axis.bar(labels, values, color=["#56CCF2", "#9B51E0"])
    axis.bar_label(bars, padding=4)
    axis.set_ylabel("有效订单数量（单）")
    axis.set_title("单菜品与多菜品订单对比")
    axis.set_ylim(bottom=0)
    axis.grid(axis="y", alpha=0.2)
    return _save_figure(plt, fig, charts_dir / "order_composition.png")


def _chart_merchants(
    plt: object,
    result: AnalysisResult,
    charts_dir: Path,
) -> Path:
    fig, axis = plt.subplots(figsize=(9, 5.4))
    rows = result.merchant_performance
    if rows:
        labels = [row.merchant_id for row in reversed(rows)]
        values = [float(row.valid_sales_amount) for row in reversed(rows)]
        bars = axis.barh(labels, values, color="#0F9F8F")
        axis.bar_label(
            bars,
            labels=[f"{value:.2f}" for value in values],
            padding=4,
        )
        axis.set_xlabel("有效销售额（元）")
        axis.set_ylabel("商家编号")
    else:
        _empty_chart(axis, "暂无商家销售数据")
    axis.set_title("商家有效销售额")
    axis.grid(axis="x", alpha=0.2)
    return _save_figure(plt, fig, charts_dir / "merchant_performance.png")


def _chart_inventory(
    plt: object,
    result: AnalysisResult,
    dataset: Dataset,
    charts_dir: Path,
) -> Path:
    fig, axis = plt.subplots(figsize=(9, 5.4))
    dishes = sorted(dataset.dishes, key=lambda row: (row.stock, row.dish_id))
    if dishes:
        threshold = result.low_stock_threshold
        colors = [
            "#EB5757"
            if dish.stock == 0
            else "#F2994A"
            if 0 < dish.stock < threshold
            else "#2D9CDB"
            for dish in dishes
        ]
        bars = axis.barh(
            [dish.dish_name for dish in dishes],
            [dish.stock for dish in dishes],
            color=colors,
        )
        axis.bar_label(bars, padding=4)
        if threshold > 0:
            axis.axvline(
                threshold,
                color="#F2994A",
                linestyle="--",
                label=f"预警阈值 {threshold}",
            )
            axis.legend()
        axis.set_xlabel("当前库存（份）")
        axis.set_ylabel("菜品")
    else:
        _empty_chart(axis, "暂无菜品库存数据")
    axis.set_title("菜品库存状态")
    axis.grid(axis="x", alpha=0.2)
    return _save_figure(plt, fig, charts_dir / "inventory_status.png")


def _chart_inventory_risk(
    plt: object,
    result: AnalysisResult,
    charts_dir: Path,
) -> Path:
    fig, axis = plt.subplots(figsize=(9, 5.4))
    rows = tuple(
        row
        for row in result.inventory_analysis
        if row.risk_level != "NORMAL"
    )
    if rows:
        display_rows = tuple(reversed(rows))
        values = [row.recommended_restock_quantity for row in display_rows]
        colors = [
            "#C0392B" if row.stock_status == "OUT_OF_STOCK" else "#F2994A"
            for row in display_rows
        ]
        bars = axis.barh(
            [row.dish_name for row in display_rows],
            values,
            color=colors,
        )
        axis.bar_label(bars, padding=4)
        axis.set_xlabel("建议补货数量（份）")
        axis.set_ylabel("菜品")
    else:
        _empty_chart(axis, "当前无低库存或缺货菜品")
    axis.set_title("库存补货风险建议（不是需求预测）")
    axis.grid(axis="x", alpha=0.2)
    return _save_figure(plt, fig, charts_dir / "inventory_restock_risk.png")


def _empty_chart(axis: object, message: str) -> None:
    axis.text(
        0.5,
        0.5,
        message,
        ha="center",
        va="center",
        transform=axis.transAxes,
        fontsize=14,
        color="#666666",
    )
    axis.set_xticks([])
    axis.set_yticks([])


def _save_figure(plt: object, figure: object, path: Path) -> Path:
    temporary = path.with_name(f".{path.stem}.{uuid.uuid4().hex}.tmp")
    try:
        figure.tight_layout()
        figure.savefig(
            temporary,
            format="png",
            dpi=170,
            bbox_inches="tight",
        )
        plt.close(figure)
        temporary.replace(path)
    finally:
        plt.close(figure)
        temporary.unlink(missing_ok=True)
    return path


def _money(value: object) -> str:
    return f"{value:.2f}"


def _percent_value(numerator: int, denominator: int) -> str:
    if denominator == 0:
        return "0.00"
    value = Decimal(numerator) * Decimal("100") / Decimal(denominator)
    return f"{value.quantize(Decimal('0.01'), rounding=ROUND_HALF_UP):.2f}"
