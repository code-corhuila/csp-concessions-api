# Changelog

## Unreleased

### Added
- HU-CONCESSIONS-003 (domain and ports): `Product.adjustStock` keeps the balance non-negative and refuses an inactive product, the immutable `StockMovement` (type from the sign of the change), `StockUseCases` and `StockService` with the retry by `Idempotency-Key`, and the output port `StockRepository`. Persistence and the `POST /stock/adjust` route follow.
- HU-CONCESSIONS-002: the `Combo` aggregate and its use cases, `POST /combos`, `GET /combos` and `PATCH /combos/{comboId}`. A combo is saved as a `DRAFT` from existing products with positive quantities and published only while every component is a `PUBLISHED` product (`422` otherwise). Idempotent creation by `Idempotency-Key`.
- The CI pins the schema of `csp-concessions-db` to the merge commit that adds the combo tables (V006 to V009).

## HU-CONCESSIONS-001
- Products (`POST/GET/PATCH /products`), RS256 token validation, the error envelope, readiness and the image.
