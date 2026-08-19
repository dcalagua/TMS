# TMS by EBIM

Transport Management System: master data, fleet, orders and manual trip planning for
multi-company transport operations.

TMS is an independent product. It integrates with **EWM by EBIM** later only through APIs,
events and explicit contracts - never through shared internal tables or cross-product
foreign keys.

## Architecture in one picture

    React + TypeScript (frontend/tms-web)
              |
              |  HTTPS / JSON, Supabase-issued JWT in the Authorization header
              v
    Java 21 + Spring Boot (backend/tms-api)
              |
              |  JDBC, pooled, application database role
              v
    PostgreSQL on Supabase (+ PostGIS, RLS)

Every business operation follows that path. The only direct browser-to-Supabase call
allowed in V1 is **authentication**: sign in, refresh, sign out and obtain the JWT that is
then sent to Spring Boot.

| Layer | Owns |
|---|---|
| React | Presentation, dense operational screens, client-side UX validation, session handling |
| Spring Boot | Business authorization, tenancy enforcement, masters, orders, planning, capacity, concurrency, jobs, integrations, audit use cases, the OpenAPI contract |
| Supabase / PostgreSQL | Managed PostgreSQL, PostGIS, Auth and JWT issuance, RLS as defense in depth, data invariants the database can guarantee |

Full detail: [`docs/architecture/TMS_ARCHITECTURE_V1.md`](docs/architecture/TMS_ARCHITECTURE_V1.md).

## Repository layout

    backend/tms-api      Java 21 + Spring Boot 4 + Flyway (canonical application DDL)
    frontend/tms-web     React + TypeScript + Vite + Bootstrap + SweetAlert2
    supabase             Local Supabase platform config; no duplicate application DDL
    docs                 Architecture, ADRs, database, security, overnight reports
    scripts              Local developer helper scripts

## Requirements

| Tool | Version | Required for |
|---|---|---|
| JDK | 21 | Backend. Maven itself is **not** required - the backend ships `./mvnw` |
| Node.js | 20+ (developed on 22) | Frontend |
| Docker Desktop | any recent | Local Supabase stack and Testcontainers integration tests |
| Supabase CLI | 2.x | Optional: local platform stack |

## Local development

    git clone <this repository>
    cd TMS

    # 1. environment templates (both copies are git-ignored)
    cp backend/tms-api/.env.example  backend/tms-api/.env
    cp frontend/tms-web/.env.example frontend/tms-web/.env.local

    # 2. platform - optional, needs Docker Desktop running
    supabase start                  # PostgreSQL on localhost:54322, Studio on :54323

    # 3. backend - applies Flyway migrations at startup, serves http://localhost:8080
    ./scripts/dev-backend.sh

    # 4. frontend - serves http://localhost:5173
    ./scripts/dev-frontend.sh

Verify the stack is wired: <http://localhost:8080/api/v1/system/info> returns the service
identity, and the dashboard at <http://localhost:5173> shows it under "Backend connection".

Any PostgreSQL 17 instance works instead of the Supabase CLI; point `TMS_DB_URL` at it.

## Checks

    ./scripts/check-all.sh          # everything below, in fail-fast order

    ./scripts/backend-build.sh      # ./mvnw clean verify + packaged jar
    ./scripts/backend-test.sh       # backend tests only
    ./scripts/frontend-build.sh     # typecheck + lint + production build
    ./scripts/frontend-test.sh      # frontend tests only

Integration tests that need PostgreSQL use Testcontainers and therefore require a running
Docker daemon. The scripts warn when Docker is unavailable rather than silently skipping.

## API surface

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /api/v1/system/info` | public | Service identity and liveness |
| `GET /actuator/health` | public | Health probe |
| `GET /actuator/info` | public | Build information |
| `GET /v3/api-docs`, `/swagger-ui.html` | public | OpenAPI contract and explorer |
| everything else | authenticated | Business endpoints, denied by default |

## Working agreements

- **Flyway owns application schema migrations.** Applied migrations are immutable; a later
  change is a new versioned migration. Supabase carries no parallel history (ADR-002).
- **Authorization is server-side.** Hiding a button in React is a UX hint, never a
  security control. RLS is defense in depth, not a substitute for backend checks.
- **Tenancy is resolved from the token**, never from a client-supplied company id (ADR-003).
- **Modules stay separable.** `backend/tms-api` is a modular monolith - `shared`, `iam`,
  `masterdata`, `fleet`, `orders`, `planning`, `audit`, `integration` - and an ArchUnit test
  fails the build if a module reaches into another module's internals.
- **No secrets in the repository.** Only `.env.example` files with placeholders.
- **Deferred by decision:** OR-Tools/route optimization, GPS/telematics, EWM and ERP
  integration, Kafka/microservices/event sourcing, Supabase Realtime and Storage, live map
  tracking. Each needs a concrete requirement and an ADR before it enters the codebase.

## Scale target

10,000+ orders/day, 100-300 vehicles, multiple companies and warehouses, concurrent users -
without premature distributed-system complexity. Simple now, correctly separable,
scalable later.
