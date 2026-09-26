# Architecture

## Components

### Java domain core

`java/src/main/java` contains the shared domain types, validation rules, password hashing, and legacy CSV codec. The Spring Boot module adds the HTTP and database layers without duplicating the central order rules.

### Spring Boot web application

`java/web/src/main/java` provides:

- JSON endpoints under `/api/v1`;
- session authentication and CSRF integration;
- service-layer authorization and business transactions;
- JDBC persistence for users, dishes, orders, order items, idempotency requests, revision metadata, and ID counters;
- merchant-scoped live aggregation;
- migration, snapshot export, and database cutover support.

The real browser client in `java/web/src/main/resources/static` talks to these endpoints.

### Database model

The MySQL schema uses InnoDB, foreign keys, `DECIMAL(12,2)` money columns, explicit state constraints, and a composite primary key for per-student idempotency keys. H2 mirrors the schema for isolated tests and the public local demo.

### Python analytics

The Python package validates CSV encoding, headers, types, keys, references, amounts, and states before analysis. Cancelled orders are excluded from valid sales. Outputs include rankings, daily trends, merchant summaries, inventory risk, simulated notifications, charts, and a local dashboard.

### Standalone showcase

`frontend-showcase/` is a separate static prototype. It demonstrates student, merchant, and analytics interactions with synthetic browser-local state. It does not claim backend persistence, concurrency control, authentication security, or MySQL integration.

## Key invariants

- One order contains dishes from one merchant.
- Prices and totals are calculated on the server.
- Stock never becomes negative.
- Cancelling a `PLACED` order restores stock exactly once.
- `PLACED → PREPARING → COMPLETED` is the merchant flow.
- `PLACED → CANCELLED` is the student cancellation flow.
- A repeated idempotency key with the same canonical payload returns the first order.
- The same key with a different payload returns a conflict.
