# Changelog

## 3.0.0 - 2026-10-21

First release of `csp-concessions-api` in `main` with the service code. It needs `csp-concessions-db` 3.0.0 (V001 to V011) applied first.

### Added
- HU-CONCESSIONS-001: products (`POST/GET/PATCH /products`) with the permission `concessions:product:write`, RS256 token validation, the single error envelope, correlation id, readiness, the image, the compose service and the CI that applies the schema.
- HU-CONCESSIONS-002: the `Combo` aggregate and its use cases, `POST /combos`, `GET /combos` and `PATCH /combos/{comboId}` with the permission `concessions:combo:write`. A combo is saved as a `DRAFT` from existing products with positive quantities and published only while every component is a `PUBLISHED` product (`422` otherwise). Idempotent creation by `Idempotency-Key`.
- HU-CONCESSIONS-003: `POST /stock/adjust` with the permission `concessions:inventory:write`. `Product.adjustStock` keeps the balance non-negative and refuses an inactive product, the immutable `StockMovement` records each receipt or adjustment (type from the sign of the change) and `JdbcStockRepository` moves the balance with one guarded `UPDATE` and stores the movement in the same transaction, so concurrent withdrawals cannot drive it below zero; a retry of the same `Idempotency-Key` applies nothing. Errors: `409 INSUFFICIENT_STOCK`, `422` for an inactive product.
- HU-CONCESSIONS-004 (persistence): `JdbcOrderRepository` stores an order with its lines, hold, `RESERVE` movements and outbox events in one transaction, taking the stock of each product with one guarded `UPDATE` in ascending product id (two orders cannot take the last unit, nor deadlock); confirming and releasing are conditional on the order still being `RESERVED`, so the hold is committed or released once and a release returns exactly the held quantities; the idempotency key is settled by `ON CONFLICT DO NOTHING` (ADR-045).
- The CI pins the schema of `csp-concessions-db` to the merge commit that adds the order, stock hold and outbox tables (V012 to V018).
- HU-CONCESSIONS-004 (use cases and ports): `OrderService` creates the order all or nothing (snapshot of names and prices, combos expanded into product quantities, one live order per reservation, idempotent by user, key and body hash), reads and cancels the orders of the caller and reacts to the confirmation or expiry of a reservation and to the deadline of an order without one, each effect once; `OrderRepository` and the outbox event of the port (ADR-045).
- HU-CONCESSIONS-004 (domain): the immutable `ConcessionOrder` (RESERVED to CONFIRMED or CANCELLED once, total equal to its snapshot lines, 1 to 20 lines of 1 to 20 units, expiry for an order without reservation), its `OrderLine` snapshot and the `StockHold` (HELD to COMMITTED or RELEASED once, products in ascending id order), with their typed errors (ADR-045).

### Known limits
- A combo that is already published keeps a component that is deactivated afterwards (`TD-035` in `csp-docs`).
- Idempotency keys older than 24 hours are not purged yet.
- The CI pins the schema of `csp-concessions-db` to a commit of its `develop`; it moves to the tag `v3.0.0` once that repository publishes it.
