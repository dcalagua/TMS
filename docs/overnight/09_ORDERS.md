# Step 09 - Orders V1

Date: 2026-08-19
Attempt: 1
Result: **PASS**

## 0. State inherited from prior steps

The repository arrived with a clean working tree and Step 08 (`08_FLEET` in the overnight log,
migration V9) complete: five master-data verticals (origins/zones, destinations/frequencies,
routes, carriers/vehicle-types/vehicles) all following the same shape, `orders.order:read/manage`
permissions already seeded and granted (V3) to `PLANNER`/`VIEWER`/`COMPANY_ADMIN`/
`ORGANIZATION_ADMIN`, `Capability.ORDERS_VIEW/ORDERS_MANAGE` already mapped, `router.tsx` already
had an `orders` route pointing at `PlaceholderPage`, `navConfig.ts` already listed the "Orders"
nav group, and `com.ebim.tms.orders.package-info.java` already existed as an empty placeholder.
This attempt read `docs/overnight/08_FLEET.md` section 8 ("Handoff to Step 09") end to end before
writing anything (rule 6) and followed it point by point:

1. Orders references fleet's precedent directly: point 1 said Orders would need to reference
   origin/destination (master-data) from the caller's own company, reusing the composite-FK
   idiom - `uq_origin_id_company`/`uq_destination_id_company` already existed from V8, so no new
   `ALTER TABLE` was needed on either table.
2. Point 4 flagged that `EffectiveCapacityResolver` is ready for Planning, not Orders - correctly
   out of scope here; Orders references no fleet table at all (no vehicle/carrier assignment - see
   section 1).
3. Point 2's `loadByIds` generic batch-resolution pattern was reused in spirit but not in code:
   Orders cannot reuse `RouteService`'s or `VehicleService`'s private copy (`orders` must not
   depend on `masterdata`, see section 1's port design), so the same discipline was re-implemented
   as `OriginLookupPort.findAllInCompany`/`DestinationLookupPort.findAllInCompany`.
4. This is the first step where the handoff's "if Orders/Planning defines what an association
   needs to look like, add the table then" judgment (from `06_DESTINATIONS_FREQUENCIES.md`,
   restated in `08_FLEET.md` point 4) actually fires: Orders does **not** add a vehicle/carrier
   assignment to the order header - that is explicitly Planning's job (step 10), matching the
   brief's "no dispatch/delivery integration exists yet."

## 1. Scope decisions made before writing code

- **A transport order references origin and destination only - no vehicle, carrier or route.**
  The brief's "Prevent assigning inactive/mismatched-company masters" is satisfied for the two
  masters an order actually carries; vehicle/carrier/route assignment is Planning's concern
  (step 10, not built), matching the deferred-dispatch-integration constraint. See
  `docs/domain/ORDER_LIFECYCLE_V1.md`.
- **`orders` resolves origin/destination through a new port in `shared`, not through
  `masterdata` directly.** `ModuleBoundaryTest` forbids any business module from depending on
  another; Orders is the first module that genuinely needs to read another module's owned
  entity (not just a `shared` primitive). `com.ebim.tms.shared.reference.OriginLookupPort`/
  `DestinationLookupPort` (plus a small `MasterReference(id, code, name)` record) carry no
  `masterdata` type; `masterdata.infrastructure.OriginLookupAdapter`/`DestinationLookupAdapter`
  are the only implementations. `OrderService` depends only on the port interfaces. Verified by
  running `ModuleBoundaryTest`/`LayeringTest` before writing any other Orders code, not after.
  See `docs/database/DATA_MODEL.md` section 12.6 and new rule 10.
- **`order_number` is a system-generated, globally unique identifier - the "stable internal
  number" the brief asks for - not a user-supplied `code`.** Unlike every master's `code`,
  nobody chooses or types it: `OrderService.generateOrderNumber` reads
  `tms.transport_order_number_seq` and formats `TO-00000001`. Because nobody can "attempt" a
  specific value, a global (not company-scoped) unique constraint carries none of the
  cross-tenant leak risk migration rule 9 (`DATA_MODEL.md` section 10.2) exists to prevent - a
  documented, narrow exception to that rule. See section 12.1.
- **The external-source/external-reference pair is the idempotency strategy**, mirroring
  `carrier.tax_id_type`/`tax_id_value`'s flexible-pair shape (section 10.1): both optional, but
  a reference with no source is rejected (`ck_transport_order_external_pair_complete`) so the
  partial unique index `(company_id, external_source, external_reference)` can never be defeated
  by two differently-`NULL` sources. `OrderService.create` rejects a duplicate pair with 409
  before ever reaching the database - V1 does not attempt idempotent replay (returning the prior
  resource), a documented, deliberately deferred capability. See section 12.2.
- **Header weight/volume/pallet totals are a transactional snapshot, computed and owned
  exclusively by `TransportOrder.applyLines`, in the same transaction as every line change** -
  exactly the pattern the step brief's "Totals strategy" paragraph pre-approves ("acceptable to
  persist header totals... if the backend is the sole mutation path and tests guarantee
  recomputation"). `OrderApiIntegrationTest.createComputesTotalsAndReadsBack`/
  `updateRecomputesTotals` are the guarantee. See section 6 and `DATA_MODEL.md` section 12.3.
- **The minimal V1 lifecycle is four states**: `NOT_READY` → `READY_FOR_PLANNING` → `PLANNED`
  (reserved for a not-yet-built Planning module - no endpoint in this step reaches it) or
  `CANCELLED` from either of the first two. Editing a `READY_FOR_PLANNING` order unconditionally
  resets it to `NOT_READY`, rather than re-running the completeness check to decide whether it
  stays ready - a deliberate simplification documented with its trade-off in
  `docs/domain/ORDER_LIFECYCLE_V1.md` section 4. Full lifecycle: same document, sections 2-5.
- **`version` (JPA `@Version`, `bigint`) is the first optimistic-locking column in the schema.**
  `OrderService.update` explicitly compares `OrderRequest.version` against the persisted order's
  version *before* applying any change - this is what actually catches a client resubmitting a
  stale form (two separate HTTP round trips), which plain `@Version`-driven locking alone would
  not catch, since a fresh `find()` inside the update transaction always reads the current row.
  JPA's own optimistic-lock exception is kept as a narrower second backstop for two transactions
  racing to flush at the same instant. See `DATA_MODEL.md` section 12.4 and new rule 11, and
  `docs/domain/ORDER_LIFECYCLE_V1.md` section 7.
- **Order lines are deleted and re-created as a whole set on every update, never diffed in
  place.** Unlike `Route.replaceStops` (keyed by `destinationId`) or `Frequency.replaceWeeklyRules`
  (keyed by `dayOfWeek`), an order line has no natural key that survives an edit - two lines can
  legitimately share the same `materialCode` with different quantities. This still needed the
  same `DEFERRABLE INITIALLY DEFERRED` idiom `uq_route_stop_route_sequence` uses (V8), for a
  different reason discovered by actually running the tests, not by reasoning alone: Hibernate
  flushes new-row insertions before orphan-removal deletions, so replacing a 2-line order
  transiently duplicates `(order_id, line_number)` within one flush even though nothing is being
  reordered. See `V10__orders.sql`'s comment on `uq_transport_order_line_order_line_number` and
  section 5 below for the failing-test story.
- **No metadata JSONB column on order line.** The brief's "future-friendly metadata without
  arbitrary JSON becoming the main model" is read as "stay friendly to adding typed columns
  later," the same judgment V6/V7 made for zone geometry and the destination-frequency
  association - not as "add a JSON escape hatch now." No concrete field has a use yet.
- **No `active` boolean on the order header.** `status` (specifically `CANCELLED`) already
  models the "cancel semantics" the brief asks for; a second boolean would either duplicate or
  contradict it. `cancel_reason` is optional free text, constrained to only ever be set together
  with `status = CANCELLED`.

## 2. Database (migration V10)

`backend/tms-api/src/main/resources/db/migration/V10__orders.sql` follows V6-V9's shape
(`DATA_MODEL.md` section 13, "Rules for the next migrations") and documents the decisions above
(12.1-12.5) plus the two new rules it establishes (10, 11):

- **`tms.transport_order`**: `company_id NOT NULL` + FK + composite indexes leading with it
  (`ix_transport_order_company_status`, `ix_transport_order_company_service_date`), globally
  unique `order_number` (12.1), the external-reference idempotency pair (12.2, partial unique
  index), composite-FK tenant guarantees for `origin_id`/`destination_id` (mandatory, reusing
  V8's `uq_origin_id_company`/`uq_destination_id_company`), `priority`/`status` restricted to
  their catalogues, a requested-window pair/order `CHECK`, `cancel_reason` gated to
  `status = CANCELLED`, nonnegative totals, `version bigint NOT NULL DEFAULT 0`, actor columns,
  and `uq_transport_order_id_company` added pre-emptively for a future Planning trip-assignment
  table (the same "add the composite-FK target before the referencing table exists" idiom V8
  used for `uq_origin_id_company` itself).
- **`tms.transport_order_line`**: a pure child of `transport_order` (no own `company_id` - it
  references no other company-scoped table), `line_number` unique per order but
  `DEFERRABLE INITIALLY DEFERRED` (see section 1's delete-and-recreate discovery), quantity
  strictly positive, `uom` normalized but not restricted to a fixed catalogue (12.5,
  same reasoning as `carrier.tax_id_type`), unit weight/volume positive when present, computed
  `line_weight_kg`/`line_volume_m3` snapshot columns, direct (not derived) `pallet_quantity`.
- `CREATE SEQUENCE tms.transport_order_number_seq` backs `order_number` generation
  (`OrderService.generateOrderNumber`, via a plain `nextval()` repository query - no DB
  `DEFAULT`/Hibernate `@Generated` complexity, since Java is already the sole writer of every
  other Orders field).
- Both tables get `ENABLE ROW LEVEL SECURITY` in this same migration, matching every table
  before them; both were added to `SchemaExposureIntegrationTest`'s RLS/`public`-schema
  assertion lists.

## 3. Backend

### 3.1 Cross-module boundary: `shared.reference` ports

`com.ebim.tms.shared.reference` (new): `MasterReference(id, code, name)`, `OriginLookupPort`,
`DestinationLookupPort` - each with a company-scoped `findActiveInCompany` (validation, rejects
inactive/wrong-company) and a batched `findAllInCompany` (display, resolves regardless of active
state so a deactivated origin/destination does not break an already-persisted order, the same
invariant `DATA_MODEL.md` section 9.5 established for routes). `masterdata.infrastructure.
OriginLookupAdapter`/`DestinationLookupAdapter` are the only implementations, each a thin
translation over the module's own `OriginRepository`/`DestinationRepository`. This is the
"explicit API" `ModuleBoundaryTest`'s rule message points to, verified directly: `ModuleBoundaryTest`
and `LayeringTest` were run standalone before any other Orders file was written, and again in
the final full suite.

### 3.2 Domain (`orders.domain`)

`OrderStatus`, `OrderPriority` (plain enums), `OrderLineInput` (a domain-owned record so
`TransportOrder`/`TransportOrderLine` never depend on the application layer's validated request
DTO), `TransportOrder` (the aggregate root - `applyChanges`/`applyLines`/`markReadyForPlanning`/
`markPlanned`/`cancel`, `@Version version`, owns `lines` via `orphanRemoval`), `TransportOrderLine`
(a pure child, package-visible constructor like `RouteStop`). Legality of every state transition
lives in `OrderService`, not the entity - the same split the rest of the codebase already uses
(`OriginService.validateCoordinatePair`, `VehicleTypeService.validateTemperatureRange`): entity
methods are deliberately "dumb," callable only after the service has checked the precondition.

### 3.3 Application (`orders.application`)

- **`OrderRequest`**: shared create/update shape; `lines` may be empty (a header-only order is a
  legitimate `NOT_READY` state - see section 1); `version` is required by `OrderService.update`
  and ignored by `create`.
- **`OrderService`**: `list`/`get`/`create`/`update`/`markReadyForPlanning`/`cancel`. `list`
  batch-resolves origin/destination for the whole page via the ports (never one lookup per row)
  and line counts via one `GROUP BY` query (`TransportOrderLineRepository.countByOrderIds`,
  identical shape to `RouteStopRepository.countByRouteIds`). `requireActiveOrigin`/
  `requireActiveDestination` reject an inactive or cross-company master with 400
  `malformed-request` - this is strictly stronger than `RouteService.requireOriginInScope`,
  which does not check `active`; Orders needed the stronger check because the step brief
  explicitly names it, so the divergence from Route's precedent is deliberate, not an oversight.
  `requireCurrentVersion`, `requireEditable`, `validateTimeWindow`, `validateExternalPair` are
  the Java-side mirrors of the matching database `CHECK`s (defense in depth, same split
  `OriginService.validateCoordinatePair` uses). `saveOrConflict` catches both
  `ObjectOptimisticLockingFailureException` and `DataIntegrityViolationException`, translating
  each to the same `ConflictException` a pre-check would give.
- **`OrderView`**/**`OrderDetailView`**: the same list/detail shape split `RouteView`/
  `RouteDetailView` established, for the same N+1 reason (list carries a line *count*; detail
  carries the resolved line list).

### 3.4 Infrastructure (`orders.infrastructure`) and API (`orders.api`)

`TransportOrderRepository` (company-scoped finders, the external-reference existence checks, the
`nextval()` query), `TransportOrderLineRepository` (the batched line-count query only - lines are
never queried any other way), `TransportOrderSpecifications` (filter composition, same shape as
`RouteSpecifications`). `OrderController` mirrors `RouteController`: `orders.order:read/manage`
`@PreAuthorize`, `CompanyScope` resolved by the framework, no delete endpoint, two lifecycle
endpoints (`POST .../mark-ready`, `POST .../cancel?reason=`) instead of Route's
activate/deactivate pair.

### 3.5 `ApiExceptionHandler`

Added `handleOptimisticLockingFailure` for `ObjectOptimisticLockingFailureException` as a
backstop alongside `OrderService.saveOrConflict`'s own catch - the first module to need it,
documented as the pattern the next versioned module should reuse.

## 4. Frontend

`ordersApi.ts` and two screens follow the `routesApi.ts`/`RoutesPage`/`RouteFormModal` template
(the closest precedent: a header plus an owned array of child rows), adapted for Orders' richer
field set and lifecycle:

- **`OrdersPage`**: dense list with server-side filters (order number, origin, destination,
  service-date range, status, priority) and pagination, a lane column (`origin → destination`),
  priority/status badges, a totals summary column, permission-aware row actions
  (`orders.order:manage`): Edit/View (label depends on whether the order is still editable),
  Mark ready (only while `NOT_READY`), Cancel (hidden once `PLANNED`/`CANCELLED`) - both actions
  confirm via `confirmDialog` (SweetAlert2) before calling the API, matching `RoutesPage`'s
  activate/deactivate pattern.
- **`OrderFormModal`**: one component serves create, edit and read-only view. A cancelled or
  planned order renders every field inside a disabled `<fieldset>` with no Save action and an
  explanatory alert (including the cancel reason, if any) instead of a separate detail screen -
  the brief's "detail" requirement satisfied by reusing the same modal rather than building a
  fourth screen for a state every other field already renders correctly. The line editor is a
  plain add/remove table (no reorder controls - line order carries no meaning, unlike Route's
  stop sequence) with a live, client-computed totals preview (`previewTotals`, mirroring
  `TransportOrderLine.applyInput`'s formula) explicitly labelled "server-computed on save" - it
  is never sent to the backend and the modal always reflects the server's real totals after a
  save completes, which is what "Do not rely on frontend totals" actually requires.
  `version` round-trips silently (loaded from the fetched order, resubmitted unchanged) - not a
  visible field, since a user has no meaningful action to take on it beyond what the 409
  conflict message and a reload already provide.
- **`router.tsx`**: `orders` now routes to `OrdersPage` instead of `PlaceholderPage`;
  `navConfig.ts` already had the "Orders" nav group and needed no change.

No mass upload was added - the brief allows it "unless trivial after core functionality," and a
correct bulk-order import needs its own validation/partial-failure story that is not trivial;
left for a later, explicitly scoped step.

## 5. Verification

Backend (`backend/tms-api`, `./mvnw -q -o test`, Docker Desktop running so every
Testcontainers-backed test executed rather than being skipped):

```
27 test classes, 253 tests, 0 failures, 0 errors
```

including the two new classes added this step:

- `OrderConstraintIntegrationTest` (19 tests, `com.ebim.tms.database`): `order_number` global
  uniqueness (12.1); the external-reference pair scoped per company and per source, and rejected
  without a source; an order's origin/destination must belong to its own company even though the
  FK columns are separate; `priority`/`status` restricted; the requested-window pair/order rule;
  `cancel_reason` requires `CANCELLED`; totals nonnegative; a line's quantity strictly positive,
  `uom` normalized, unit weight/volume positive when present; a line number must be positive;
  lines cascade-delete with their order; defaults (`NOT_READY`, `NORMAL`, zero totals,
  `version = 0`) and actor columns; a company/origin/destination is required (`NOT NULL`); **the
  `DEFERRABLE INITIALLY DEFERRED` line-number constraint** proven both directions - a delete-and-
  recreate replacement (mirroring what `TransportOrder.applyLines` actually does) survives
  `COMMIT`, and a genuine unresolved duplicate still fails, just at `COMMIT` instead of at the
  statement.
- `OrderApiIntegrationTest` (17 tests, `com.ebim.tms.orders.api`): create computes header totals
  from lines and reads back correctly; list returns a line count, never the lines; update
  recomputes totals after changing/adding/removing lines; an incomplete (only-start or
  start-after-end) requested window is rejected, a valid one accepted; an inactive origin is
  rejected even though it is real; an origin/destination from another company is rejected; a
  cross-company read answers 404; the same external reference is free to repeat across companies
  but conflicts inside one; `mark-ready` refuses an order with no lines, then refuses one whose
  totals are still all zero, then succeeds once a line carries known capacity; **editing a
  `READY_FOR_PLANNING` order resets it to `NOT_READY`**; cancel succeeds from `NOT_READY`/
  `READY_FOR_PLANNING`, refuses an already-cancelled order, and refuses a `PLANNED` one (seeded
  directly via SQL, since no endpoint in this step reaches `PLANNED`); **update requires the
  current version and rejects a stale one as a conflict** - the concurrency proof, using a real
  second write in between to advance the version rather than asserting the mechanism in the
  abstract; a read-only role may list/read but not manage; server-side pagination; status/priority
  filters.

Backend also re-ran the full pre-existing suite (`LayeringTest`, `ModuleBoundaryTest` - both
proving the `shared.reference` port design compiles and holds *before* any other Orders file was
written, `SchemaExposureIntegrationTest` with `transport_order`/`transport_order_line` added to
its RLS/`public`-schema assertions, `MigrationConventionTest`, every prior module's API
integration tests, etc.) with no regressions.

Frontend (`frontend/tms-web`):

```
npm run typecheck    tsc -b                clean, no errors
npm run lint          oxlint                0 errors, 2 pre-existing warnings (unrelated files, documented since 04_FRONTEND_FOUNDATION.md)
npm test               vitest run            27 files, 172 tests passed
npm run build         tsc -b && vite build  built in 411ms, dist/ produced
```

The 172 tests include the 2 new files added this step (`OrdersPage.test.tsx` 9,
`OrderFormModal.test.tsx` 8 - 17 new tests total) plus the full 155-test pre-existing suite,
unmodified and still green.

### 5.1 Test coverage against the brief

| Required case | Backend test | Frontend test |
|---|---|---|
| total recomputation | `OrderApiIntegrationTest.createComputesTotalsAndReadsBack`/`updateRecomputesTotals` | `OrderFormModal.test.tsx` (live preview, labelled server-computed) |
| invalid time windows | `OrderConstraintIntegrationTest.timeWindowIsValidated` (DB), `OrderApiIntegrationTest.invalidTimeWindowsAreRejected`/`validTimeWindowIsAccepted` | n/a (native `<input type="time">`, server-validated) |
| company isolation | `OrderConstraintIntegrationTest.originAndDestinationMustBelongToTheSameCompany`, `OrderApiIntegrationTest.crossCompanyMastersAreRejected`/`crossCompanyAccessIsBlocked`/`inactiveOriginIsRejected` | n/a (server-enforced; the add-selects only ever offer the caller's own company's active masters) |
| duplicate external id | `OrderConstraintIntegrationTest.externalReferenceIsScopedToItsCompany`/`externalReferenceIsScopedToItsSource`/`externalReferenceRequiresASource`, `OrderApiIntegrationTest.duplicateExternalReferenceIsScopedToItsCompany` | n/a (server-enforced) |
| state transitions | `OrderApiIntegrationTest.markReadyRequiresCompleteness`/`markReadySucceedsThenEditingResetsStatus`/`cancelRulesForOrdinaryStates`/`plannedOrderCannotBeCancelledDirectly` | `OrdersPage.test.tsx` (mark-ready/cancel confirmations, action visibility per status) |
| concurrency/version conflict | `OrderApiIntegrationTest.updateRequiresCurrentVersion` | `OrderFormModal.test.tsx` (submits the loaded order's version) |
| permissions | `OrderApiIntegrationTest.readOnlyRoleCannotManage` | `OrdersPage.test.tsx` (manage actions hidden without `orders.order:manage`) |
| frontend happy/error paths | n/a | `OrderFormModal.test.tsx` (validation, create with/without lines, edit/pre-fill, read-only view, field-error mapping, cancel) |

## 6. Constraint compliance

| Constraint | How |
|---|---|
| never push, never deploy | nothing was pushed; no deployment exists |
| never mutate a remote/shared database | all tests ran against a local, disposable Testcontainers PostgreSQL; no Supabase project or shared database was touched |
| no real secrets | no `.env` file was read or created |
| no destructive Git operations | none run; nothing was staged or committed per the overnight-pack instruction |
| Flyway is the only migration owner | V10 is the only schema change; no `supabase/migrations` entry was added |
| Java owns business logic and authorization | company scoping, active/cross-company validation, idempotency, status-transition rules, totals recomputation, optimistic-lock checks and `@PreAuthorize` all live in the backend |
| React talks to Spring Boot for business data | `ordersApi.ts` calls `apiRequest` exclusively; no direct Supabase table access was added |
| TMS independent from EWM | no new external-system reference was added; `externalSource`/`externalReference` are free-text, never a foreign key into another product |
| vertical slice checked end to end | `OrdersPage`/`OrderFormModal` → `ordersApi.ts` → `OrderController` → `OrderService` → `TransportOrderRepository`/`TransportOrderLineRepository` (+ `OriginLookupPort`/`DestinationLookupPort`) → `tms.transport_order`/`tms.transport_order_line` → RLS + `@PreAuthorize` → the tests in section 5, read and verified layer by layer |
| do not claim untested passes | every number in section 5 comes from a run executed this session; the Hibernate flush-order bug behind `uq_transport_order_line_order_line_number` (section 1) and every test-ordering/version-tracking mistake in the new test files were found by running the suites, not by reading the code |
| deferred-by-decision items untouched | no vehicle/carrier/route assignment on the order (Planning's job, step 10), no dispatch/delivery status, no OR-Tools, no GPS/telematics, no mass upload (documented as deferred, not forgotten) |

## 7. Files

Added:

```
backend/tms-api/src/main/resources/db/migration/V10__orders.sql
backend/tms-api/src/main/java/com/ebim/tms/shared/reference/{MasterReference,OriginLookupPort,
  DestinationLookupPort}.java
backend/tms-api/src/main/java/com/ebim/tms/masterdata/infrastructure/{OriginLookupAdapter,
  DestinationLookupAdapter}.java
backend/tms-api/src/main/java/com/ebim/tms/orders/domain/{OrderStatus,OrderPriority,
  OrderLineInput,TransportOrder,TransportOrderLine}.java
backend/tms-api/src/main/java/com/ebim/tms/orders/application/{OrderRequest,OrderFilter,
  OrderView,OrderDetailView,OrderService}.java
backend/tms-api/src/main/java/com/ebim/tms/orders/infrastructure/{TransportOrderRepository,
  TransportOrderLineRepository,TransportOrderSpecifications}.java
backend/tms-api/src/main/java/com/ebim/tms/orders/api/OrderController.java
backend/tms-api/src/test/java/com/ebim/tms/database/OrderConstraintIntegrationTest.java
backend/tms-api/src/test/java/com/ebim/tms/orders/api/OrderApiIntegrationTest.java
frontend/tms-web/src/shared/api/ordersApi.ts
frontend/tms-web/src/pages/orders/{OrdersPage,OrderFormModal}.tsx
frontend/tms-web/src/pages/orders/{OrdersPage,OrderFormModal}.test.tsx
docs/domain/ORDER_LIFECYCLE_V1.md
docs/overnight/09_ORDERS.md
```

Modified:

```
backend/tms-api/src/main/java/com/ebim/tms/shared/api/ApiExceptionHandler.java
  added handleOptimisticLockingFailure for ObjectOptimisticLockingFailureException
backend/tms-api/src/test/java/com/ebim/tms/database/SchemaExposureIntegrationTest.java
  transport_order/transport_order_line added to the RLS and not-in-public checks
frontend/tms-web/src/app/router.tsx
  orders routed to the real OrdersPage instead of PlaceholderPage
docs/database/DATA_MODEL.md
  documented the V10 orders model (new section 12), renumbered "rules for the next migrations"
  to section 13 and added rules 10 (cross-module lookup ports) and 11 (explicit version check),
  and updated section 6's test-coverage table
```

## 8. Handoff to Step 10 (manual planning backend)

1. **`OrderStatus.PLANNED` and `TransportOrder.markPlanned` already exist but are unreachable.**
   Planning's backend should call `TransportOrder.markPlanned(actorId)` once it assigns an order
   to a trip - no schema or enum change needed, only the transition itself and its own
   completeness/authorization rules (e.g. "only a `READY_FOR_PLANNING` order may be planned").
2. **`OrderService.cancel` already refuses a `PLANNED` order** with "unassign it from its trip
   first" - Planning needs to decide what "unassign" means (does it revert the order to
   `READY_FOR_PLANNING`?) and implement that transition; Orders intentionally does not guess it.
3. **`uq_transport_order_id_company` already exists** on `tms.transport_order` for Planning's
   trip-to-order assignment table to reference with the same composite-FK tenant guarantee every
   cross-table reference in this schema uses (`DATA_MODEL.md` rule 6). Follow `route_stop`'s
   shape (V8) if the assignment table needs its own `company_id`.
4. **The `shared.reference` port pattern (rule 10) is ready to reuse.** Planning will need to
   resolve fleet vehicles/carriers (owned by `fleet`) and orders (owned by `orders`) from its own
   module; add `VehicleLookupPort`/`CarrierLookupPort`/`OrderLookupPort` (or similar) in
   `shared.reference` rather than importing `fleet`/`orders` directly - see
   `OriginLookupPort`/`DestinationLookupPort` and their adapters as the template, and
   `docs/database/DATA_MODEL.md` section 12.6 for the reasoning.
5. **`EffectiveCapacityResolver` (fleet, from Step 08) and `TransportOrder.totalWeightKg`/
   `totalVolumeM3`/`totalPallets` are both ready for Planning's capacity checks** as plain,
   already-resolved values - no second computation needed, per
   `docs/architecture/OWNERSHIP_MATRIX.md`, "Capacity checks."
6. **The explicit-version-check optimistic-locking pattern (rule 11) is ready to reuse** for any
   Planning entity two people might edit concurrently (a trip's stop list, for instance) - see
   `OrderService.requireCurrentVersion`/`saveOrConflict` and
   `ApiExceptionHandler.handleOptimisticLockingFailure` as the template.
7. **`OrderFormModal`'s "one component serves create/edit/read-only-view" pattern** is available
   if Planning's own screens have a similar "some states are no longer editable" shape (a
   dispatched trip, for instance) - see that component's `isEditable` branch.

## 9. Result

Orders V1 is complete end to end: a company-scoped transport-order header and lines with a
system-generated stable number, an optional idempotency pair, mandatory origin/destination
(validated active and same-company - stronger than Route's precedent, per the brief), a minimal
four-state status lifecycle (`NOT_READY`/`READY_FOR_PLANNING`/`PLANNED`/`CANCELLED`, documented
in `docs/domain/ORDER_LIFECYCLE_V1.md`), transactionally-recomputed weight/volume/pallet totals,
and the first optimistic-locking column in the schema with an explicit stale-write check. The
first genuine cross-module reference in the codebase (`orders` needing `masterdata`'s origin and
destination) was resolved with a small port/adapter pair in `shared.reference`, verified against
`ModuleBoundaryTest`/`LayeringTest` before any dependent code was written. A real Hibernate
flush-ordering bug in the delete-and-recreate line-replacement strategy was found by running the
tests (not by reasoning about the code) and fixed with the same `DEFERRABLE` idiom Route already
established, for a new reason. Bootstrap/SweetAlert2 screens provide a dense filterable list,
mark-ready/cancel confirmations, and one form that serves create, edit and read-only detail
without a fourth screen. 253 backend tests and 172 frontend tests pass; typecheck, lint and both
production builds are clean.

TMS_GATE=PASS
