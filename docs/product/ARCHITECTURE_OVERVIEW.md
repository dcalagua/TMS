# TMS by EBIM — architecture overview

For the technical person in the room: the CTO who will ask where the data lives, the integration
lead who will ask what they have to build, and the security reviewer who will ask what happens when
a query forgets its `WHERE company_id`.

This is a summary. The authoritative documents are under [`../architecture/`](../architecture/), and
each claim below names the one that carries it.

---

## 1. The shape, in one diagram

```
                        Browser (React + TypeScript + MUI)
                                        │
                     Supabase Auth ─────┤ sign in only: a JWT, nothing else
                                        │
                                        ▼  HTTPS, Bearer JWT + X-Company-Id
                        ┌───────────────────────────────────┐
                        │      Spring Boot (Java 21)        │
                        │  every business rule lives here   │
                        └───────────────────────────────────┘
                            │                        ▲
                            ▼                        │  Bearer <clientId>.<secret>
                   PostgreSQL / Supabase      Partner systems (ERP, WMS, carrier,
                   schema `tms`, RLS on       telematics) on /integration/v1
```

Two rules produce everything else:

1. **Business data never goes browser → database.** The browser talks to Supabase for exactly one
   thing — signing in — and to Spring Boot for everything else (ADR-001).
2. **Application tables live in the `tms` schema.** The Supabase Data API exposes only `public` and
   `graphql_public`, so the HTTP surface over these tables does not exist to be secured
   (ADR-004).

## 2. Who owns what

| Concern | Owner | Why |
|---|---|---|
| Schema, migrations | **Flyway**, in the backend | One migration history. Supabase migrations are not used for application DDL (ADR-002) |
| PostgreSQL, PostGIS, Auth, RLS enforcement | **Supabase** | Platform capabilities we would otherwise operate ourselves |
| Authorization, tenancy, masters, orders, planning, capacity, concurrency, integrations, audit | **Java** | Business rules |
| Presentation, and only presentation | **React** | Hiding a button is courtesy; the server re-checks every call |

Full matrix: [`../architecture/OWNERSHIP_MATRIX.md`](../architecture/OWNERSHIP_MATRIX.md).

## 3. Multi-tenancy — the answer to "can customer A see customer B?"

Five layers, and the point is that any one of them failing is not a breach.

```
1. JWT            verified against Supabase's published JWKS. No signing secret exists in TMS
                  to leak; there is no "auth disabled" mode reachable by omitting configuration
2. Identity       the caller is resolved server-side, strictly by auth_user_id. No fallback by
                  email, no auto-provisioning at first login
3. Company scope  X-Company-Id is a *request* to act in a company, validated against the caller's
                  own active memberships. It is never trusted as an assertion
4. Service        every service takes a resolved CompanyScope object. There is no method that
                  takes a company id from a payload
5. Repository     every query carries its company predicate, and every child row carries a
                  composite foreign key (id, company_id) so a cross-tenant parent is not
                  representable in the database
6. RLS            business tables are filtered by the transaction's tenant for the non-owner
                  `tms_app` role. A query that forgot layer 5 returns nothing rather than
                  somebody else's rows
```

Layer 6 is the one worth dwelling on with a security reviewer: it is defence in depth, deliberately
not the mechanism. Making RLS *the* authorization would put the business rules in the database and
out of reach of the tests. See ADR-003, ADR-005 and
[`../security/RLS_STRATEGY.md`](../security/RLS_STRATEGY.md).

**Two separate credential vocabularies.** A partner credential's company scope carries an *empty*
permission set, so no `@PreAuthorize` on the application API can ever be satisfied by one. A partner
key cannot mint another partner key — structurally, not by a rule somebody remembered to write.

## 4. Authorization

- **Permissions** (`masterdata.location:manage`, `planning.trip:execute`, …) are what endpoints
  check. They come from a catalogue seeded by migration and are granted to roles.
- **Capabilities** (`FLEET_VIEW`, `IAM_MANAGE`, …) are *derived* from permissions, are never stored,
  and are only ever used to decide what the browser renders. The class comment says so and a test
  pins the mapping.

Four roles ship: `ORGANIZATION_ADMIN`, `COMPANY_ADMIN`, `PLANNER`, `VIEWER`. A customer that needs a
dispatcher who can run trips but not re-plan them creates a role — that split is exactly what the
`(role, permission)` model is for, and `planning.trip:execute` already exists as its own permission.

Detail: [`../security/AUTHORIZATION_MODEL.md`](../security/AUTHORIZATION_MODEL.md).

## 5. Modules, and the line between them

```
masterdata   locations, zones, frequencies, routes
fleet        carriers, vehicle types, vehicles, drivers
orders       transport orders, lines, totals, import
planning     runs, trips, stops, execution, deliveries, tendering, control tower, KPIs
rates        rate cards, trip costing
tracking     vehicle positions
notification in-app alerts
integration  inbound intake, outbound feed, webhooks, credentials
iam          identity, tenancy, company and user administration
shared       ports, security, imports, audit, storage
```

**No business module imports another.** Every cross-module call goes through a port in
`shared/reference` — `OrderPlanningPort`, `VehicleLookupPort`, `RouteTemplateLookupPort`,
`ServiceCalendarPort`, `ShipmentPublicationPort`, `TrackingIntakePort`, `EvidenceStoragePort` and so
on. An ArchUnit-style test (`ModuleBoundaryTest`) fails the build if one does.

That is what makes "simple now, correctly separable later" more than a slogan: the seams a future
service split would cut along are already the only way the modules speak.

## 6. Domain rules live in the domain, not in services

Three examples that a technical buyer will recognise:

- **`TripStatus`** carries the transition table as data. `TripExecutionService` asks it and turns a
  refusal into a `409` naming both states; `Trip` asserts it again; PostgreSQL CHECK constraints
  enforce what each state *guarantees*. The frontend holds **no copy** — it renders the buttons the
  server said were allowed.
- **`OrderTotals`** is the single implementation of "lines win, a declaration fills what the lines
  are silent about, and a contradiction is an error". Every write path resolves totals through it.
- **`KpiRate`** is the single place that decides a percentage with an empty denominator is `null`.

The pattern is deliberate and consistent: one rule, one place, enforced two or three times outward.

## 7. Concurrency

Two planners on the same board is the normal case, not the edge case.

- Every trip mutation takes the trip's row lock first (`findByIdAndCompanyIdForUpdate`).
- Every write checks the caller's `version` against the persisted row and answers `409` on a stale
  one.
- Execution transitions are **idempotent, and idempotency is checked before the version** — a
  dispatcher whose request timed out and who pressed the button again is holding a stale version by
  definition, and answering "someone else changed this" to a retry of an operation that already
  succeeded is a worse lie than accepting it.
- Two structural invariants are partial unique indexes rather than service checks, because a row
  lock cannot cover two application instances: one active trip per vehicle per operating day, and at
  most one accepted tender per shipment ever.

## 8. Integration

| Direction | Mechanism | Notes |
|---|---|---|
| In | `POST /integration/v1/{locations,orders}` and `/batch` | Idempotent by business identity **and** optionally by `Idempotency-Key`, which replays the original response |
| In | `POST /integration/v1/tracking/positions` | Own scope. Positions inform people and move no lifecycle |
| In/out | `GET`/`POST /integration/v1/tenders/…` | A carrier reads its offers and answers them |
| Out | `GET /integration/v1/shipments`, `/shipments/events?since=` | A transactional outbox, polled |
| Out | Signed webhooks | The **same** outbox, pushed. Same event ids, so both can run during a cutover with no gap and no duplicate |

Two properties worth stating to an integration lead:

1. **The company is a property of the credential**, not a header. There is nothing for a partner to
   get wrong, and `X-Company-Id` that disagrees is refused rather than ignored.
2. **The webhook envelope carries no business detail.** It says what happened, to which shipment,
   and when; the receiver then reads what TMS believes *now*. A retry three hours later cannot
   deliver a three-hour-old snapshot, and customer names and addresses never go to a URL an
   administrator typed.

Register of every published interface, with what counts as a breaking change to each:
[`../integrations/API_CONTRACTS.md`](../integrations/API_CONTRACTS.md).

## 9. What is deliberately absent

This matters as much as what is present, because each of these is an operational cost that has not
been taken on:

| Not used | Instead | Why |
|---|---|---|
| Kafka, microservices, event sourcing | One Spring Boot application, a transactional outbox, an append-only event log that is never replayed to rebuild state | The scale target — 10,000+ orders/day, 100–300 vehicles — does not need a distributed system, and the module ports mean splitting later is a deployment change |
| Supabase Realtime, WebSockets | Ordinary polling | A feed that updates once a minute does not justify a socket |
| Supabase Storage | An `EvidenceStoragePort` with a local-volume implementation | The port is the decision (ADR-006); the adapter is a day's work behind it once the platform choice is made |
| A telematics vendor SDK | A normalised position contract behind two ports | ADR-007. Onboarding a provider implements an interface rather than redefining the model |
| OR-Tools / a route solver | A capacity-and-eligibility heuristic a person reviews | Deferred by decision, and by the honest observation that a solver without a distance matrix is theatre |
| Background schedulers | Business transactions that were going to happen anyway | Exactly one scheduled task exists in the whole application — the webhook dispatcher (V35) — and it is annotated in one place so it stays visible |

## 10. Deployment shape

- **Backend:** one Spring Boot 3 / Java 21 process, virtual threads on, graceful shutdown,
  Actuator health/info/metrics only — nothing that lists beans, configuration or environment.
- **Frontend:** a static Vite build.
- **Database:** PostgreSQL, Supabase-hosted or not. Nothing in the application depends on Supabase
  beyond Auth and the database itself.
- **Migrations:** run by Flyway at startup, `clean` disabled, `baseline-on-migrate` off,
  `validate-on-migrate` on. An existing non-empty schema is migrated deliberately or not at all.
- **Secrets:** no credential in this repository has a production-usable default. The JWT issuer and
  JWKS have **no default at all** — a deployment that omits them fails to start rather than starting
  with verification off.

## 11. Testing, and one honest caveat

| Layer | How it is covered |
|---|---|
| Domain rules | Pure unit tests, no Spring context, no database |
| Services | Unit tests over stubs — every refusal path, not only the happy one |
| API | Integration tests including cross-company cases in every module |
| Database | Testcontainers: Flyway apply, RLS isolation, schema exposure, every CHECK and unique index, a full vertical smoke |
| Frontend | 572 component and hook tests |
| End to end | 52 Playwright specs |

**The caveat.** The database layer — 443 declared cases across 31 test classes — cannot run on the
current build machine, because Docker Desktop's Linux engine is unreachable there. Migrations V24
through V35 have therefore never been executed by any PostgreSQL server. Every rule they carry is
enforced a second time in Java and unit-tested there, which is why the application is coherent
without them, but it is not the same as having run them.

That is stated here rather than buried because it is the first question a competent reviewer will
ask, and because the fix is one working Docker installation rather than a redesign. See
[`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md) §1.

## 12. Where to read further

| Topic | Document |
|---|---|
| The architecture of record | [`../architecture/TMS_ARCHITECTURE_V1.md`](../architecture/TMS_ARCHITECTURE_V1.md) |
| Every ADR | [`../architecture/`](../architecture/) — 001 layering, 002 Flyway, 003 tenancy, 004 schema exposure, 005 tenant RLS, 006 evidence storage, 007 tracking ports |
| The schema | [`../database/DATA_MODEL.md`](../database/DATA_MODEL.md) |
| Security posture | [`../security/SECURITY_BASELINE.md`](../security/SECURITY_BASELINE.md), [`../security/RLS_STRATEGY.md`](../security/RLS_STRATEGY.md) |
| One module's rules | [`../domain/`](../domain/) — one contract per module |
| Scale ceilings, measured | [`../performance/PERFORMANCE_BASELINE.md`](../performance/PERFORMANCE_BASELINE.md) |
