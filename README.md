# csp-concessions-api

> concessions bounded context: service API

Part of the **Cinesync Platform** distributed system — team `cinesync-platform`, Group 1.
Governance and documentation live in [`csp-docs`](https://github.com/code-corhuila/csp-docs).

## Purpose

`csp-concessions-api` is the service of the **Concessions / Snack Bar** domain: products, combos, inventory and
concession orders. It is the only writer of the `concessions` schema and the only publisher of concessions events,
which it writes to the outbox table in the same transaction as the change. This repository holds the base
structure for now; the stories (HU-CONCESSIONS-001 to 007) arrive one Pull Request at a time.

## Stack

| Item | Decision | Record |
|---|---|---|
| Language and build | Java 21, Spring Boot 3.x, Maven, modules `concessions-core`, `concessions-adapters` and `concessions-app` | [ADR-018](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/decisions/records/ADR-018-concessions-java-postgresql-flyway.md) |
| Domain | Supporting domain with its own schema, orders and stock | [ADR-004](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/decisions/records/ADR-004-concessions-snack-bar-domain.md), [ADR-008](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/decisions/records/ADR-008-concessions-contract-and-inventory-boundary.md) |
| Database | PostgreSQL schema `concessions`, owned and migrated by `csp-concessions-db` with Flyway | [ADR-018](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/decisions/records/ADR-018-concessions-java-postgresql-flyway.md) |
| Contract | OpenAPI of the concessions service | [`concessions-service.yaml`](https://github.com/code-corhuila/csp-docs/blob/main/07-api/contracts/openapi/concessions-service.yaml) |

## Rules of this repository

- **No migration here.** The schema and its migrations live only in `csp-concessions-db`. This service declares no
  migration library and runs no migration at startup (Norma 5.2.1).
- **The domain depends on nothing.** `concessions-core` does not declare Spring or a database driver, so a framework
  annotation in the domain does not compile.
- **Only the approved contract is implemented.** A route that is not in `concessions-service.yaml` is not added.
- **The API never connects to RabbitMQ.** It writes events to the outbox table only.

## Build, test and run

Requirements: Java 21 and Maven 3.9. The persistence tests need PostgreSQL with the migrations of `csp-concessions-db`: set
`TEST_DATABASE_URL` (for example `jdbc:postgresql://localhost:5432/csp?user=...&password=...`) or they are skipped.

```bash
mvn -B verify                                  # compile and run every test
mvn -B -pl concessions-core test               # only the domain tests (no Spring, no database)
mvn -B -pl concessions-app -am package -DskipTests
java -jar concessions-app/target/concessions-app-0.1.0.jar   # starts on PORT, 8085 by default
```

The service **refuses to start without the public key** that validates the tokens (Norma 5.3.7): set
`JWT_PUBLIC_KEY` (the PEM) or `JWT_PUBLIC_KEY_FILE` (its path). `ORDER_HOLD_MINUTES` (default 15) is how long an order without a reservation keeps its stock. `SERVICE_SUBJECTS` (default `csp-worker`) lists the `sub` of
the service tokens allowed on `/internal/` routes; a client token receives `403` there and a service token receives `403` anywhere else.
Every error is the envelope of the contract (`error`, `message`, `details`, `traceId`) and every answer carries `X-Correlation-Id`.
Every variable the service reads is listed in `.env.example`; never commit a real `.env`.

With Docker, from the root of the repository (the image reads the key from a mounted file):

```bash
docker build -f deploy/Dockerfile -t csp-concessions-api .
docker run --rm -p 8085:8085 -e JWT_PUBLIC_KEY_FILE=/run/jwt/public.pem -v /path/to/keys:/run/jwt:ro csp-concessions-api
```

In the platform, `csp-infra-postgres` includes `deploy/compose.yml`, which exposes the port on the `platform` network
without publishing it.

## API

The routes are served under `/api/v1/concessions`, the base `concessions-service.yaml` opens with
(`server.servlet.context-path`).

| Route | Answer |
|---|---|
| `GET /health` | Liveness, independent of PostgreSQL and RabbitMQ |
| `GET /health/ready` | Readiness: `503` while PostgreSQL cannot be reached |
| `POST /products` | Creates a `DRAFT` product (needs `concessions:product:write`): `201`, `200` when the `Idempotency-Key` replays, `400`, `401`, `403`, `409` |
| `GET /products` | Page of products (`page`, `limit` 1 to 100): an administrator sees every status, any other caller only the `PUBLISHED` ones |
| `PATCH /products/{productId}` | Moves the status (needs `concessions:product:write`): `DRAFT` to `PUBLISHED`, `PUBLISHED` to `INACTIVE`, `INACTIVE` to `PUBLISHED`; anything else is `409` |
| `POST /combos` | Creates a `DRAFT` combo from existing products (needs `concessions:combo:write`): `201`, `200` when the `Idempotency-Key` replays, `400`, `401`, `403`, `409`, `422` (a product that does not exist) |
| `GET /combos` | Page of combos, like the products: an administrator sees every status, any other caller only the `PUBLISHED` ones |
| `PATCH /combos/{comboId}` | Moves the status (needs `concessions:combo:write`), same lifecycle as a product; publishing is `422` unless every component is a `PUBLISHED` product |
| `POST /stock/adjust` | Records a receipt (positive `quantityDelta`) or an adjustment (negative) of a product (needs `concessions:inventory:write`): `204`, also for a retry of the same `Idempotency-Key`; `400`, `401`, `403`, `404`, `409` (`INSUFFICIENT_STOCK`, or the key used with another body), `422` (inactive product). The balance never becomes negative |
| `GET /stock/movements` | The stock history, newest first (needs `concessions:inventory:read`): `page`, `limit` 1 to 100, and the filters `productId`, `fromDate` and `toDate` (RFC 3339, both included). Each line has `id`, `productId`, `productName`, `quantityDelta`, `reason` (the stored movement type: `RECEIPT`, `ADJUSTMENT`, `RESERVE`, `RELEASE` or `CONSUME`) and `timestamp`; `400` for a bad bound or filter, `401`, `403`. It only reads: no route changes or deletes a movement |
| `POST /orders` | Creates a `RESERVED` order of products and combos, optionally tied to a Booking `reservationId`, and takes its stock all or nothing (needs `concessions:order:create`): `201`, `200` when the `Idempotency-Key` replays, `400`, `401`, `403`, `409` (not enough stock, key reused with another body, reservation already ordered), `422` (an item is not available) |
| `GET /orders`, `GET /orders/{orderId}` | The caller's own history (page, `limit` 1 to 100) and one of its orders (needs `concessions:order:read:self`); another user's order is `404` |
| `POST /orders/{orderId}/cancel` | Cancels the caller's `RESERVED` order and releases its stock once (needs `concessions:order:cancel:self`): `204`, `409` for a confirmed order |
| `POST /internal/orders/by-reservation/{reservationId}/confirm`, `.../release` | Called by `csp-worker` on `BookingConfirmed` and `ReservationExpired`: `204`, also when the reservation has no order or the order is already in that state, `409` for the opposite final state |
| `POST /internal/orders/expire` | Called by `csp-worker` on a schedule: releases up to `batchSize` (1 to 500, default 100) orders without a reservation whose `ORDER_HOLD_MINUTES` ran out |

The token carries the permissions in its `permissions` claim; an `ADMIN` has `concessions:product:write`, `concessions:combo:write`, `concessions:inventory:write` and `concessions:inventory:read`. A product created without a
category is stored as `UNCATEGORIZED` (the contract makes it optional, the schema does not).

## What is missing

The inbox of the events (HU-CONCESSIONS-005); the status route also covers the endpoint of HU-CONCESSIONS-006. The consumer of `BookingConfirmed` and `ReservationExpired` and the relay of the Concessions outbox are work of `csp-worker` (ADR-045): until they exist, an order tied to a reservation is settled by the client cancelling it.

## Related repositories

| Repository | Relation |
|---|---|
| `csp-concessions-db` | Owns the `concessions` schema, its roles and its migrations |
| `csp-infra-postgres` | Defines the PostgreSQL instance and creates the login user `concessions_app` |
| `csp-worker` | Publishes the concessions outbox and runs the purge |
| `csp-docs` | Governance, contracts, data model and ADRs |

Branching, promotion and Pull Request rules: `00-governance/git-conventions.md` in `csp-docs` (ADR-021 for `qa-promote/`).
