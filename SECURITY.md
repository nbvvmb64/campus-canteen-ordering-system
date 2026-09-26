# Security and privacy

## Public repository boundary

This repository contains source code and synthetic sample data only. Do not commit:

- runtime `data/` or exported business snapshots;
- `java/web/var/`, H2/MySQL files, database backups, logs, or PID files;
- DPAPI blobs, passwords, tokens, private keys, certificates, or `.env` files;
- executable JARs or downloaded build-tool caches;
- account or order data copied from a real installation.

## Implemented controls

- PBKDF2-HMAC-SHA256 password derivation with per-user random salts
- server-side sessions with `HttpOnly` cookies
- CSRF token checks for state-changing requests
- role and resource-ownership authorization in the service layer
- parameterized JDBC operations
- transactional order, stock, cancellation, and status updates
- UUID v4 idempotency keys for order creation retries
- fixed state-transition rules and server-side monetary calculation
- restricted demo ingress and environment-only database credentials

## Deployment note

The browser-only `frontend-showcase/` is not a security boundary and is not connected to the backend. Deploy the real Java service behind HTTPS and provide secrets through the hosting platform's secret store.
