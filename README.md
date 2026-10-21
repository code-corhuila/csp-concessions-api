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
`JWT_PUBLIC_KEY` (the PEM) or `JWT_PUBLIC_KEY_FILE` (its path). `SERVICE_SUBJECTS` (default `csp-worker`) lists the `sub` of
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

The token carries the permissions in its `permissions` claim; an `ADMIN` has `concessions:product:write` and `concessions:combo:write`. A product created without a
category is stored as `UNCATEGORIZED` (the contract makes it optional, the schema does not).

## What is missing

Stock, orders and the stock history (HU-CONCESSIONS-003 to 005 and 007); the status route also covers the endpoint of HU-CONCESSIONS-006.

## Related repositories

| Repository | Relation |
|---|---|
| `csp-concessions-db` | Owns the `concessions` schema, its roles and its migrations |
| `csp-infra-postgres` | Defines the PostgreSQL instance and creates the login user `concessions_app` |
| `csp-worker` | Publishes the concessions outbox and runs the purge |
| `csp-docs` | Governance, contracts, data model and ADRs |

Branching, promotion and Pull Request rules: `00-governance/git-conventions.md` in `csp-docs` (ADR-021 for `qa-promote/`).
