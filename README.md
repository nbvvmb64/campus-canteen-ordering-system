# Campus Canteen Ordering System

A portfolio-ready campus ordering system that connects a Spring Boot transaction service, a MySQL data model, a browser UI, and a Python analytics pipeline.

The repository contains two browser experiences with different purposes:

- **Real application** — `java/web/src/main/resources/static/` calls the Java `/api/v1` backend. Authentication, authorization, pricing, inventory, order state transitions, idempotency, and persistence are enforced by Spring Boot and the database.
- **Frontend showcase** — `frontend-showcase/` is a standalone HTML/CSS/JavaScript interaction demo. It uses synthetic data in browser storage and never writes to Java or MySQL.

No production database, real account, real order, credential, encrypted secret, backup, log, or executable JAR is included.

## Highlights

- Student and merchant registration and session login
- Role and ownership checks on every business operation
- Dish, price, stock, and sale-status management
- Same-merchant carts, server-side amount calculation, stock transactions, cancellation rollback
- UUID v4 `Idempotency-Key` contract for safe order retries
- Merchant-scoped real-time dashboard
- MySQL schema with seven business tables and H2 for isolated development/tests
- Versioned CSV snapshot export for the Python pipeline
- Python validation, sales analysis, alerts, notifications, charts, and local dashboard
- CSRF protection, `HttpOnly` sessions, PBKDF2 password hashing, prepared SQL statements

## Architecture

```text
Browser UI
   │ HTTPS/HTTP + JSON
   ▼
Spring Boot API ── session / CSRF / role checks
   │
   ├── transactional business service
   ├── idempotent order creation
   └── JDBC repository
             │
             ▼
       MySQL 8 (main data model)
             │
             └── versioned CSV snapshot
                         │
                         ▼
                  Python analytics
                         │
                         └── tables / charts / alerts / dashboard
```

See [Architecture](docs/ARCHITECTURE.md), [security notes](SECURITY.md), and the [API overview](docs/API.md).

## Repository layout

```text
java/src/main/java/          Shared domain models and validation
java/web/                    Spring Boot API, real browser UI, schema, tests
python/                      CSV validation and analytics pipeline
frontend-showcase/           Standalone interaction demo; browser-only state
examples/sample-data/        Fully synthetic CSV data
scripts/                     Local H2 demo and analysis helpers
docs/                        Public architecture and API documentation
```

公开样例包含 **5 个商家、12 个学生、40 道菜、180 笔订单和 344 条订单明细**，覆盖 45 天的午晚餐时段、四种订单状态、热销与滞销、低库存、售罄和下架等场景。运行 `python .\scripts\generate-sample-data.py` 可确定性地重新生成这些 CSV。

## Quick review

### 1. Open the standalone frontend showcase

Open `frontend-showcase/index.html` in a browser. It is designed for UI review and stores only synthetic state in the browser.

### 2. Run the real application locally with synthetic H2 data

Requirements: JDK 17+, Maven 3.9+, and PowerShell.

```powershell
.\scripts\bootstrap-h2-demo.ps1
.\scripts\run-h2-demo.ps1
```

Then open <http://127.0.0.1:8080/>.

Synthetic logins:

| Role | Username | Password |
|---|---|---|
| Student | `demo_student` | `DemoStudent!2026` |
| Merchant | `demo_merchant` | `DemoMerchant!2026` |

These credentials exist only in the public sample dataset. The local demo creates ignored files under `data/` and `java/web/var/`.

真实应用会把样例迁移到 H2，并通过 Java API 执行登录、下单、库存与商家看板操作。`frontend-showcase/` 是独立的浏览器交互演示，内置两家商户、31 道菜和约一个月的合成订单，只使用浏览器本地存储，不会实时连接 Java、H2 或 MySQL。

### 3. Run tests

```powershell
mvn -f .\java\web\pom.xml test
$env:PYTHONDONTWRITEBYTECODE = "1"
python -m unittest discover -s .\python\tests -p "test_*.py" -v
node .\java\web\tests\app-submit.test.cjs
node .\frontend-showcase\dashboard.test.cjs
```

The Node.js test is optional if Node is not installed.

### 4. Run analytics on synthetic data

```powershell
.\scripts\run-sample-analysis.ps1
```

Results are written to ignored `output/`. The checked-in source dataset is under `examples/sample-data/`.

## MySQL mode

The application contains a production-oriented MySQL 8 schema in `java/web/src/main/resources/schema-mysql.sql` and a separate synthetic-demo schema under `java/web/demo-deploy/db/`. Runtime credentials are accepted only through environment variables; none are committed.

The source project also enforces database-location and cutover checks for its managed local MySQL instance. Those operational data directories, credentials, backups, and machine-bound encrypted files are intentionally excluded from this public repository. Reviewers can inspect the MySQL schema, connection policy, transaction code, and integration tests without receiving private runtime state.

## Data note

All checked-in records are fictional. Names, IDs, dates, prices, orders, salts, and password hashes in `examples/sample-data/` were generated only for this repository.

## Status

This repository is a sanitized review candidate. It is prepared for GitHub publication but has not been uploaded by the preparation workflow.
