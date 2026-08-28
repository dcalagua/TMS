# TMS by EBIM — sellable capabilities

What the product does today, stated so that a demo can be promised against it.

- Derived from the working tree on **2026-08-21**, at migration **V35**.
- Every row was checked against source in this run. Nothing is carried forward from a previous
  report without being re-derived, and nothing is listed as present because a document says so.

## How to read the status column

| Status | Meaning | Safe to demo? |
|---|---|---|
| **IMPLEMENTED** | The whole vertical exists — screen, API client, controller, service, repository, schema, permission check — and is covered by tests that run without a database. | Yes |
| **PARTIAL** | A real, usable slice exists and a specific piece of it does not. The gap is named on the row. | Yes, if you say what the gap is |
| **ENVIRONMENT BLOCKED** | The code exists and its database half has never been executed on this machine. See §5. | Yes on a database that has run the migrations once |
| **FUTURE** | Not built. Listed so it is never implied. | No |

> **One rule for the whole document.** *Implemented* means the code path exists and is unit-tested,
> not that it has been exercised against PostgreSQL in this environment. Twelve of the thirty-five
> migrations have never been run anywhere (§5). Read every row through that qualifier.

---

## 1. Master data

| # | Capability | Status | Where it lives |
|---|---|---|---|
| 1 | **Location** as the single canonical place — one record for a store, a DC, a plant, a customer | IMPLEMENTED | `masterdata`, V14 + V23, `/masters/locations` |
| 2 | **Operational roles** `ORIGIN` / `DESTINATION` on that one record, instead of two parallel masters | IMPLEMENTED | `LocationRoleAssignment`, V23 |
| 3 | Origins and Destinations screens as **filtered views** of Locations | IMPLEMENTED | `OriginsPage` / `DestinationsPage` delegate to `LocationsPage` |
| 4 | **Zones** | IMPLEMENTED | `ZoneController`, `/masters/zones` |
| 5 | **Frequencies**: weekly service rules, cut-off times, per-date exceptions **with a cut-off override** | IMPLEMENTED | `FrequencyCalendar`, V15 + V24, `/masters/frequencies` |
| 6 | Location ↔ frequency assignment and **eligibility for a given date** | IMPLEMENTED | `LocationEligibilityEvaluator`, `GET /masterdata/locations/{id}/eligibility` |
| 7 | **Routes** with ordered stops, reordering and a **per-stop service-time override** | IMPLEMENTED | `Route`, `RouteStop`, V8 + V24, `/masters/routes` |
| 8 | **Carriers** | IMPLEMENTED | `fleet`, V9, `/fleet/carriers` |
| 9 | **Vehicle types** — weight / volume / pallets, plus dimensions, body type, temperature range and axles | IMPLEMENTED | V9, `/fleet/vehicle-types` |
| 10 | **Vehicles**, with per-vehicle capacity overrides and an availability status | IMPLEMENTED | `EffectiveCapacityResolver`, V9 + V16, `/fleet/vehicles` |
| 11 | **Drivers**, with licence expiry and a licence rule that blocks an assignment | IMPLEMENTED | `Driver`, `DriverLicenseStatus`, V26, `/fleet/drivers` |
| 12 | **Bulk import** for locations, carriers, vehicle types, vehicles and orders — XLSX/CSV template, dry-run preview, all-or-nothing apply, idempotent by code | IMPLEMENTED | `shared/imports`, V21, `ImportDrawer` on each screen |

Codes are the idempotency key on every import: re-uploading a file that contains a code TMS already
has is **skipped, not duplicated**.

## 2. Orders

| # | Capability | Status | Notes |
|---|---|---|---|
| 13 | **Transport orders** with lines, declared header totals, priority and a requested service window | IMPLEMENTED | V10 + V17 |
| 14 | **Effective totals** — the lines win when present, the sender's declaration is used when they are not, and the browser can never send the effective figures | IMPLEMENTED | `OrderTotals` |
| 15 | **Order lifecycle** `NOT_READY → READY_FOR_PLANNING → PLANNED`, plus `CANCELLED` | PARTIAL | There is no `DELIVERED` state. A delivered order stays `PLANNED`; what was handed over is recorded on the trip side (row 27). **This is the product's largest known modelling gap** |
| 16 | Bulk order import, one row per order **line**, grouped by the sender's own reference | IMPLEMENTED | `OrderImportColumn` |

## 3. Planning

| # | Capability | Status | Notes |
|---|---|---|---|
| 17 | **Planning runs** per origin and operating date | IMPLEMENTED | V11 + V19 |
| 18 | **Manual planning board** — eligible orders, trips, drag an order onto a trip, move it between trips, reorder stops | IMPLEMENTED | `/planning/{runId}` |
| 19 | **Capacity** in weight, volume and pallets, live while drafting and **frozen onto the trip at confirmation** | IMPLEMENTED | `PlanningCapacityService`, `CAPACITY_MODEL.md` |
| 20 | **Double-booking protection** — one vehicle cannot hold two active trips on one planning date | IMPLEMENTED | partial unique index `uq_trip_vehicle_active_planning_date`, V16 |
| 21 | **Automatic planning** — proposes editable draft trips, never confirms anything by itself | IMPLEMENTED | `HeuristicPlanningEngine` (pure, 15 unit tests) + `AutoPlanningService` |
| 22 | **Every order accounted for** — the proposal asserts `considered = planned + unplanned`, and each unplanned order carries an explicit reason | PARTIAL | The assertion runs on the *proposal*. On `apply`, an order refused by a concurrent edit is counted as unplanned while still being listed on its proposed trip, so the on-screen report can disagree with itself. Database state stays correct. See `KNOWN_LIMITATIONS.md` |
| 23 | **Vehicle and driver assignment** on a trip, with the licence rule enforced at assignment time | IMPLEMENTED | `TripAssignmentService`, `TripDriverDrawer` |

## 4. Execution

| # | Capability | Status | Notes |
|---|---|---|---|
| 24 | **Trip lifecycle** `DRAFT → CONFIRMED → READY_FOR_DISPATCH → IN_TRANSIT → COMPLETED`, plus `CANCELLED` before departure | IMPLEMENTED | `TripStatus`, V25. The transition table is domain data, enforced in the service, in the entity and by CHECK constraints |
| 25 | **Actual times** — ready, departure, completion — operator-supplied, validated, never overwriting the plan | IMPLEMENTED | V25 |
| 26 | **Stop execution** `PENDING → ARRIVED → IN_SERVICE → COMPLETED`, with `SKIPPED` and `FAILED` as two distinct facts, each requiring a typed reason | IMPLEMENTED | V27 |
| 27 | **Delivery result per order at a stop** — `DELIVERED`, `PARTIAL`, `REJECTED`, `FAILED`, `NOT_ATTEMPTED`, with receiver and notes rules per outcome | IMPLEMENTED | `OrderDelivery`, V28 |
| 28 | **Proof-of-delivery evidence** — signature image, photo or PDF, behind a storage port | PARTIAL, **off by default** | `EvidenceStoragePort` + a local-volume implementation. `TMS_EVIDENCE_STORAGE_MODE=DISABLED` until a deployment says where the bytes go (ADR-006). Delivery *results* record either way |
| 29 | **Operational exceptions** on a trip or a stop, from a typed catalogue, `OPEN` / `RESOLVED` | IMPLEMENTED | `TripException`, V27 |
| 30 | **Append-only trip timeline** of every execution fact, with *when it happened* and *when it was typed* recorded separately | IMPLEMENTED | `tms.transport_event`, V27 |
| 31 | **Trip workspace** at `/trips/{id}` — header, lifecycle, stops with map, orders, timeline, problems, cost, tender, tracking | IMPLEMENTED | `TripWorkspacePage` |
| 32 | **Trips board** indexed by operating day rather than by planning run | IMPLEMENTED | `/trips` |

## 5. Commercial

| # | Capability | Status | Notes |
|---|---|---|---|
| 33 | **Rate cards** — one agreement with one carrier, scoped `CARRIER` / `ORIGIN` / `ROUTE`, optionally narrowed by vehicle type, with validity dates and non-overlap enforcement | IMPLEMENTED | V30, `/rates/rate-cards` |
| 34 | **Cost components** — base, per km, per kg, per m³, per pallet, plus a minimum. A `NULL` component and a `0` component are different statements | IMPLEMENTED | `TripCostCalculator` |
| 35 | **Estimated vs actual cost per shipment**, with the variance, priced by the tariff in force on the shipment's **planning date** | IMPLEMENTED | V30 + V33 |
| 36 | **Carrier tendering** — one live offer per shipment, every attempt kept, `DRAFT → SENT → ACCEPTED / REJECTED / EXPIRED / CANCELLED` | IMPLEMENTED | V31 |
| 37 | Carriers answer **from their own system** over the integration API, or a planner records the answer by hand | IMPLEMENTED | `POST /integration/v1/tenders/{shipmentNumber}/response` |
| 38 | At most one accepted carrier per shipment, ever | IMPLEMENTED | `uq_trip_tender_accepted` |
| 39 | Cost per kilometre, cost per order, accessorials, fuel index, break tables, sell-side pricing | FUTURE | Each needs an input TMS does not measure. See `ROADMAP_NEXT.md` |

## 6. Visibility

| # | Capability | Status | Notes |
|---|---|---|---|
| 40 | **Control tower** — today's shipments, late departures, open problems, stops past their window, the unplanned backlog and vehicle workload, on one screen | IMPLEMENTED | `/control-tower`. Owns no data of its own: every figure comes from the module that decides it |
| 41 | **Departure and stop lateness** with the magnitude always reported and **no grace period**, because none has been agreed commercially | IMPLEMENTED | `DepartureDelay`, `StopServiceWindow` |
| 42 | **KPI report** over a date range (30 days by default, 92 maximum): shipments, on-time departure, service, delivery success, exceptions, utilisation, tenders, cost | IMPLEMENTED | `/reporting`, V33 |
| 43 | **A measured number or a dash, never a fabricated zero** — a percentage with an empty denominator is `null` and renders as `—` | IMPLEMENTED | `KpiRate` |
| 44 | **CSV export** of the report | IMPLEMENTED | `GET /reporting/kpis/export` |
| 45 | **In-app alerts** — seven types across late departure, exception opened, tender rejected/expired, licence expiring, trip completed, failed delivery | IMPLEMENTED | V32, the bell in the top bar |
| 46 | **Vehicle positions** — normalised intake, sampling policy, storage and a map trail on the trip | IMPLEMENTED, **no vendor adapter** | V29, ADR-007. TMS ships the contract; a provider is an implementation of it. Positions inform people and move no lifecycle |
| 47 | Scheduled or emailed reports, email/SMS alert delivery, geofencing, ETA | FUTURE | Named in `ROADMAP_NEXT.md` |

## 7. Multi-tenancy, security and administration

| # | Capability | Status | Notes |
|---|---|---|---|
| 48 | **Organization → Company tenancy**, with company scope resolved server-side from the caller's own memberships and never from a client-supplied id | IMPLEMENTED | ADR-003 |
| 49 | **Supabase JWT validated in Spring Security** against the published JWKS; identity and membership resolved server-side | IMPLEMENTED | `SupabaseJwtConfig`, `PrincipalLoader` |
| 50 | **Permissions checked on every endpoint**, with `Capability` used only to decide what the browser shows | IMPLEMENTED | `Permission`, `Capability`, V3–V5 |
| 51 | **RLS as defence in depth** — business tables filtered by the transaction's tenant for the non-owner `tms_app` role, so a query that forgot its predicate stops being a leak | IMPLEMENTED / ENVIRONMENT BLOCKED | V13, ADR-005. The isolation tests need Docker (§5) |
| 52 | **Application objects live in the `tms` schema**, which the Supabase Data API does not expose | IMPLEMENTED / ENVIRONMENT BLOCKED | ADR-004, `SchemaExposureIntegrationTest` |
| 53 | **Company settings and administration from the product** — company profile, adding a company to the organization, granting and revoking access, replacing roles | IMPLEMENTED | V34, `/settings/company`, `/settings/users` |
| 54 | Four roles out of the box: `ORGANIZATION_ADMIN`, `COMPANY_ADMIN`, `PLANNER`, `VIEWER` | IMPLEMENTED | V3 |
| 55 | **Append-only audit trail** of business acts, which the application role cannot update or delete | PARTIAL | `tms.audit_event`, V22. **Write path only — there is no read endpoint and no screen.** Today it is a SQL query |
| 56 | Custom roles per organization, billing/plans/seats, self-service organization signup, email invitations, organization administration screen | FUTURE | `SAAS_ADMINISTRATION_V1.md` §7 |

## 8. Integration

| # | Capability | Status | Notes |
|---|---|---|---|
| 57 | **Inbound M2M API** for locations and orders, single and batch, on its own URL space and security chain | IMPLEMENTED | `/integration/v1`, V18 |
| 58 | **The company is a property of the credential**, never a header a partner can get wrong | IMPLEMENTED | `INBOUND_API_V1.md` §1 |
| 59 | **Idempotency twice over** — by business identity, and optionally by `Idempotency-Key` replaying the original response | IMPLEMENTED | V18 |
| 60 | **Integration inbox** — every delivery recorded, success or failure, in its own transaction, holding no credential material and (by default) no raw payload | IMPLEMENTED | `tms.integration_request` |
| 61 | **Outbound shipment contract** and a **transactional outbox change feed** a partner polls | IMPLEMENTED | V20, `GET /integration/v1/shipments/events?since=` |
| 62 | **Outbound webhooks** — HTTPS only, HMAC-SHA-256 signed, retried with backoff, every attempt logged, endpoint auto-suspended after repeated failure | IMPLEMENTED, **off unless configured** | V35. On exactly when `TMS_WEBHOOK_SECRET_KEY` is set. A partner who polls keeps polling |
| 63 | **Tracking intake** for a telematics feed, gated by its own scope | IMPLEMENTED | V29 |
| 64 | **Carrier tender API** — a carrier reads its offers and answers them | IMPLEMENTED | V31 |
| 65 | **Integration Hub** screen — issue and rotate credentials, see the inbound inbox, register webhook endpoints, read the delivery log, retry a failed delivery | IMPLEMENTED | `/settings/integrations` |
| 66 | **No secret is ever re-shown.** A credential secret is hashed and unrecoverable; a webhook secret is encrypted and only its last four characters are displayed | IMPLEMENTED | `INBOUND_API_V1.md` §2.2, `WEBHOOKS_V1.md` §5 |
| 67 | EWM / ERP product integration, GraphQL, a bulk export endpoint, inbound signature verification | FUTURE | Deliberately not published — `API_CONTRACTS.md` §6 |

## 9. The product surface

| # | Capability | Status | Notes |
|---|---|---|---|
| 68 | **Spanish and English**, Spanish by default, with exact key parity enforced by a test | IMPLEMENTED | 20 i18n namespaces |
| 69 | **MUI-based design system** (ADR-008) with a monochrome premium palette, drawers for CRUD and a shared `confirmDialog` for destructive confirmations | IMPLEMENTED | `shared/ui` |
| 70 | **Empty, loading and error states with retry on every list screen**, and on every card added since | IMPLEMENTED | `DataTable`, plus the job 14 fixes |
| 71 | **Google Maps** for picking a location, drawing stops and drawing a trip, degrading to manual lat/long with no key | IMPLEMENTED | `GOOGLE_MAPS.md` |
| 72 | **Correlation id** on every request, echoed to the caller, on every log line and inside the error document | IMPLEMENTED | `CorrelationIdFilter` |
| 73 | **`/account`** | FUTURE | The one route still resolving to a placeholder |

---

## 10. What is not in the product, stated plainly

Ask about any of these in a demo and the answer is *not yet*:

- Route **optimisation** — no OR-Tools, no solver, no distance matrix. Automatic planning is a
  capacity-and-eligibility heuristic that a person reviews.
- **ETA**, geofencing, automatic arrival detection.
- A **driver mobile app**. Execution is recorded by a dispatcher on the web.
- A **carrier portal**. Carriers integrate over the API or are handled by a planner.
- A **customer portal** and any customer-facing tracking page.
- **Order-level delivery status** (row 15).
- An **audit viewer** (row 55).
- **Money owed to us** — there is carrier cost and no sell-side price.
- Any **EWM or ERP** product integration beyond the published API.

---

## 11. The environment qualifier

This machine cannot run the database tests. Docker Desktop's Linux engine is unreachable because
its backing WSL distribution is missing, and the native PostgreSQL on this host has no PostGIS, so
it cannot stand in — migration V1 creates the extension.

The consequence, counted statically in this run. These are *declared* test methods and therefore a
floor — a `@ParameterizedTest` expands into several cases at runtime. **No suite was executed in
this session**; see `KNOWN_LIMITATIONS.md` §1.

| Suite | Declared methods | Runnable here |
|---|---|---|
| Backend | 1,243 across 110 test classes | 800. **443 across 31 classes are Docker-gated and skip** |
| Frontend unit | 572 across 73 files | all |
| End-to-end (Playwright) | 52 across 11 specs | all |

Migrations **V24–V35 have never been executed by any PostgreSQL server**. Every rule they carry —
the CHECK constraints, the partial unique indexes, the RLS policies, the grants — is enforced a
second time in Java and unit-tested there, which is why the application is coherent without them.
It is not a substitute for running them.

**Before any demo, apply the migrations once to a disposable local database and watch them
succeed.** That is the single highest-value thing anyone can do to this repository, and it takes
one working Docker installation. `DEMO_SCRIPT.md` §1 has the commands.

---

## 12. Where the detail is

| Question | Document |
|---|---|
| How does the demo run? | [`DEMO_SCRIPT.md`](DEMO_SCRIPT.md) |
| What breaks, and what is honestly missing? | [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md) |
| What is built on, and why? | [`ARCHITECTURE_OVERVIEW.md`](ARCHITECTURE_OVERVIEW.md) |
| What is next? | [`ROADMAP_NEXT.md`](ROADMAP_NEXT.md) |
| Show me the API | [`API_EXAMPLES.md`](API_EXAMPLES.md), then `../integrations/` |
| Why is a rule the way it is? | `../domain/` — one contract per module |
