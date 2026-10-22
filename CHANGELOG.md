# Changelog

## 3.0.0 - 2026-10-21

First release of `csp-concessions-api` in `main` with the service code. It needs `csp-concessions-db` 3.0.0 (V001 to V009) applied first.

### Added
- HU-CONCESSIONS-001: products (`POST/GET/PATCH /products`) with the permission `concessions:product:write`, RS256 token validation, the single error envelope, correlation id, readiness, the image, the compose service and the CI that applies the schema.
- HU-CONCESSIONS-002: the `Combo` aggregate and its use cases, `POST /combos`, `GET /combos` and `PATCH /combos/{comboId}` with the permission `concessions:combo:write`. A combo is saved as a `DRAFT` from existing products with positive quantities and published only while every component is a `PUBLISHED` product (`422` otherwise). Idempotent creation by `Idempotency-Key`.

### Known limits
- A combo that is already published keeps a component that is deactivated afterwards (`TD-035` in `csp-docs`).
- Idempotency keys older than 24 hours are not purged yet.
- The CI pins the schema of `csp-concessions-db` to a commit of its `develop`; it moves to the tag `v3.0.0` once that repository publishes it.
