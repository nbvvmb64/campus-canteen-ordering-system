# Spring Boot web application

This module contains the real Java backend and integrated browser client.

- Spring Boot 3.5 / Java 17
- Spring Security sessions and CSRF
- JDBC transactions
- H2 for isolated development and tests
- MySQL 8 schema and connection policy for the main data model
- REST API, student UI, merchant UI, and merchant dashboard
- order idempotency and versioned CSV export

From the repository root:

```powershell
mvn -f .\java\web\pom.xml test
```

For a local synthetic-data demonstration, follow the root README. Runtime databases, secrets, exports, logs, JARs, and local tool caches are intentionally excluded.
