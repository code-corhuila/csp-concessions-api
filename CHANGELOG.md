# Changelog

## Unreleased

### Fixed
- The filter accepts an `exp` up to 30 seconds late, for the clock difference between services (norm 5.3.7; `csp-concessions-api#36`).

### Added
- HU-CONCESSIONS-004 (routes): `POST /orders` (201, 200 on replay), `GET /orders`, `GET /orders/{orderId}` and `POST /orders/{orderId}/cancel` with the permissions of the client role, the internal `POST /internal/orders/by-reservation/{reservationId}/confirm`, `.../release` and `POST /internal/orders/expire` for `csp-worker`, the error mapping of the order rules and the wiring of `OrderService` (`ORDER_HOLD_MINUTES`, default 15) (ADR-045).
- HU-CONCESSIONS-004 (persistence): `JdbcOrderRepository` stores an order with its lines, hold, `RESERVE` movements and outbox events in one transaction, taking the stock of each product with one guarded `UPDATE` in ascending product id (two orders cannot take the last unit, nor deadlock); confirming and releasing are conditional on the order still being `RESERVED`, so the hold is committed or released once and a release returns exactly the held quantities; the idempotency key is settled by `ON CONFLICT DO NOTHING` (ADR-045).
- The CI pins the schema of `csp-concessions-db` to the merge commit that adds the order, stock hold and outbox tables (V012 to V018).
- HU-CONCESSIONS-004 (use cases and ports): `OrderService` creates the order all or nothing (snapshot of names and prices, combos expanded into product quantities, one live order per reservation, idempotent by user, key and body hash), reads and cancels the orders of the caller and reacts to the confirmation or expiry of a reservation and to the deadline of an order without one, each effect once; `OrderRepository` and the outbox event of the port (ADR-045).
- HU-CONCESSIONS-004 (domain): the immutable `ConcessionOrder` (RESERVED to CONFIRMED or CANCELLED once, total equal to its snapshot lines, 1 to 20 lines of 1 to 20 units, expiry for an order without reservation), its `OrderLine` snapshot and the `StockHold` (HELD to COMMITTED or RELEASED once, products in ascending id order), with their typed errors (ADR-045).
- HU-CONCESSIONS-003 (persistence and route): `POST /stock/adjust` and `JdbcStockRepository`, which moves the balance of `product.available_stock` with one guarded `UPDATE` and stores the `stock_movement` in the same transaction, so concurrent withdrawals cannot drive it below zero (tested with parallel requests against PostgreSQL); `409 INSUFFICIENT_STOCK`, `422` for an inactive product. The CI pins the schema of `csp-concessions-db` to the commit that adds `stock_movement` (V010, V011).
- HU-CONCESSIONS-003 (domain and ports): `Product.adjustStock` keeps the balance non-negative and refuses an inactive product, the immutable `StockMovement` (type from the sign of the change), `StockUseCases` and `StockService` with the retry by `Idempotency-Key`, and the output port `StockRepository`. Persistence and the `POST /stock/adjust` route follow.
- HU-CONCESSIONS-002: the `Combo` aggregate and its use cases, `POST /combos`, `GET /combos` and `PATCH /combos/{comboId}`. A combo is saved as a `DRAFT` from existing products with positive quantities and published only while every component is a `PUBLISHED` product (`422` otherwise). Idempotent creation by `Idempotency-Key`.
- The CI pins the schema of `csp-concessions-db` to the merge commit that adds the combo tables (V006 to V009).

## HU-CONCESSIONS-001
- Products (`POST/GET/PATCH /products`), RS256 token validation, the error envelope, readiness and the image.
