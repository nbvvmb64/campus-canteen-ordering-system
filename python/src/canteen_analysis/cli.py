from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .analysis import analyze_dataset
from .errors import DataValidationError
from .loader import load_dataset
from .outputs import write_outputs

PROJECT_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_CONFIG = PROJECT_ROOT / "python" / "analysis_config.json"


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    try:
        data_dir = _inside_project(Path(args.data_dir), "数据目录")
        output_dir = _inside_project(Path(args.output_dir), "输出目录")
        config_path = _inside_project(Path(args.config), "配置文件")
        config = _load_config(config_path)

        threshold = (
            args.low_stock_threshold
            if args.low_stock_threshold is not None
            else _config_int(config, "low_stock_threshold", minimum=0)
        )
        restock_target = (
            args.restock_target
            if args.restock_target is not None
            else _config_int(config, "restock_target", minimum=1)
        )
        top_n = (
            args.top_n
            if args.top_n is not None
            else _config_int(config, "top_n", minimum=1)
        )

        dataset = load_dataset(data_dir)
        result = analyze_dataset(
            dataset,
            low_stock_threshold=threshold,
            restock_target=restock_target,
        )
        generated = write_outputs(
            result,
            dataset,
            output_dir,
            data_dir=data_dir,
            top_n=top_n,
        )
    except (DataValidationError, ValueError, OSError, json.JSONDecodeError) as exc:
        print(f"分析失败：{exc}", file=sys.stderr)
        return 2
    except RuntimeError as exc:
        print(f"分析失败：{exc}", file=sys.stderr)
        return 3

    print("CSV 校验通过。")
    print(f"有效订单数量：{result.valid_order_count}")
    print(f"有效销量：{result.valid_sales_quantity}")
    print(f"有效销售总额：{result.valid_sales_total:.2f} 元")
    print(f"平均有效订单金额：{result.average_valid_order_amount:.2f} 元")
    print(f"已排除取消订单：{result.cancelled_order_count}")
    print(f"低库存预警数量：{len(result.low_stock_alerts)}")
    print(f"模拟短信事件数量：{len(result.notifications)}")
    print(f"已生成 {len(generated)} 个分析文件：{output_dir}")
    return 0


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="读取 Java CSV，生成校园餐厅订单分析、图表、预警和模拟短信。"
    )
    parser.add_argument(
        "--data-dir",
        default=str(PROJECT_ROOT / "data"),
        help="Java 正式 CSV 所在目录",
    )
    parser.add_argument(
        "--output-dir",
        default=str(PROJECT_ROOT / "output"),
        help="Python 分析输出目录",
    )
    parser.add_argument(
        "--config",
        default=str(DEFAULT_CONFIG),
        help="Python 分析配置 JSON",
    )
    parser.add_argument("--low-stock-threshold", type=int)
    parser.add_argument("--restock-target", type=int)
    parser.add_argument("--top-n", type=int)
    return parser


def _load_config(path: Path) -> dict[str, object]:
    if not path.is_file():
        raise ValueError(f"配置文件不存在: {path}")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("配置文件顶层必须是 JSON 对象")
    return payload


def _config_int(
    config: dict[str, object],
    key: str,
    *,
    minimum: int,
) -> int:
    value = config.get(key)
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ValueError(f"配置项 {key} 必须是不小于 {minimum} 的整数")
    return value


def _inside_project(path: Path, label: str) -> Path:
    resolved = path.resolve()
    try:
        resolved.relative_to(PROJECT_ROOT)
    except ValueError as exc:
        raise ValueError(f"{label}必须位于项目目录内: {PROJECT_ROOT}") from exc
    return resolved
