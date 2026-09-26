# Python analytics

The package reads `dishes.csv`, `orders.csv`, and `order_items.csv`, validates the cross-file contract, and generates analytical tables, charts, alerts, notifications, and a local dashboard.

Run it against the synthetic sample:

```powershell
.\scripts\run-sample-analysis.ps1
```

Run tests:

```powershell
$env:PYTHONDONTWRITEBYTECODE = "1"
python -m unittest discover -s .\python\tests -p "test_*.py" -v
```
