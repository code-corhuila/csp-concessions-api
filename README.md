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

Requirements: Java 21 and Maven 3.9.

```bash
mvn -B verify                                  # compile and run every test
mvn -B -pl concessions-core test               # only the domain tests (no Spring, no database)
mvn -B -pl concessions-app -am package -DskipTests
java -jar concessions-app/target/concessions-app-0.1.0.jar   # starts on PORT, 8085 by default
```

Every variable the service reads is listed in `.env.example`; never commit a real `.env`.

## API

The routes are served under `/api/v1/concessions`, the base `concessions-service.yaml` opens with
(`server.servlet.context-path`).

| Route | Answer |
|---|---|
| `GET /health` | Liveness, independent of PostgreSQL and RabbitMQ |

## What is missing

Token validation (RS256), the error envelope, the persistence adapter with readiness, and every route of the
products, combos, inventory and orders.

## Related repositories

| Repository | Relation |
|---|---|
| `csp-concessions-db` | Owns the `concessions` schema, its roles and its migrations |
| `csp-infra-postgres` | Defines the PostgreSQL instance and creates the login user `concessions_app` |
| `csp-worker` | Publishes the concessions outbox and runs the purge |
| `csp-docs` | Governance, contracts, data model and ADRs |

Branching, promotion and Pull Request rules: `00-governance/git-conventions.md` in `csp-docs` (ADR-021 for `qa-promote/`).
