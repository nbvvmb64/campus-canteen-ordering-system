from __future__ import annotations

import hashlib
import json
import uuid
from collections import Counter
from datetime import datetime
from pathlib import Path

from .models import AnalysisResult

SCHEMA_VERSION = "1.0.0"

_STATUS_LABELS = {
    "PLACED": "已下单",
    "PREPARING": "制作中",
    "COMPLETED": "已完成",
    "CANCELLED": "已取消",
}

_STOCK_STATUS_LABELS = {
    "NORMAL": "库存正常",
    "LOW_STOCK": "低库存",
    "OUT_OF_STOCK": "缺货",
}


def build_dashboard_payload(
    result: AnalysisResult,
    data_dir: Path | str,
    generated_at: datetime,
) -> dict[str, object]:
    source_directory = Path(data_dir).resolve()
    source_files = {
        name: _fingerprint(source_directory / name)
        for name in ("dishes.csv", "orders.csv", "order_items.csv")
    }
    stock_counts = Counter(
        row.stock_status for row in result.inventory_analysis
    )
    return {
        "schema_version": SCHEMA_VERSION,
        "generated_at": generated_at.isoformat(timespec="seconds"),
        "source_files": source_files,
        "definitions": {
            "effective_order_statuses": [
                "PLACED",
                "PREPARING",
                "COMPLETED",
            ],
            "cancelled_orders_excluded": True,
            "daily_date_basis": "order_time natural date",
            "money_unit": "CNY",
            "percentage_unit": "percent",
            "inventory_note": (
                "低库存定义为 0 < stock < low_stock_threshold，"
                "缺货定义为 stock == 0，两者互不重复。"
                "补货排序是基于当前库存、预警阈值和历史有效销量的风险建议，"
                "不是需求预测。"
            ),
        },
        "summary": {
            "total_order_count": result.input_order_count,
            "valid_order_count": result.valid_order_count,
            "cancelled_order_count": result.cancelled_order_count,
            "cancellation_rate_percent": _decimal(result.cancellation_rate_percent),
            "valid_sales_quantity": result.valid_sales_quantity,
            "valid_sales_total": _decimal(result.valid_sales_total),
            "average_valid_order_amount": _decimal(
                result.average_valid_order_amount
            ),
            "on_sale_dish_count": result.on_sale_dish_count,
            "low_stock_dish_count": result.low_stock_dish_count,
            "out_of_stock_dish_count": result.out_of_stock_dish_count,
        },
        "sales": {
            "daily": [
                {
                    "date": row.sales_date.isoformat(),
                    "total_order_count": row.total_order_count,
                    "valid_order_count": row.valid_order_count,
                    "cancelled_order_count": row.cancelled_order_count,
                    "cancellation_rate_percent": _decimal(
                        row.cancellation_rate_percent
                    ),
                    "valid_sales_quantity": row.valid_sales_quantity,
                    "valid_sales_amount": _decimal(row.valid_sales_amount),
                    "average_valid_order_amount": _decimal(
                        row.average_valid_order_amount
                    ),
                }
                for row in result.daily_order_analysis
            ],
            "dishes": [
                {
                    "dish_id": row.dish_id,
                    "merchant_id": row.merchant_id,
                    "dish_name": row.dish_name,
                    "sale_status": row.sale_status,
                    "stock": row.stock,
                    "valid_sales_quantity": row.quantity,
                    "valid_sales_amount": _decimal(row.sales_amount),
                    "quantity_share_percent": _decimal(
                        row.quantity_share_percent
                    ),
                    "revenue_share_percent": _decimal(
                        row.revenue_share_percent
                    ),
                }
                for row in result.dish_performance
            ],
            "hourly_order_distribution": [
                {
                    "hour": row.hour,
                    "hour_label": f"{row.hour:02d}:00",
                    "total_order_count": row.total_order_count,
                    "valid_order_count": row.valid_order_count,
                    "cancelled_order_count": row.cancelled_order_count,
                }
                for row in result.hourly_order_distribution
            ],
        },
        "orders": {
            "status_distribution": [
                {
                    "order_status": row.order_status,
                    "status_name": _STATUS_LABELS[row.order_status],
                    "order_count": row.order_count,
                    "share_percent": _decimal(row.share_percent),
                }
                for row in result.order_status_analysis
            ],
            "single_item_order_count": result.single_item_order_count,
            "multi_item_order_count": result.multi_item_order_count,
            "item_kind_distribution": [
                {
                    "item_kind_count": int(row.bucket),
                    "order_count": row.order_count,
                    "share_percent": _decimal(row.share_percent),
                }
                for row in result.item_kind_distribution
            ],
            "item_quantity_distribution": [
                {
                    "item_quantity": int(row.bucket),
                    "order_count": row.order_count,
                    "share_percent": _decimal(row.share_percent),
                }
                for row in result.item_quantity_distribution
            ],
            "merchant_status_distribution": [
                {
                    "merchant_id": row.merchant_id,
                    "order_status": row.order_status,
                    "status_name": _STATUS_LABELS[row.order_status],
                    "order_count": row.order_count,
                }
                for row in result.merchant_order_status
            ],
        },
        "merchants": [
            {
                "merchant_id": row.merchant_id,
                "total_order_count": row.total_order_count,
                "valid_order_count": row.valid_order_count,
                "cancelled_order_count": row.cancelled_order_count,
                "cancellation_rate_percent": _decimal(
                    row.cancellation_rate_percent
                ),
                "valid_sales_quantity": row.valid_sales_quantity,
                "valid_sales_amount": _decimal(row.valid_sales_amount),
                "average_valid_order_amount": _decimal(
                    row.average_valid_order_amount
                ),
                "dish_count": row.dish_count,
                "on_sale_dish_count": row.on_sale_dish_count,
                "low_stock_dish_count": row.low_stock_dish_count,
                "out_of_stock_dish_count": row.out_of_stock_dish_count,
            }
            for row in result.merchant_performance
        ],
        "inventory": {
            "low_stock_threshold": result.low_stock_threshold,
            "restock_target": result.restock_target,
            "stock_status_distribution": [
                {
                    "stock_status": status,
                    "status_name": _STOCK_STATUS_LABELS[status],
                    "dish_count": stock_counts[status],
                }
                for status in ("NORMAL", "LOW_STOCK", "OUT_OF_STOCK")
            ],
            "risk_rule": (
                "低库存为 0 < stock < low_stock_threshold，缺货为 stock == 0。"
                "缺货优先；其次为有有效销量的低库存菜品；再其次为无有效销量的"
                "低库存菜品。同层按有效销量与当前库存比值从高到低排序，再按"
                "库存从低到高排序。该规则是风险建议，不是需求预测。"
            ),
            "items": [
                {
                    "dish_id": row.dish_id,
                    "merchant_id": row.merchant_id,
                    "dish_name": row.dish_name,
                    "sale_status": row.sale_status,
                    "stock": row.stock,
                    "stock_status": row.stock_status,
                    "stock_status_name": _STOCK_STATUS_LABELS[
                        row.stock_status
                    ],
                    "valid_sales_quantity": row.valid_sales_quantity,
                    "valid_sales_amount": _decimal(row.valid_sales_amount),
                    "sales_to_stock_ratio": (
                        _decimal(row.sales_to_stock_ratio)
                        if row.sales_to_stock_ratio is not None
                        else None
                    ),
                    "recommended_restock_quantity": (
                        row.recommended_restock_quantity
                    ),
                    "risk_level": row.risk_level,
                    "risk_reason": row.risk_reason,
                }
                for row in result.inventory_analysis
            ],
        },
        "insights": [
            {
                "category": row.category,
                "level": row.level,
                "title": row.title,
                "content": row.content,
            }
            for row in result.insights
        ],
        "limitations": list(result.limitations),
    }


def write_dashboard(
    payload: dict[str, object],
    dashboard_dir: Path | str,
) -> tuple[Path, ...]:
    directory = Path(dashboard_dir).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    json_text = json.dumps(payload, ensure_ascii=False, indent=2)
    embedded_json = json_text.replace("</", "<\\/")
    html = _HTML_TEMPLATE.replace("__DASHBOARD_DATA__", embedded_json)
    return (
        _atomic_write_text(directory / "index.html", html),
        _atomic_write_text(directory / "dashboard.css", _DASHBOARD_CSS),
        _atomic_write_text(directory / "dashboard.js", _DASHBOARD_JS),
    )


def _fingerprint(path: Path) -> dict[str, object]:
    content = path.read_bytes()
    stat = path.stat()
    return {
        "file_name": path.name,
        "sha256": hashlib.sha256(content).hexdigest(),
        "size_bytes": stat.st_size,
        "modified_at": datetime.fromtimestamp(stat.st_mtime).astimezone().isoformat(
            timespec="seconds"
        ),
    }


def _decimal(value: object) -> str:
    return f"{value:.2f}"


def _atomic_write_text(path: Path, text: str) -> Path:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_text(text, encoding="utf-8", newline="\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)
    return path


_HTML_TEMPLATE = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light">
  <link rel="icon" href="data:,">
  <title>校园餐厅经营数据看板</title>
  <link rel="stylesheet" href="dashboard.css">
</head>
<body>
  <header class="hero">
    <div>
      <p class="eyebrow">校园餐厅订餐系统</p>
      <h1>经营数据看板</h1>
      <p class="hero-copy">统计基于 Java 正式 CSV。已取消订单不计入有效销量和有效销售额。</p>
    </div>
    <div class="freshness" id="freshness"></div>
  </header>

  <main>
    <section id="limitations" class="limitations" aria-label="数据限制"></section>
    <section class="metrics" id="metrics" aria-label="核心指标"></section>

    <section class="panel panel-wide">
      <div class="panel-heading">
        <div><p class="section-kicker">销售分析</p><h2>每日订单量与有效销售额</h2></div>
        <span class="unit">金额单位：元</span>
      </div>
      <div id="daily-trend" class="chart" role="img" aria-label="每日订单量与有效销售额趋势"></div>
    </section>

    <div class="two-column">
      <section class="panel">
        <div class="panel-heading"><div><p class="section-kicker">菜品排行</p><h2>有效销量与销售额</h2></div></div>
        <div class="tabs" role="tablist" aria-label="菜品排行指标">
          <button id="dish-tab-quantity" class="tab active" type="button" role="tab" aria-selected="true" aria-controls="dish-ranking" tabindex="0" data-dish-mode="quantity">按销量</button>
          <button id="dish-tab-revenue" class="tab" type="button" role="tab" aria-selected="false" aria-controls="dish-ranking" tabindex="-1" data-dish-mode="revenue">按销售额</button>
        </div>
        <div id="dish-ranking" class="bar-list" role="tabpanel" aria-labelledby="dish-tab-quantity"></div>
      </section>

      <section class="panel">
        <div class="panel-heading"><div><p class="section-kicker">订单分析</p><h2>订单状态分布</h2></div></div>
        <div id="status-distribution" class="status-layout"></div>
        <div id="order-composition" class="composition"></div>
      </section>
    </div>

    <section class="panel panel-wide">
      <div class="panel-heading">
        <div><p class="section-kicker">商家分析</p><h2>订单与销售表现</h2></div>
        <span class="unit">商家名称未进入分析数据，仅显示商家编号</span>
      </div>
      <div class="table-wrap"><table id="merchant-table"></table></div>
    </section>

    <section class="panel panel-wide">
      <div class="panel-heading">
        <div><p class="section-kicker">库存预警</p><h2>补货风险建议</h2></div>
        <span class="unit">风险建议，不是需求预测</span>
      </div>
      <p class="method" id="inventory-rule"></p>
      <div class="table-wrap"><table id="inventory-table"></table></div>
    </section>

    <div class="two-column">
      <section class="panel">
        <div class="panel-heading"><div><p class="section-kicker">下单时段</p><h2>小时分布</h2></div></div>
        <p id="hourly-summary" class="method" aria-live="polite"></p>
        <div id="hourly-chart" class="hour-grid" aria-hidden="true"></div>
        <table id="hourly-data-table" class="sr-only">
          <caption>按小时统计的订单数据</caption>
        </table>
      </section>
      <section class="panel">
        <div class="panel-heading"><div><p class="section-kicker">自动分析</p><h2>经营摘要</h2></div></div>
        <div id="insights" class="insights"></div>
      </section>
    </div>
  </main>

  <footer>数据看板与结构化 JSON 由同一次 Python 分析生成。</footer>
  <script id="dashboard-data" type="application/json">__DASHBOARD_DATA__</script>
  <script src="dashboard.js"></script>
</body>
</html>
"""


_DASHBOARD_CSS = """:root {
  --ink: #17202a;
  --muted: #667085;
  --line: #e5e9f0;
  --surface: #ffffff;
  --canvas: #f3f6fa;
  --blue: #2563eb;
  --teal: #0f9f8f;
  --amber: #d97706;
  --red: #dc2626;
  --shadow: 0 12px 32px rgba(15, 23, 42, .07);
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--canvas); color: var(--ink); font-family: "Microsoft YaHei", "Noto Sans CJK SC", system-ui, sans-serif; }
.hero { min-height: 240px; padding: 52px max(5vw, 24px) 82px; color: #fff; background: linear-gradient(125deg, #102a43, #164e63 55%, #0f766e); display: flex; justify-content: space-between; gap: 32px; align-items: flex-start; }
.eyebrow, .section-kicker { margin: 0 0 8px; font-size: 12px; font-weight: 700; letter-spacing: .14em; text-transform: uppercase; }
.hero h1 { margin: 0; font-size: clamp(32px, 5vw, 54px); letter-spacing: -.04em; }
.hero-copy { max-width: 700px; margin: 14px 0 0; color: #d9f3ef; line-height: 1.7; }
.freshness { min-width: 260px; padding: 16px 18px; border: 1px solid rgba(255,255,255,.22); border-radius: 14px; background: rgba(255,255,255,.09); font-size: 13px; line-height: 1.7; }
main { width: min(1440px, calc(100% - 40px)); margin: -54px auto 50px; position: relative; }
.limitations { display: grid; gap: 8px; margin-bottom: 16px; }
.limit-note { padding: 12px 16px; border-left: 4px solid var(--amber); background: #fff8e8; border-radius: 8px; color: #7c4a03; font-size: 14px; }
.metrics { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 14px; margin-bottom: 18px; }
.metric { background: var(--surface); border: 1px solid rgba(255,255,255,.7); border-radius: 16px; padding: 20px; box-shadow: var(--shadow); }
.metric-label { color: var(--muted); font-size: 13px; }
.metric-value { margin-top: 9px; font-size: clamp(24px, 3vw, 34px); font-weight: 750; font-variant-numeric: tabular-nums; }
.metric-note { margin-top: 7px; color: var(--muted); font-size: 12px; }
.panel { background: var(--surface); border: 1px solid var(--line); border-radius: 18px; padding: 24px; box-shadow: var(--shadow); min-width: 0; }
.panel-wide { margin-bottom: 18px; }
.two-column { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin-bottom: 18px; }
.panel-heading { display: flex; justify-content: space-between; gap: 18px; align-items: flex-start; margin-bottom: 20px; }
.section-kicker { color: var(--teal); }
h2 { margin: 0; font-size: 22px; }
.unit { color: var(--muted); font-size: 12px; text-align: right; }
.chart { min-height: 310px; overflow-x: auto; }
.chart svg { width: 100%; min-width: 620px; height: 310px; display: block; }
.tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.tab { border: 1px solid var(--line); background: #fff; color: var(--muted); border-radius: 999px; padding: 8px 14px; cursor: pointer; }
.tab.active { border-color: var(--blue); color: var(--blue); background: #eff6ff; }
.tab:focus-visible { outline: 3px solid rgba(37, 99, 235, .35); outline-offset: 2px; }
.bar-list { display: grid; gap: 14px; }
.bar-head { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; }
.bar-track { height: 10px; background: #edf1f6; border-radius: 999px; overflow: hidden; margin-top: 7px; }
.bar-fill { height: 100%; border-radius: inherit; background: linear-gradient(90deg, var(--blue), #60a5fa); }
.bar-sub { color: var(--muted); font-size: 11px; margin-top: 5px; }
.status-layout { display: grid; grid-template-columns: 160px 1fr; gap: 24px; align-items: center; }
.donut { width: 150px; height: 150px; border-radius: 50%; position: relative; }
.donut::after { content: ""; position: absolute; inset: 28px; background: #fff; border-radius: 50%; }
.legend { display: grid; gap: 10px; }
.legend-row { display: grid; grid-template-columns: 12px 1fr auto; gap: 8px; align-items: center; font-size: 13px; }
.swatch { width: 10px; height: 10px; border-radius: 3px; }
.composition { margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--line); display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.composition-card { background: #f7f9fc; border-radius: 12px; padding: 14px; }
.composition-card strong { display: block; font-size: 24px; margin-top: 5px; }
.table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; min-width: 760px; }
th, td { padding: 13px 12px; border-bottom: 1px solid var(--line); text-align: left; white-space: nowrap; font-size: 13px; }
th { color: var(--muted); font-weight: 650; background: #f8fafc; }
td.num { text-align: right; font-variant-numeric: tabular-nums; }
.badge { display: inline-block; padding: 4px 9px; border-radius: 999px; font-size: 11px; font-weight: 700; }
.badge-normal { color: #047857; background: #d1fae5; }
.badge-low { color: #9a5700; background: #fef3c7; }
.badge-out { color: #b91c1c; background: #fee2e2; }
.method { margin: -8px 0 18px; color: var(--muted); font-size: 13px; line-height: 1.7; }
.hour-grid { display: grid; grid-template-columns: repeat(12, 1fr); gap: 5px; align-items: end; min-height: 210px; }
.hour-cell { display: grid; gap: 7px; justify-items: center; align-self: stretch; grid-template-rows: 1fr auto; }
.hour-bar-wrap { width: 100%; display: flex; align-items: end; justify-content: center; }
.hour-bar { width: 72%; min-height: 2px; border-radius: 5px 5px 2px 2px; background: linear-gradient(180deg, var(--teal), #5eead4); }
.hour-label { color: var(--muted); font-size: 9px; transform: rotate(-55deg); transform-origin: center; margin-bottom: 8px; }
.insights { display: grid; gap: 11px; }
.insight { border-left: 4px solid var(--blue); background: #f8fafc; border-radius: 9px; padding: 13px 15px; }
.insight.warning { border-left-color: var(--amber); background: #fffaf0; }
.insight h3 { margin: 0 0 6px; font-size: 14px; }
.insight p { margin: 0; color: #475467; font-size: 13px; line-height: 1.65; }
.empty { color: var(--muted); padding: 32px 0; text-align: center; }
.sr-only { position: absolute !important; width: 1px !important; min-width: 0 !important; max-width: 1px !important; height: 1px !important; padding: 0 !important; margin: -1px !important; overflow: hidden !important; clip: rect(0, 0, 0, 0) !important; white-space: nowrap !important; border: 0 !important; }
footer { color: var(--muted); text-align: center; padding: 0 20px 36px; font-size: 12px; }
@media (max-width: 1050px) { .metrics { grid-template-columns: repeat(3, 1fr); } .two-column { grid-template-columns: 1fr; } }
@media (max-width: 700px) {
  .hero { min-height: 280px; padding: 34px 20px 78px; display: block; }
  .freshness { min-width: 0; margin-top: 22px; }
  main { width: min(100% - 20px, 1440px); margin-top: -48px; }
  .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }
  .metric { padding: 15px; }
  .panel { padding: 18px 15px; border-radius: 14px; }
  .panel-heading { display: block; }
  .unit { display: block; text-align: left; margin-top: 7px; }
  .status-layout { grid-template-columns: 1fr; justify-items: center; }
  .legend { width: 100%; }
  .hour-grid { grid-template-columns: repeat(8, 1fr); }
}
"""


_DASHBOARD_JS = """(() => {
  'use strict';
  const dataNode = document.getElementById('dashboard-data');
  const data = JSON.parse(dataNode.textContent);
  const money = value => `¥${Number(value).toFixed(2)}`;
  const pct = value => `${Number(value).toFixed(2)}%`;
  const integer = value => Number(value).toLocaleString('zh-CN');
  const text = value => String(value ?? '');
  const escapeHtml = value => text(value).replace(/[&<>\"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}[char]));

  const s = data.summary;
  const metrics = [
    ['总订单数', integer(s.total_order_count), '包含全部订单状态'],
    ['有效订单数', integer(s.valid_order_count), '排除已取消订单'],
    ['取消订单数', integer(s.cancelled_order_count), `取消率 ${pct(s.cancellation_rate_percent)}`],
    ['有效销量', `${integer(s.valid_sales_quantity)} 份`, '有效订单明细数量'],
    ['有效销售额', money(s.valid_sales_total), '不含已取消订单'],
    ['平均有效订单金额', money(s.average_valid_order_amount), '有效销售额 ÷ 有效订单数'],
    ['在售菜品数', integer(s.on_sale_dish_count), 'sale_status = ON_SALE'],
    ['低库存菜品数', integer(s.low_stock_dish_count), `0 < 库存 < ${data.inventory.low_stock_threshold}`],
    ['缺货菜品数', integer(s.out_of_stock_dish_count), '当前库存为 0']
  ];
  document.getElementById('metrics').innerHTML = metrics.map(([label, value, note]) => `
    <article class="metric"><div class="metric-label">${escapeHtml(label)}</div><div class="metric-value">${escapeHtml(value)}</div><div class="metric-note">${escapeHtml(note)}</div></article>
  `).join('');

  const fingerprintRows = Object.values(data.source_files).map(file => `${escapeHtml(file.file_name)}：${escapeHtml(file.sha256.slice(0, 10))}…`).join('<br>');
  document.getElementById('freshness').innerHTML = `<strong>生成时间</strong><br>${escapeHtml(data.generated_at)}<br><strong>数据指纹</strong><br>${fingerprintRows}`;
  document.getElementById('limitations').innerHTML = data.limitations.map(item => `<div class="limit-note">${escapeHtml(item)}</div>`).join('');

  function renderDailyTrend() {
    const rows = data.sales.daily;
    const node = document.getElementById('daily-trend');
    if (!rows.length) { node.innerHTML = '<div class="empty">暂无订单趋势数据</div>'; return; }
    const width = Math.max(720, rows.length * 110);
    const height = 300, left = 58, right = 38, top = 24, bottom = 52;
    const plotW = width - left - right, plotH = height - top - bottom;
    const maxRevenue = Math.max(...rows.map(r => Number(r.valid_sales_amount)), 1);
    const maxOrders = Math.max(...rows.map(r => Number(r.valid_order_count)), 1);
    const x = i => left + (i + .5) * plotW / rows.length;
    const yRevenue = v => top + plotH - Number(v) / maxRevenue * plotH;
    const barWidth = Math.min(36, plotW / Math.max(rows.length, 1) * .38);
    const grid = [0, .25, .5, .75, 1].map(f => `<line x1="${left}" y1="${top + plotH * f}" x2="${width-right}" y2="${top + plotH * f}" stroke="#e5e9f0"/><text x="${left-9}" y="${top + plotH * f + 4}" text-anchor="end" fill="#667085" font-size="10">${(maxRevenue*(1-f)).toFixed(0)}</text>`).join('');
    const bars = rows.map((r,i) => { const h = Number(r.valid_order_count)/maxOrders*plotH*.7; return `<rect x="${x(i)-barWidth/2}" y="${top+plotH-h}" width="${barWidth}" height="${h}" rx="4" fill="#99f6e4"/><text x="${x(i)}" y="${top+plotH-h-6}" text-anchor="middle" fill="#0f766e" font-size="10">${r.valid_order_count}单</text>`; }).join('');
    const points = rows.map((r,i) => `${x(i)},${yRevenue(r.valid_sales_amount)}`).join(' ');
    const dots = rows.map((r,i) => `<circle cx="${x(i)}" cy="${yRevenue(r.valid_sales_amount)}" r="5" fill="#2563eb"/><text x="${x(i)}" y="${yRevenue(r.valid_sales_amount)-10}" text-anchor="middle" fill="#1d4ed8" font-size="10">¥${Number(r.valid_sales_amount).toFixed(2)}</text><text x="${x(i)}" y="${height-20}" text-anchor="middle" fill="#667085" font-size="10">${escapeHtml(r.date.slice(5))}</text>`).join('');
    node.innerHTML = `<svg viewBox="0 0 ${width} ${height}" aria-hidden="true">${grid}${bars}<polyline points="${points}" fill="none" stroke="#2563eb" stroke-width="3" stroke-linejoin="round"/>${dots}<text x="14" y="18" fill="#667085" font-size="10">有效销售额</text><text x="${width-right}" y="18" text-anchor="end" fill="#0f766e" font-size="10">有效订单量</text></svg>`;
  }

  function renderDishRanking(mode) {
    const rows = [...data.sales.dishes].sort((a,b) => mode === 'quantity' ? b.valid_sales_quantity-a.valid_sales_quantity || Number(b.valid_sales_amount)-Number(a.valid_sales_amount) : Number(b.valid_sales_amount)-Number(a.valid_sales_amount) || b.valid_sales_quantity-a.valid_sales_quantity);
    const key = mode === 'quantity' ? 'valid_sales_quantity' : 'valid_sales_amount';
    const maxValue = Math.max(...rows.map(r => Number(r[key])), 1);
    document.getElementById('dish-ranking').innerHTML = rows.length ? rows.map(row => {
      const value = mode === 'quantity' ? `${integer(row.valid_sales_quantity)} 份` : money(row.valid_sales_amount);
      const share = mode === 'quantity' ? row.quantity_share_percent : row.revenue_share_percent;
      return `<div><div class="bar-head"><span>${escapeHtml(row.dish_name)}</span><strong>${escapeHtml(value)}</strong></div><div class="bar-track"><div class="bar-fill" style="width:${Number(row[key])/maxValue*100}%"></div></div><div class="bar-sub">占比 ${pct(share)} · ${escapeHtml(row.dish_id)}</div></div>`;
    }).join('') : '<div class="empty">暂无有效菜品销售数据</div>';
  }

  const dishTabs = [...document.querySelectorAll('[data-dish-mode]')];
  function activateDishTab(button, moveFocus = false) {
    dishTabs.forEach(item => {
      const selected = item === button;
      item.classList.toggle('active', selected);
      item.setAttribute('aria-selected', String(selected));
      item.tabIndex = selected ? 0 : -1;
    });
    document.getElementById('dish-ranking').setAttribute('aria-labelledby', button.id);
    renderDishRanking(button.dataset.dishMode);
    if (moveFocus) button.focus();
  }
  dishTabs.forEach((button, index) => {
    button.addEventListener('click', () => activateDishTab(button));
    button.addEventListener('keydown', event => {
      let targetIndex = null;
      if (event.key === 'ArrowRight') targetIndex = (index + 1) % dishTabs.length;
      if (event.key === 'ArrowLeft') targetIndex = (index - 1 + dishTabs.length) % dishTabs.length;
      if (event.key === 'Home') targetIndex = 0;
      if (event.key === 'End') targetIndex = dishTabs.length - 1;
      if (targetIndex !== null) {
        event.preventDefault();
        activateDishTab(dishTabs[targetIndex], true);
      }
    });
  });

  function renderStatuses() {
    const rows = data.orders.status_distribution;
    const colors = ['#2563eb','#f59e0b','#10b981','#94a3b8'];
    let cursor = 0;
    const stops = rows.map((row,index) => { const start = cursor; cursor += Number(row.share_percent); return `${colors[index]} ${start}% ${cursor}%`; }).join(',');
    const donut = `<div class="donut" style="background:conic-gradient(${stops || '#e5e9f0 0 100%'})"></div>`;
    const legend = `<div class="legend">${rows.map((row,index) => `<div class="legend-row"><span class="swatch" style="background:${colors[index]}"></span><span>${escapeHtml(row.status_name)}</span><strong>${row.order_count}单 · ${pct(row.share_percent)}</strong></div>`).join('')}</div>`;
    document.getElementById('status-distribution').innerHTML = donut + legend;
    document.getElementById('order-composition').innerHTML = `<div class="composition-card"><span class="metric-label">单菜品订单</span><strong>${integer(data.orders.single_item_order_count)}</strong></div><div class="composition-card"><span class="metric-label">多菜品订单</span><strong>${integer(data.orders.multi_item_order_count)}</strong></div>`;
  }

  function renderMerchants() {
    const head = '<thead><tr><th>商家编号</th><th>有效订单</th><th>取消率</th><th>有效销量</th><th>有效销售额</th><th>平均有效订单金额</th><th>低库存数/缺货数</th></tr></thead>';
    const body = data.merchants.length ? data.merchants.map(row => `<tr><td>${escapeHtml(row.merchant_id)}</td><td class="num">${row.valid_order_count}</td><td class="num">${pct(row.cancellation_rate_percent)}</td><td class="num">${row.valid_sales_quantity}</td><td class="num">${money(row.valid_sales_amount)}</td><td class="num">${money(row.average_valid_order_amount)}</td><td class="num">${row.low_stock_dish_count}/${row.out_of_stock_dish_count}</td></tr>`).join('') : '<tr><td colspan="7" class="empty">暂无商家数据</td></tr>';
    document.getElementById('merchant-table').innerHTML = head + `<tbody>${body}</tbody>`;
  }

  function renderInventory() {
    document.getElementById('inventory-rule').textContent = data.inventory.risk_rule;
    const badge = status => status === 'NORMAL' ? 'badge-normal' : status === 'LOW_STOCK' ? 'badge-low' : 'badge-out';
    const head = '<thead><tr><th>风险顺序</th><th>菜品</th><th>库存状态</th><th>当前库存</th><th>有效销量</th><th>销量/库存</th><th>建议补货</th><th>说明</th></tr></thead>';
    const body = data.inventory.items.length ? data.inventory.items.map((row,index) => `<tr><td>${index+1}</td><td>${escapeHtml(row.dish_name)}<br><span class="metric-note">${escapeHtml(row.dish_id)}</span></td><td><span class="badge ${badge(row.stock_status)}">${escapeHtml(row.stock_status_name)}</span></td><td class="num">${row.stock}</td><td class="num">${row.valid_sales_quantity}</td><td class="num">${row.sales_to_stock_ratio === null ? '不适用' : Number(row.sales_to_stock_ratio).toFixed(2)}</td><td class="num">${row.recommended_restock_quantity}</td><td>${escapeHtml(row.risk_reason)}</td></tr>`).join('') : '<tr><td colspan="8" class="empty">暂无库存数据</td></tr>';
    document.getElementById('inventory-table').innerHTML = head + `<tbody>${body}</tbody>`;
  }

  function renderHours() {
    const rows = data.sales.hourly_order_distribution;
    const max = Math.max(...rows.map(r => r.total_order_count), 1);
    document.getElementById('hourly-chart').innerHTML = rows.map(row => `<div class="hour-cell" title="${escapeHtml(row.hour_label)}：${row.total_order_count} 单"><div class="hour-bar-wrap"><div class="hour-bar" style="height:${Math.max(row.total_order_count/max*160,2)}px;opacity:${row.total_order_count ? 1 : .18}"></div></div><div class="hour-label">${escapeHtml(row.hour_label)}</div></div>`).join('');
    const total = rows.reduce((sum, row) => sum + row.total_order_count, 0);
    const peakRows = total ? rows.filter(row => row.total_order_count === max) : [];
    document.getElementById('hourly-summary').textContent = total
      ? `当前样本共 ${total} 单，最高时段为 ${peakRows.map(row => row.hour_label).join('、')}（各 ${max} 单）。样本较小，不代表稳定规律。`
      : '当前没有订单时段数据。';
    const head = '<thead><tr><th>小时</th><th>总订单数</th><th>有效订单数</th><th>取消订单数</th></tr></thead>';
    const body = rows.map(row => `<tr><td>${escapeHtml(row.hour_label)}</td><td>${row.total_order_count}</td><td>${row.valid_order_count}</td><td>${row.cancelled_order_count}</td></tr>`).join('');
    document.getElementById('hourly-data-table').innerHTML = '<caption>按小时统计的订单数据</caption>' + head + `<tbody>${body}</tbody>`;
  }

  function renderInsights() {
    document.getElementById('insights').innerHTML = data.insights.map(row => `<article class="insight ${row.level === 'warning' ? 'warning' : ''}"><h3>${escapeHtml(row.title)}</h3><p>${escapeHtml(row.content)}</p></article>`).join('');
  }

  renderDailyTrend();
  renderDishRanking('quantity');
  renderStatuses();
  renderMerchants();
  renderInventory();
  renderHours();
  renderInsights();
})();
"""
