# API overview

All endpoints are under `/api/v1`. Obtain a CSRF token from `GET /auth/csrf`; send it in `X-CSRF-TOKEN` for state-changing requests.

| Role | Endpoint | Purpose |
|---|---|---|
| Public | `POST /auth/register` | Register student or merchant where enabled |
| Public | `POST /auth/login` | Create a server-side session |
| Signed in | `POST /auth/logout`, `GET /auth/me` | Session lifecycle |
| Student | `GET /dishes` | Browse dishes currently for sale |
| Student | `POST /orders/preview` | Recalculate order lines and amount |
| Student | `POST /orders` | Create order; requires UUID v4 `Idempotency-Key` |
| Student | `GET /orders`, `POST /orders/{id}/cancel` | View and cancel owned orders |
| Merchant | `GET/POST /merchant/dishes` | List and create owned dishes |
| Merchant | `PUT/DELETE /merchant/dishes/{id}` | Update or take an owned dish off sale |
| Merchant | `PUT /merchant/dishes/{id}/sale-status` | Change sale status |
| Merchant | `GET /merchant/orders` | View orders for the current merchant |
| Merchant | `PUT /merchant/orders/{id}/status` | Advance a valid order state |
| Merchant | `GET /merchants/me/dashboard` | Merchant-scoped live dashboard |
| Owner | `GET /orders/{id}/items` | View lines for an owned order |

The browser client is the most complete request example. API errors are returned as JSON and use HTTP status codes for validation, authentication, authorization, not-found, and idempotency conflict cases.
