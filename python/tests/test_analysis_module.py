from __future__ import annotations

import csv
import json
import re
import shutil
import sys
import unittest
import uuid
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

PYTHON_DIR = Path(__file__).resolve().parents[1]
PROJECT_ROOT = PYTHON_DIR.parent
SRC_DIR = PYTHON_DIR / "src"
sys.path.insert(0, str(SRC_DIR))

from canteen_analysis.analysis import analyze_dataset
from canteen_analysis.errors import DataValidationError
from canteen_analysis.loader import (
    DISH_HEADER,
    ORDER_HEADER,
    ORDER_ITEM_HEADER,
    load_dataset,
)
from canteen_analysis.outputs import write_outputs


class AnalysisModuleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = (
            Path(__file__).resolve().parent
            / ".analysis-test-work"
            / uuid.uuid4().hex
        )
        self.data_dir = self.root / "data"
        self.output_dir = self.root / "output"
        self.data_dir.mkdir(parents=True)

    def tearDown(self) -> None:
        shutil.rmtree(self.root, ignore_errors=False)
        work_root = self.root.parent
        if work_root.is_dir() and not any(work_root.iterdir()):
            work_root.rmdir()

    def test_valid_data_excludes_cancelled_and_aggregates(self) -> None:
        self._write_valid_dataset()
        dataset = load_dataset(self.data_dir)
        result = analyze_dataset(
            dataset,
            low_stock_threshold=5,
            restock_target=20,
        )

        self.assertEqual(2, result.valid_order_count)
        self.assertEqual(1, result.cancelled_order_count)
        self.assertEqual(Decimal("33.33"), result.cancellation_rate_percent)
        self.assertEqual(3, result.valid_sales_quantity)
        self.assertEqual(Decimal("30.00"), result.valid_sales_total)
        self.assertEqual(Decimal("15.00"), result.average_valid_order_amount)
        self.assertEqual(2, result.on_sale_dish_count)
        self.assertEqual(1, result.low_stock_dish_count)
        self.assertEqual(0, result.out_of_stock_dish_count)
        self.assertEqual("DSH000001", result.dish_sales_by_quantity[0].dish_id)
        self.assertEqual(3, result.dish_sales_by_quantity[0].quantity)
        self.assertEqual(
            Decimal("30.00"),
            result.dish_sales_by_revenue[0].sales_amount,
        )
        self.assertEqual(1, len(result.low_stock_alerts))
        self.assertEqual(2, result.single_item_order_count)
        self.assertEqual(0, result.multi_item_order_count)
        self.assertEqual(24, len(result.hourly_order_distribution))
        self.assertEqual("LOW_STOCK_WITH_SALES", result.inventory_analysis[0].risk_level)
        self.assertTrue(result.insights)
        self.assertTrue(result.limitations)
        statuses = {row.event_status for row in result.notifications}
        self.assertTrue({"PLACED", "CANCELLED", "COMPLETED"} <= statuses)

    def test_header_only_files_are_valid_empty_data(self) -> None:
        self._write_table("dishes.csv", DISH_HEADER, [])
        self._write_table("orders.csv", ORDER_HEADER, [])
        self._write_table("order_items.csv", ORDER_ITEM_HEADER, [])

        dataset = load_dataset(self.data_dir)
        result = analyze_dataset(
            dataset,
            low_stock_threshold=10,
            restock_target=20,
        )
        self.assertEqual(0, result.valid_order_count)
        self.assertEqual(Decimal("0.00"), result.valid_sales_total)
        self.assertEqual(Decimal("0.00"), result.average_valid_order_amount)
        self.assertEqual(Decimal("0.00"), result.cancellation_rate_percent)
        self.assertEqual((), result.dish_sales_by_quantity)
        self.assertEqual((), result.daily_sales)

    def test_missing_file_is_reported(self) -> None:
        self._write_table("dishes.csv", DISH_HEADER, [])
        self._write_table("orders.csv", ORDER_HEADER, [])
        with self.assertRaisesRegex(DataValidationError, "缺少必需文件"):
            load_dataset(self.data_dir)

    def test_missing_header_field_is_rejected(self) -> None:
        self._write_table("dishes.csv", DISH_HEADER[:-1], [])
        self._write_table("orders.csv", ORDER_HEADER, [])
        self._write_table("order_items.csv", ORDER_ITEM_HEADER, [])
        with self.assertRaisesRegex(DataValidationError, "表头或字段顺序"):
            load_dataset(self.data_dir)

    def test_bom_and_invalid_utf8_are_rejected(self) -> None:
        for bad_bytes, message in (
            (b"\xef\xbb\xbf" + ",".join(DISH_HEADER).encode() + b"\r\n", "BOM"),
            (b"\xff\xfe\x00", "UTF-8"),
        ):
            with self.subTest(message=message):
                shutil.rmtree(self.data_dir)
                self.data_dir.mkdir()
                (self.data_dir / "dishes.csv").write_bytes(bad_bytes)
                self._write_table("orders.csv", ORDER_HEADER, [])
                self._write_table("order_items.csv", ORDER_ITEM_HEADER, [])
                with self.assertRaisesRegex(DataValidationError, message):
                    load_dataset(self.data_dir)

    def test_wrong_type_and_enum_are_rejected(self) -> None:
        self._write_valid_dataset()
        self._replace_text("dishes.csv", ",3,ON_SALE,", ",three,ON_SALE,")
        with self.assertRaisesRegex(DataValidationError, "stock"):
            load_dataset(self.data_dir)

        self._write_valid_dataset()
        self._replace_text("orders.csv", ",PLACED,", ",UNKNOWN,")
        with self.assertRaisesRegex(DataValidationError, "order_status"):
            load_dataset(self.data_dir)

    def test_subtotal_total_and_reference_errors_are_rejected(self) -> None:
        cases = (
            (
                "order_items.csv",
                ",2,10.00,20.00",
                ",2,10.00,19.00",
                "小计不一致",
            ),
            (
                "orders.csv",
                ",PLACED,20.00,",
                ",PLACED,21.00,",
                "总金额不一致",
            ),
            (
                "order_items.csv",
                ",DSH000001,米饭,2,",
                ",DSH999999,米饭,2,",
                "不存在的 dish_id",
            ),
        )
        for filename, old, new, message in cases:
            with self.subTest(message=message):
                self._write_valid_dataset()
                self._replace_text(filename, old, new)
                with self.assertRaisesRegex(DataValidationError, message):
                    load_dataset(self.data_dir)

    def test_duplicate_primary_key_and_blank_line_are_rejected(self) -> None:
        self._write_valid_dataset()
        orders_path = self.data_dir / "orders.csv"
        text = orders_path.read_bytes().decode("utf-8")
        first_data_row = text.split("\r\n")[1]
        orders_path.write_bytes((text + first_data_row + "\r\n").encode("utf-8"))
        with self.assertRaisesRegex(DataValidationError, "order_id 重复"):
            load_dataset(self.data_dir)

        self._write_valid_dataset()
        items_path = self.data_dir / "order_items.csv"
        items_text = items_path.read_bytes().decode("utf-8")
        items_path.write_bytes((items_text + "\r\n").encode("utf-8"))
        with self.assertRaisesRegex(DataValidationError, "为空行"):
            load_dataset(self.data_dir)

    def test_outputs_include_tables_alerts_notifications_and_charts(self) -> None:
        self._write_valid_dataset()
        dataset = load_dataset(self.data_dir)
        result = analyze_dataset(
            dataset,
            low_stock_threshold=5,
            restock_target=20,
        )
        generated = write_outputs(
            result,
            dataset,
            self.output_dir,
            data_dir=self.data_dir,
            top_n=10,
        )

        self.assertGreaterEqual(len(generated), 30)
        expected_charts = {
            "dish_sales_quantity.png",
            "dish_sales_revenue.png",
            "daily_sales_trend.png",
            "daily_cancellation_trend.png",
            "order_status_distribution.png",
            "order_composition.png",
            "merchant_performance.png",
            "inventory_status.png",
            "inventory_restock_risk.png",
        }
        self.assertEqual(
            expected_charts,
            {path.name for path in (self.output_dir / "charts").glob("*.png")},
        )
        for path in generated:
            self.assertTrue(path.is_file(), path)
            self.assertGreater(path.stat().st_size, 0, path)
        summary_bytes = (self.output_dir / "analysis" / "summary.csv").read_bytes()
        self.assertFalse(summary_bytes.startswith(b"\xef\xbb\xbf"))
        self.assertIn(b"\r\n", summary_bytes)
        notification_text = (
            self.output_dir
            / "notifications"
            / "order_notifications.txt"
        ).read_text(encoding="utf-8")
        self.assertIn("已下单", notification_text)
        self.assertIn("已取消", notification_text)
        self.assertIn("已完成", notification_text)

    def test_dashboard_contract_csv_html_and_sensitive_data_consistency(self) -> None:
        self._write_valid_dataset()
        dataset = load_dataset(self.data_dir)
        result = analyze_dataset(
            dataset,
            low_stock_threshold=5,
            restock_target=20,
        )
        write_outputs(
            result,
            dataset,
            self.output_dir,
            data_dir=self.data_dir,
            top_n=10,
        )

        payload = json.loads(
            (self.output_dir / "analysis" / "dashboard_data.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual("1.0.0", payload["schema_version"])
        self.assertIn("+08:00", payload["generated_at"])
        self.assertEqual(
            {"dishes.csv", "orders.csv", "order_items.csv"},
            set(payload["source_files"]),
        )
        for source in payload["source_files"].values():
            self.assertRegex(source["sha256"], r"^[0-9a-f]{64}$")

        with (self.output_dir / "analysis" / "core_metrics.csv").open(
            encoding="utf-8",
            newline="",
        ) as handle:
            metrics = {row["metric"]: row["value"] for row in csv.DictReader(handle)}
        summary = payload["summary"]
        for metric in (
            "total_order_count",
            "valid_order_count",
            "cancelled_order_count",
            "cancellation_rate_percent",
            "valid_sales_quantity",
            "valid_sales_total",
            "average_valid_order_amount",
            "on_sale_dish_count",
            "low_stock_dish_count",
            "out_of_stock_dish_count",
        ):
            self.assertEqual(str(summary[metric]), metrics[metric], metric)

        dashboard_dir = self.output_dir / "dashboard"
        html = (dashboard_dir / "index.html").read_text(encoding="utf-8")
        self.assertTrue((dashboard_dir / "dashboard.css").is_file())
        self.assertTrue((dashboard_dir / "dashboard.js").is_file())
        self.assertIn('href="dashboard.css"', html)
        self.assertIn('src="dashboard.js"', html)
        self.assertNotRegex(html, r"https?://")
        match = re.search(
            r'<script id="dashboard-data" type="application/json">(.*?)</script>',
            html,
            flags=re.DOTALL,
        )
        self.assertIsNotNone(match)
        embedded = json.loads(match.group(1).replace("<\\/", "</"))
        self.assertEqual(payload, embedded)

        javascript = (dashboard_dir / "dashboard.js").read_text(
            encoding="utf-8"
        )
        self.assertIn('role="tablist"', html)
        self.assertIn('role="tab"', html)
        self.assertIn('aria-selected="true"', html)
        self.assertIn('role="tabpanel"', html)
        self.assertIn("ArrowRight", javascript)
        self.assertIn("ArrowLeft", javascript)
        self.assertIn("hourly-summary", html)
        self.assertIn("hourly-data-table", html)
        self.assertIn("按小时统计的订单数据", html)

        combined = json.dumps(payload, ensure_ascii=False) + html
        for forbidden in (
            "password_salt",
            "password_hash",
            "username",
            "users.csv",
            "student_id",
        ):
            self.assertNotIn(forbidden, combined)

    def test_empty_data_generates_complete_dashboard_without_errors(self) -> None:
        self._write_table("dishes.csv", DISH_HEADER, [])
        self._write_table("orders.csv", ORDER_HEADER, [])
        self._write_table("order_items.csv", ORDER_ITEM_HEADER, [])
        dataset = load_dataset(self.data_dir)
        result = analyze_dataset(
            dataset,
            low_stock_threshold=5,
            restock_target=20,
        )
        write_outputs(
            result,
            dataset,
            self.output_dir,
            data_dir=self.data_dir,
            top_n=10,
        )
        payload = json.loads(
            (self.output_dir / "analysis" / "dashboard_data.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(0, payload["summary"]["total_order_count"])
        self.assertEqual("0.00", payload["summary"]["valid_sales_total"])
        self.assertEqual([], payload["sales"]["daily"])
        self.assertEqual([], payload["inventory"]["items"])
        html = (self.output_dir / "dashboard" / "index.html").read_text(
            encoding="utf-8"
        )
        self.assertIn("dashboard-data", html)

    def test_regeneration_refreshes_metrics_and_source_fingerprint(self) -> None:
        self._write_valid_dataset()
        first_dataset = load_dataset(self.data_dir)
        first_result = analyze_dataset(
            first_dataset,
            low_stock_threshold=5,
            restock_target=20,
        )
        write_outputs(
            first_result,
            first_dataset,
            self.output_dir,
            data_dir=self.data_dir,
            top_n=10,
        )
        first_payload = json.loads(
            (self.output_dir / "analysis" / "dashboard_data.json").read_text(
                encoding="utf-8"
            )
        )

        self._replace_text(
            "orders.csv",
            ",PLACED,20.00,",
            ",CANCELLED,20.00,",
        )
        second_dataset = load_dataset(self.data_dir)
        second_result = analyze_dataset(
            second_dataset,
            low_stock_threshold=5,
            restock_target=20,
        )
        write_outputs(
            second_result,
            second_dataset,
            self.output_dir,
            data_dir=self.data_dir,
            top_n=10,
        )
        second_payload = json.loads(
            (self.output_dir / "analysis" / "dashboard_data.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertNotEqual(
            first_payload["source_files"]["orders.csv"]["sha256"],
            second_payload["source_files"]["orders.csv"]["sha256"],
        )
        self.assertNotEqual(
            first_payload["summary"]["valid_order_count"],
            second_payload["summary"]["valid_order_count"],
        )

    def test_zero_stock_is_exclusively_out_of_stock_everywhere(self) -> None:
        self._write_valid_dataset()
        self._replace_text("dishes.csv", ",3,ON_SALE,", ",0,ON_SALE,")
        dataset = load_dataset(self.data_dir)
        result = analyze_dataset(
            dataset,
            low_stock_threshold=5,
            restock_target=20,
        )

        self.assertEqual(0, result.low_stock_dish_count)
        self.assertEqual(1, result.out_of_stock_dish_count)
        self.assertEqual((), result.low_stock_alerts)
        merchant = result.merchant_performance[0]
        self.assertEqual(0, merchant.low_stock_dish_count)
        self.assertEqual(1, merchant.out_of_stock_dish_count)
        inventory_insight = next(
            row for row in result.insights if row.category == "inventory"
        )
        self.assertIn("0 个低库存菜品", inventory_insight.content)
        self.assertIn("另有 1 个缺货菜品", inventory_insight.content)

        write_outputs(
            result,
            dataset,
            self.output_dir,
            data_dir=self.data_dir,
            top_n=10,
        )
        payload = json.loads(
            (self.output_dir / "analysis" / "dashboard_data.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(0, payload["summary"]["low_stock_dish_count"])
        self.assertEqual(1, payload["summary"]["out_of_stock_dish_count"])
        self.assertEqual(0, payload["merchants"][0]["low_stock_dish_count"])
        self.assertEqual(1, payload["merchants"][0]["out_of_stock_dish_count"])
        distribution = {
            row["stock_status"]: row["dish_count"]
            for row in payload["inventory"]["stock_status_distribution"]
        }
        self.assertEqual(
            {"NORMAL": 1, "LOW_STOCK": 0, "OUT_OF_STOCK": 1},
            distribution,
        )
        self.assertEqual(
            0,
            sum(
                row["stock_status"] == "LOW_STOCK"
                for row in payload["inventory"]["items"]
            ),
        )
        self.assertEqual(
            1,
            sum(
                row["stock_status"] == "OUT_OF_STOCK"
                for row in payload["inventory"]["items"]
            ),
        )

        with (self.output_dir / "analysis" / "core_metrics.csv").open(
            encoding="utf-8", newline=""
        ) as handle:
            metrics = {row["metric"]: row["value"] for row in csv.DictReader(handle)}
        self.assertEqual("0", metrics["low_stock_dish_count"])
        self.assertEqual("1", metrics["out_of_stock_dish_count"])
        with (self.output_dir / "analysis" / "merchant_performance.csv").open(
            encoding="utf-8", newline=""
        ) as handle:
            merchant_csv = next(csv.DictReader(handle))
        self.assertEqual("0", merchant_csv["low_stock_dish_count"])
        self.assertEqual("1", merchant_csv["out_of_stock_dish_count"])
        with (self.output_dir / "alerts" / "low_stock_alerts.csv").open(
            encoding="utf-8", newline=""
        ) as handle:
            self.assertEqual([], list(csv.DictReader(handle)))

    def test_batch_publish_failure_preserves_old_output_and_cleans_staging(self) -> None:
        self._write_valid_dataset()
        dataset = load_dataset(self.data_dir)
        result = analyze_dataset(dataset, low_stock_threshold=5, restock_target=20)
        self.output_dir.mkdir()
        marker = self.output_dir / "old-output.txt"
        marker.write_text("保留旧输出", encoding="utf-8")

        with patch(
            "canteen_analysis.outputs._write_charts",
            side_effect=RuntimeError("模拟生成失败"),
        ):
            with self.assertRaisesRegex(RuntimeError, "模拟生成失败"):
                write_outputs(
                    result,
                    dataset,
                    self.output_dir,
                    data_dir=self.data_dir,
                    top_n=10,
                )

        self.assertEqual("保留旧输出", marker.read_text(encoding="utf-8"))
        self._assert_no_publish_workdirs()

    def test_batch_publish_success_replaces_old_output_and_cleans_staging(self) -> None:
        self._write_valid_dataset()
        dataset = load_dataset(self.data_dir)
        result = analyze_dataset(dataset, low_stock_threshold=5, restock_target=20)
        self.output_dir.mkdir()
        marker = self.output_dir / "old-output.txt"
        marker.write_text("旧输出", encoding="utf-8")

        write_outputs(
            result,
            dataset,
            self.output_dir,
            data_dir=self.data_dir,
            top_n=10,
        )

        self.assertFalse(marker.exists())
        self.assertTrue(
            (self.output_dir / "analysis" / "dashboard_data.json").is_file()
        )
        self._assert_no_publish_workdirs()

    def _assert_no_publish_workdirs(self) -> None:
        leftovers = [
            path.name
            for path in self.output_dir.parent.iterdir()
            if path.name.startswith(f".{self.output_dir.name}-staging-")
            or path.name.startswith(f".{self.output_dir.name}-backup-")
        ]
        self.assertEqual([], leftovers)

    def _write_valid_dataset(self) -> None:
        if self.data_dir.exists():
            shutil.rmtree(self.data_dir)
        self.data_dir.mkdir()
        self._write_table(
            "dishes.csv",
            DISH_HEADER,
            [
                (
                    "DSH000001",
                    "USR000001",
                    "米饭",
                    "10.00",
                    "3",
                    "ON_SALE",
                    "2026-07-16 10:00:00",
                    "2026-07-16 10:00:00",
                ),
                (
                    "DSH000002",
                    "USR000001",
                    "饮料",
                    "5.00",
                    "10",
                    "ON_SALE",
                    "2026-07-16 10:00:00",
                    "2026-07-16 10:00:00",
                ),
            ],
        )
        self._write_table(
            "orders.csv",
            ORDER_HEADER,
            [
                (
                    "ORD000001",
                    "USR000002",
                    "USR000001",
                    "2026-07-16 11:00:00",
                    "PLACED",
                    "20.00",
                    "2026-07-16 11:00:00",
                ),
                (
                    "ORD000002",
                    "USR000002",
                    "USR000001",
                    "2026-07-16 12:00:00",
                    "CANCELLED",
                    "5.00",
                    "2026-07-16 12:05:00",
                ),
                (
                    "ORD000003",
                    "USR000002",
                    "USR000001",
                    "2026-07-17 11:00:00",
                    "COMPLETED",
                    "10.00",
                    "2026-07-17 11:30:00",
                ),
            ],
        )
        self._write_table(
            "order_items.csv",
            ORDER_ITEM_HEADER,
            [
                (
                    "ITM000001",
                    "ORD000001",
                    "DSH000001",
                    "米饭",
                    "2",
                    "10.00",
                    "20.00",
                ),
                (
                    "ITM000002",
                    "ORD000002",
                    "DSH000002",
                    "饮料",
                    "1",
                    "5.00",
                    "5.00",
                ),
                (
                    "ITM000003",
                    "ORD000003",
                    "DSH000001",
                    "米饭",
                    "1",
                    "10.00",
                    "10.00",
                ),
            ],
        )

    def _write_table(
        self,
        filename: str,
        header: tuple[str, ...],
        rows: list[tuple[str, ...]],
    ) -> None:
        path = self.data_dir / filename
        with path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, lineterminator="\r\n")
            writer.writerow(header)
            writer.writerows(rows)

    def _replace_text(self, filename: str, old: str, new: str) -> None:
        path = self.data_dir / filename
        text = path.read_bytes().decode("utf-8")
        self.assertIn(old, text)
        path.write_bytes(text.replace(old, new, 1).encode("utf-8"))


if __name__ == "__main__":
    unittest.main()
