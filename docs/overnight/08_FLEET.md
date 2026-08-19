# Step 08 - Fleet Masters: Carriers, Vehicle Types and Vehicles

Date: 2026-08-19
Attempt: 1
Result: **PASS**

## 0. State inherited from prior steps

The repository arrived with a clean working tree and Step 07 (`07_ROUTES` in the overnight log,
migration V8) complete: `tms.route`/`tms.route_stop`, the full `masterdata` module shape, the
list/detail-split and `DEFERRABLE` reorder patterns, and working frontend vertical slices for
Origins, Zones, Destinations, Frequencies and Routes. `fleet.carrier:read/manage`,
`fleet.vehicle_type:read/manage` and `fleet.vehicle:read/manage` permissions already existed in
migration V3 (granted to `VIEWER`/`PLANNER` as read, `COMPANY_ADMIN`/`ORGANIZATION_ADMIN` as
full), `Capability.FLEET_VIEW`/`FLEET_MANAGE` already mapped them, `router.tsx` already had
`fleet/carriers`, `fleet/vehicle-types` and `fleet/vehicles` routes pointing at `PlaceholderPage`,
`navConfig.ts` already listed the "Fleet" nav group, and `com.ebim.tms.fleet.package-info.java`
already existed as an empty placeholder describing the module's intended scope ("carriers,
vehicle types and vehicles, plus effective-capacity resolution"). This attempt read
`docs/overnight/07_ROUTES.md` section 8 ("Handoff to Step 08") end to end before writing
anything (rule 6) and followed it point by point:

1. The handoff correctly predicted fleet would not need the deferrable-constraint or
   list/detail-split patterns Routes introduced - none of the three fleet tables have an
   ordered/reorderable child list or an N+1-sensitive list shape, so all three follow the plain
   `Origin`/`Zone` CRUD shape.
2. The handoff's point 2 (reuse the composite-FK idiom for any table a fleet table references)
   applied directly: `tms.vehicle.carrier_id` and `tms.vehicle.vehicle_type_id` both needed
   `uq_<table>_id_company` targets, added fresh in V9 since carrier/vehicle_type did not exist
   before this step.
3. Point 3 (a child-of-a-child needing its own `company_id`) did not apply - none of the three
   fleet tables is a child of another fleet table.
4. Point 4 (`RouteService.loadByIds`) was reused directly: `VehicleService` needed to batch-
   resolve two related masters (carrier, vehicle type) per list page, and copies the same
   generic private helper rather than inventing a new shape - see section 3.

## 1. Scope decisions made before writing code

- **`carrier.tax_id_type`/`tax_id_value` are a flexible free-text pair, not an enum.** The step
  brief explicitly asks for "legal/tax identification type/value in a flexible model." A fixed
  `CHECK ... IN ('RUC', 'DNI', ...)` catalogue would hardcode a Peru-specific (or any single
  country's) vocabulary into the schema, the same trap `destination.district`/`province`/
  `department` (V7) deliberately avoided for locality fields. Both halves are still normalized
  (trimmed, upper-cased, enforced by `CHECK`, not just convention - `DATA_MODEL.md` section 3.8)
  so the per-company uniqueness constraint actually catches case-drift duplicates. See
  `DATA_MODEL.md` section 10.1.
- **A vehicle's license plate is unique per company, not installation-wide**, even though a real
  plate is a globally-unique physical identifier. Scoping to `company_id` matches every other
  master's code uniqueness (ADR-003) and avoids a cross-tenant information leak: a global
  constraint would let company A learn whether company B has registered a specific plate by
  triggering a 409. Documented as a new, explicit migration rule (rule 9) in `DATA_MODEL.md`
  section 11, since the reasoning generalizes to any future real-world-unique identifier (a VIN,
  a national tax id used somewhere other than `carrier`). See section 10.2 of that document.
- **`vehicle.carrier_id` is optional; `vehicle.vehicle_type_id` is mandatory.** The brief lists
  "carrier; vehicle type" as fields a vehicle supports without saying either is required. A
  company may run its own owned fleet with no third-party carrier at all, so `carrier_id` is
  nullable (the vehicle's `carrierCode`/`carrierBusinessName` are `null` in that case, and the
  frontend list/form both render "Owned fleet"). `vehicle_type_id` is mandatory because
  `EffectiveCapacityResolver` always needs a type to fall back to - a vehicle with no type would
  have no defined capacity at all.
- **`vehicle_type`'s temperature range only makes sense when `temperature_controlled` is true.**
  Rather than a separate "temperature profile" table (which the brief's "only when the model
  remains simple" instruction argues against), `min_temperature_celsius`/`max_temperature_celsius`
  are two nullable columns on `vehicle_type` itself, guarded by a `CHECK` that refuses a
  temperature value unless `temperature_controlled` is true, plus the ordinary `min <= max`
  range check. `VehicleTypeService.validateTemperatureRange` mirrors the same rule in Java so a
  violation is a clean 400 (`malformed-request`) rather than a raw constraint failure.
- **`vehicle.availability_status` is a current-state flag (`AVAILABLE`/`IN_MAINTENANCE`/
  `OUT_OF_SERVICE`), not a scheduling calendar** - the step brief's explicit "does not pretend to
  be a full scheduling calendar." No dates, no slots, no shifts; Trip/planning-level scheduling
  (not built) is a separate, later concern this column does not anticipate the shape of.
- **`EffectiveCapacityResolver` is a small stateless `@Service`, not an entity method or a
  static utility.** The step brief asks for "one backend service that resolves effective
  capacities." Making it a Spring service (rather than, say, `Vehicle.effectiveCapacity(type)`)
  keeps the vehicle entity free of a computed cross-aggregate rule and makes the resolver trivial
  to reuse from Planning later (`docs/architecture/OWNERSHIP_MATRIX.md`, "Capacity checks") and
  to unit-test without a Spring context (`EffectiveCapacityResolverTest` constructs it with
  `new EffectiveCapacityResolver()`).
- **List and detail share one `VehicleView` shape**, unlike Route's list/detail split. Routes
  needed two shapes because the brief explicitly forbade loading every stop for every list row
  (N+1). No fleet table has an unbounded child collection - `VehicleView` needs at most two
  batched lookups (carrier, vehicle type) per page, the same one-extra-lookup shape
  `DestinationView`/`RouteView` already used for a single related master, just duplicated once
  for two masters instead of one. `VehicleService.list` batches both with the same generic
  `loadByIds` helper `RouteService` established (duplicated locally, not shared, because
  `fleet` must not depend on `masterdata` - `ModuleBoundaryTest`).

## 2. Database (migration V9)

`backend/tms-api/src/main/resources/db/migration/V9__fleet_masters.sql` follows V6/V7/V8's shape
(`DATA_MODEL.md` section 11, "Rules for the next migrations") and documents the two new
decisions above (10.1, 10.2) plus the new rule 9 it establishes:

- **`tms.carrier`**: `company_id NOT NULL` + FK + leading index, normalized-code `CHECK`,
  `uq_carrier_company_code`, `tax_id_type`/`tax_id_value` both `NOT NULL`, non-blank and
  normalized (upper/trim) by `CHECK`, `uq_carrier_company_tax_id UNIQUE (company_id,
  tax_id_type, tax_id_value)`, optional `contact_name`/`phone` (non-blank when present),
  optional `email` normalized lower and shape-checked with the same regex `tms.app_user.email`
  (V2) uses, `active`, audit columns, `uq_carrier_id_company` as the composite-FK target
  `vehicle.carrier_id` needs.
- **`tms.vehicle_type`**: `company_id NOT NULL` + FK + leading index, normalized-code `CHECK`,
  `uq_vehicle_type_company_code`, `max_weight_kg numeric(10,2)`/`max_volume_m3 numeric(10,3)`
  both `NOT NULL` and strictly positive (`CHECK > 0`), `max_pallets integer NOT NULL DEFAULT 0`
  nonnegative (zero is legitimate - a tanker), optional `length_m`/`width_m`/`height_m
  numeric(6,2)` each positive when present, optional `body_type` restricted to a small fixed
  catalogue (`DRY_VAN`, `REFRIGERATED`, `FLATBED`, `TANKER`, `CONTAINER`, `CURTAIN_SIDER`,
  `OTHER`), `temperature_controlled boolean NOT NULL DEFAULT false` plus optional
  `min_temperature_celsius`/`max_temperature_celsius numeric(5,2)` guarded by the
  "requires-controlled" and "min <= max" checks, optional `axles integer >= 1`, `active`, audit
  columns, `uq_vehicle_type_id_company` as the composite-FK target `vehicle.vehicle_type_id`
  needs.
- **`tms.vehicle`**: `company_id NOT NULL` + FK + leading index, normalized-code `CHECK`,
  `uq_vehicle_company_code`, `license_plate` normalized upper/trim by `CHECK` plus a permissive
  shape check (`^[A-Z0-9-]{4,12}$` - letters, digits, hyphen, 4-12 characters, deliberately not
  tied to one country's plate format), `uq_vehicle_company_license_plate` (see 10.2 above),
  optional `carrier_id` with the composite-FK tenant guarantee (`fk_vehicle_carrier_company`,
  `MATCH SIMPLE` so a `NULL` carrier stays legal), mandatory `vehicle_type_id` with the same
  composite-FK guarantee (always checked, never `NULL`), three independently optional override
  columns (`max_weight_override_kg`, `max_volume_override_m3`, `max_pallets_override`) each
  positive/nonnegative when present, `availability_status text NOT NULL DEFAULT 'AVAILABLE'`
  restricted to the three-value catalogue, `active`, audit columns.
- All three tables get `ENABLE ROW LEVEL SECURITY` in this same migration, matching every table
  before them.

## 3. Backend

`fleet` package additions follow the `Origin`/`Zone`/`Destination` reference implementations
(`docs/overnight/07_ROUTES.md` section 8, points 1-2) inside a fresh module
(`com.ebim.tms.fleet`), not the `masterdata` module, per `ModuleBoundaryTest`'s "business
modules must not depend on each other" rule:

- **`Carrier`/`VehicleType`/`Vehicle`**: plain JPA entities in `fleet.domain`, same
  `applyChanges`/`activate`/`deactivate` shape as `Zone`/`Origin`. `VehicleType` maps every
  capacity/dimension field with its unit explicit in the accessor name
  (`maxWeightKg()`, `maxVolumeM3()`, `lengthM()`, ...), matching the migration's column names one
  for one - "do not mix kilograms/tons or m3/cm3 implicitly" is satisfied by never having a
  column or field whose unit is ambiguous in the first place.
- **`CarrierService`**: pre-checks both the code and the `(taxIdType, taxIdValue)` pair for a
  duplicate before writing (two independent unique constraints, so `saveOrConflict` disambiguates
  a raced write by re-checking which value is actually now duplicated rather than assuming it
  was the code - see the class comment on that method). Email format is validated in Java
  (`InvalidRequestException` -> 400 `malformed-request`) rather than left to the database `CHECK`
  alone, the same defense-in-depth split `DestinationService.validateCoordinatePair` uses.
- **`VehicleTypeService`**: `validateTemperatureRange` mirrors the two temperature `CHECK`s in
  Java before the insert/update ever reaches the database, for the same clean-400-instead-of-
  generic-500 reason.
- **`VehicleService`**: `requireCarrierInScope` (optional, `null` allowed) and
  `requireVehicleTypeInScope` (mandatory) validate cross-entity assignment against the caller's
  own company, returning `InvalidRequestException` (400 `malformed-request`) on a cross-company
  id rather than a raw constraint violation - the same pattern
  `RouteService.requireOriginInScope`/`requireZoneInScope` established. List batch-resolves
  carrier and vehicle type for the whole page with the `loadByIds` generic helper (duplicated
  from `RouteService`, not shared, per the module-boundary constraint noted above);
  `saveOrConflict` disambiguates a raced code-vs-plate duplicate the same way `CarrierService`
  disambiguates code-vs-tax-id.
- **`EffectiveCapacityResolver`** (`fleet.application`, `@Service`, no fields): `resolve(Vehicle,
  VehicleType)` returns an `EffectiveCapacity` record, applying each of weight/volume/pallets
  independently (vehicle override first, otherwise the type default) - unit-tested directly in
  `EffectiveCapacityResolverTest` without a Spring context. `VehicleService` calls it for every
  read (`get`/`list`/`create`/`update`/`activate`/`deactivate`), so `VehicleView` always carries
  both the raw overrides and the already-resolved `effectiveMax*` fields.
- **`CarrierController`/`VehicleTypeController`/`VehicleController`**: mirror
  `DestinationController` - `fleet.carrier:read/manage`, `fleet.vehicle_type:read/manage`,
  `fleet.vehicle:read/manage` `@PreAuthorize`, `CompanyScope` resolved by the framework, no
  delete endpoint (deactivate only).

## 4. Frontend

`carriersApi.ts`/`vehicleTypesApi.ts`/`vehiclesApi.ts` and three screen pairs follow the
`zonesApi.ts`/`ZonesPage`/`ZoneFormModal` (simple, no cross-reference) and
`destinationsApi.ts`/`DestinationsPage`/`DestinationFormModal` (cross-reference select) templates:

- **`CarriersPage`/`CarrierFormModal`**: the simplest of the three - no cross-entity reference,
  same shape as `ZonesPage`/`ZoneFormModal`. List columns: code, business name, tax id
  (`type value`), contact, phone, status.
- **`VehicleTypesPage`/`VehicleTypeFormModal`**: a longer form (weight/volume/pallets,
  optional dimensions, optional body type, temperature-controlled checkbox with its own
  min/max fields) but still no cross-entity reference. Client-side validation mirrors the
  backend: weight/volume/dimensions must be `> 0` when present, pallets `>= 0`,
  temperature fields rejected unless "Temperature controlled" is checked (the same rule
  `VehicleTypeService.validateTemperatureRange` enforces server-side) - a first version of this
  form used `react-hook-form`'s `watch()` to visually disable the temperature inputs, which
  `oxlint`'s `react(incompatible-library)` rule flags (`watch()`'s return value cannot be safely
  memoized); dropped in favor of relying on the existing per-field `validate` function, which
  already receives live form values as its second argument without needing `watch()` at all -
  zero new lint warnings, one less dependency on a flagged API.
- **`VehiclesPage`/`VehicleFormModal`**: the cross-reference case, same pattern
  `DestinationFormModal` uses for `zoneId` - `carrierId`/`vehicleTypeId` `<select>`s populated
  from an active-only fetch, prepending the currently-assigned value if it was deactivated since
  (so editing a vehicle whose carrier/type was deactivated never breaks, matching the invariant
  `DATA_MODEL.md` section 9.5 already established for routes). The vehicle list columns are
  exactly what the brief asks for: plate/code (plate first, code as a muted subtitle), carrier
  (or "Owned fleet" when `carrierId` is `null`), type, an effective-capacity summary
  (`{weight} kg · {volume} m³ · {pallets} pallets`, using the server-resolved
  `effectiveMax*` fields directly - the frontend never re-implements the override-first rule),
  an availability badge (`AVAILABLE`/`IN_MAINTENANCE`/`OUT_OF_SERVICE`, colored
  success/warning/neutral) and the active/inactive status badge. No GPS/map/tracking UI was
  added, per the brief.
- **`router.tsx`**: `fleet/carriers`, `fleet/vehicle-types` and `fleet/vehicles` now route to the
  real pages instead of `PlaceholderPage`; `navConfig.ts` already had the "Fleet" group and
  needed no change.

## 5. Verification

Backend (`backend/tms-api`, `./mvnw -q -o test`, Docker Desktop running so every
Testcontainers-backed test executed rather than being skipped):

```
25 test classes, 226 tests, 0 failures, 0 errors
```

including the three new classes/files added this step:

- `FleetConstraintIntegrationTest` (16 tests, `com.ebim.tms.database`): carrier/vehicle-type/
  vehicle code uniqueness per company; carrier tax-id pair uniqueness per company and free reuse
  across companies; carrier code and tax-id normalization (rejects lower-case/un-trimmed input);
  carrier email normalization/shape validated only when present, absent email is legitimate;
  carrier requires a real company; vehicle-type max weight/volume strictly positive, max pallets
  nonnegative-including-zero; optional dimensions positive when present; `body_type` restricted;
  axles `>= 1`; the temperature-range coherence rule proven both directions (rejected without
  `temperature_controlled`, rejected when inverted, accepted when coherent); vehicle code and
  license-plate uniqueness per company and free reuse across companies; license plate
  normalization and shape; a vehicle requires a real vehicle type but carrier is optional;
  **a vehicle's carrier and vehicle type must each belong to the vehicle's own company even
  though both rows genuinely exist in another company** (the cross-company-assignment proof the
  step brief explicitly asks for); override capacities positive/nonnegative when present;
  `availability_status` restricted; defaults and actor columns for both carrier and vehicle.
- `FleetApiIntegrationTest` (19 tests, `com.ebim.tms.fleet.api`, three `@Nested` groups -
  Carriers/VehicleTypes/Vehicles): carrier create/normalize/list, duplicate code and duplicate
  tax-id each independently scoped per company, malformed email rejected as
  `malformed-request`, cross-company access blocked with 404, read-only role blocked from
  managing, activate/deactivate; vehicle-type create/normalize, zero/negative capacity rejected
  as `validation-failed` with the offending field named, zero pallets accepted, a temperature
  range without `temperatureControlled` rejected as `malformed-request`, read-only role blocked;
  vehicle create resolves carrier/type names and computes the effective capacity from the type
  when no override is set, **a per-field override takes precedence while the other two
  dimensions still fall back to the type's defaults** (the exact override-resolution proof the
  brief asks for), duplicate code and duplicate license plate each independently rejected as a
  conflict and each independently free to reuse across companies, a vehicle type or carrier from
  another company rejected as `malformed-request` (cross-company assignment, end to end through
  the real HTTP filter chain), cross-company read blocked with 404, read-only role blocked,
  update re-resolves the effective capacity when the override changes, server-side pagination.
- `EffectiveCapacityResolverTest` (4 tests, `com.ebim.tms.fleet.application`, plain unit test -
  no Spring context): no overrides falls back to every type default; a single override is
  applied while the other two dimensions still use the type default (the core per-field
  independence the resolver exists for); every dimension overridden uses none of the type's
  defaults; a zero pallets override is honoured as a real override, not treated as "absent" -
  this last case is exactly why the resolver checks `!= null` and never `> 0`/truthiness on the
  override fields.

Backend also re-ran the full pre-existing suite (`LayeringTest`, `ModuleBoundaryTest`,
`SchemaExposureIntegrationTest` with `carrier`/`vehicle_type`/`vehicle` added to its RLS/
`public`-schema assertions, `OriginZoneApiIntegrationTest`, `DestinationFrequencyApiIntegrationTest`,
`RouteApiIntegrationTest`, `ApiSecurityTest`, etc.) with no regressions.

Frontend (`frontend/tms-web`):

```
npm run typecheck    tsc -b                clean, no errors
npm run lint          oxlint                0 errors, 2 pre-existing warnings (unrelated files, documented since 04_FRONTEND_FOUNDATION.md)
npm test              vitest run            25 files, 155 tests passed
npm run build         tsc -b && vite build  built in 407ms, dist/ produced
```

The 155 tests include the 6 new files added this step (`CarrierFormModal.test.tsx` 6,
`CarriersPage.test.tsx` 8, `VehicleTypeFormModal.test.tsx` 7, `VehicleTypesPage.test.tsx` 7,
`VehicleFormModal.test.tsx` 8, `VehiclesPage.test.tsx` 8 - 44 new tests total) plus the full
111-test pre-existing suite, unmodified and still green.
`VehicleFormModal.test.tsx` covers: rejecting an empty submission; rejecting a malformed license
plate; listing carriers/vehicle types fetched from the backend in their selects; creating a
vehicle with no carrier (owned fleet) and no overrides; creating a vehicle with a single weight
override; pre-filling an existing vehicle's assignment even when it would be excluded by the
active-only fetch (the same "deactivation does not break the editor" pattern
`RouteFormModal.test.tsx` proved for stops); mapping a backend field error onto the matching
input; closing on Cancel. `VehicleTypeFormModal.test.tsx` covers the capacity/temperature
validation rules directly, including the zero-pallets-is-valid case and the
temperature-requires-controlled rejection. `VehiclesPage.test.tsx` covers the "Owned fleet"
rendering for a `null` carrier and asserts the effective-capacity summary and availability badge
render from the server-resolved fields.

One run-time defect was found and fixed while writing `VehiclesPage.test.tsx`, not by reading the
code: `screen.getByText('Available')` initially matched both the row's availability badge and the
filter bar's `<option>Available</option>`, failing with a "multiple elements found" error; fixed
by scoping the assertion to `{ selector: 'span' }` (only the badge is a `<span>`), the same class
of test-only ambiguity `RouteFormModal.test.tsx` hit and fixed the same way (07_ROUTES.md
section 5). A second, code-level issue was caught by `oxlint` rather than a failing test:
`VehicleTypeFormModal`'s first draft used `watch()` to disable the temperature inputs, which
oxlint's `react(incompatible-library)` rule flags; removed in favor of the existing per-field
`validate` function (see section 4).

### 5.1 Test coverage against the brief

| Required case | Backend test | Frontend test |
|---|---|---|
| capacity validation (weight/volume positive, pallets nonnegative) | `FleetConstraintIntegrationTest.vehicleTypeCapacitiesAreValidated` (DB), `FleetApiIntegrationTest.VehicleTypes.zeroOrNegativeCapacityIsRejected`/`zeroPalletsIsAccepted` | `VehicleTypeFormModal.test.tsx` (rejects zero/negative, accepts zero pallets) |
| negative/zero edge cases | same as above, plus override capacities (`vehicleOverridesAndAvailabilityAreValidated`) | `VehicleFormModal.test.tsx` (weight override submitted as a number) |
| override resolution (vehicle override first, else vehicle type default) | `EffectiveCapacityResolverTest` (unit, all four cases), `FleetApiIntegrationTest.Vehicles.createResolvesEffectiveCapacityFromType`/`effectiveCapacityPrefersVehicleOverridePerField`/`updateRecomputesEffectiveCapacity` | `VehicleFormModal.test.tsx` (creates with a single override) |
| duplicate plate/code | `FleetConstraintIntegrationTest.vehicleCodeAndPlateAreScopedToTheirCompany`, `FleetApiIntegrationTest.Vehicles.duplicateCodeAndPlateAreScoped` | n/a (server-enforced) |
| cross-company assignments | `FleetConstraintIntegrationTest.vehicleCarrierAndTypeMustBelongToTheSameCompany` (DB, both directions), `FleetApiIntegrationTest.Vehicles.crossCompanyAssignmentIsRejected` | n/a (the add-selects only ever offer the caller's own company's carriers/types) |
| permissions | `FleetApiIntegrationTest` `readOnlyRoleCannotManage` in all three nested groups | `CarriersPage.test.tsx`/`VehicleTypesPage.test.tsx`/`VehiclesPage.test.tsx` (manage actions hidden without `fleet.*:manage`) |
| frontend forms | `CarrierFormModal.test.tsx`, `VehicleTypeFormModal.test.tsx`, `VehicleFormModal.test.tsx` (validation, create, edit/pre-fill, field-error mapping, cancel) | (same) |

## 6. Constraint compliance

| Constraint | How |
|---|---|
| never push, never deploy | nothing was pushed; no deployment exists |
| never mutate a remote/shared database | all tests ran against a local, disposable Testcontainers PostgreSQL; no Supabase project or shared database was touched |
| no real secrets | no `.env` file was read or created |
| no destructive Git operations | none run; nothing was staged or committed per the overnight-pack instruction |
| Flyway is the only migration owner | V9 is the only schema change; no `supabase/migrations` entry was added |
| Java owns business logic and authorization | company scoping, code/plate/tax-id normalization, cross-company carrier/type rejection, conflict detection, effective-capacity resolution and `@PreAuthorize` all live in the backend |
| React talks to Spring Boot for business data | `carriersApi.ts`/`vehicleTypesApi.ts`/`vehiclesApi.ts` call `apiRequest` exclusively; no direct Supabase table access was added |
| TMS independent from EWM | no new external-system reference was added; fleet references only TMS's own company/tenancy model |
| vertical slice checked end to end | `CarriersPage`/`VehicleTypesPage`/`VehiclesPage` and their form modals → `carriersApi.ts`/`vehicleTypesApi.ts`/`vehiclesApi.ts` → `CarrierController`/`VehicleTypeController`/`VehicleController` → `CarrierService`/`VehicleTypeService`/`VehicleService` (+ `EffectiveCapacityResolver`) → `CarrierRepository`/`VehicleTypeRepository`/`VehicleRepository` → `tms.carrier`/`tms.vehicle_type`/`tms.vehicle` → RLS + `@PreAuthorize` → the tests in section 5, read and verified layer by layer |
| do not claim untested passes | every number in section 5 comes from a run executed this session; the `getByText` ambiguity in `VehiclesPage.test.tsx` and the `oxlint` `watch()` warning were only found by running the suites/linter, not by reading the code |
| deferred-by-decision items untouched | no GPS/telematics, no live map tracking, no OR-Tools/optimization, no scheduling calendar - `availability_status` is explicitly a current-state flag (section 1) |

## 7. Files

Added:

```
backend/tms-api/src/main/resources/db/migration/V9__fleet_masters.sql
backend/tms-api/src/main/java/com/ebim/tms/fleet/domain/{Carrier,VehicleType,Vehicle,
  VehicleBodyType,VehicleAvailabilityStatus}.java
backend/tms-api/src/main/java/com/ebim/tms/fleet/application/{CarrierFilter,CarrierRequest,
  CarrierView,CarrierService,VehicleTypeFilter,VehicleTypeRequest,VehicleTypeView,
  VehicleTypeService,VehicleFilter,VehicleRequest,VehicleView,VehicleService,
  EffectiveCapacity,EffectiveCapacityResolver}.java
backend/tms-api/src/main/java/com/ebim/tms/fleet/infrastructure/{CarrierRepository,
  CarrierSpecifications,VehicleTypeRepository,VehicleTypeSpecifications,VehicleRepository,
  VehicleSpecifications}.java
backend/tms-api/src/main/java/com/ebim/tms/fleet/api/{CarrierController,VehicleTypeController,
  VehicleController}.java
backend/tms-api/src/test/java/com/ebim/tms/database/FleetConstraintIntegrationTest.java
backend/tms-api/src/test/java/com/ebim/tms/fleet/api/FleetApiIntegrationTest.java
backend/tms-api/src/test/java/com/ebim/tms/fleet/application/EffectiveCapacityResolverTest.java
frontend/tms-web/src/shared/api/{carriersApi,vehicleTypesApi,vehiclesApi}.ts
frontend/tms-web/src/pages/fleet/{CarriersPage,CarrierFormModal,VehicleTypesPage,
  VehicleTypeFormModal,VehiclesPage,VehicleFormModal}.tsx
frontend/tms-web/src/pages/fleet/{CarriersPage,CarrierFormModal,VehicleTypesPage,
  VehicleTypeFormModal,VehiclesPage,VehicleFormModal}.test.tsx
docs/overnight/08_FLEET.md
```

Modified:

```
backend/tms-api/src/test/java/com/ebim/tms/database/SchemaExposureIntegrationTest.java
  carrier/vehicle_type/vehicle added to the RLS and not-in-public checks
frontend/tms-web/src/app/router.tsx
  fleet/carriers, fleet/vehicle-types and fleet/vehicles routed to the real pages
docs/database/DATA_MODEL.md
  documented the V9 fleet model (new section 10), renumbered the migration-rules section to 11
  and added rule 9 (uniqueness always scopes to company_id, even for a real-world-unique
  identifier), and updated section 6's test-coverage table
```

## 8. Handoff to Step 09 (orders)

1. **Orders will need to reference fleet masters (vehicle/carrier) and master-data masters
   (origin/destination) from the caller's own company.** Reuse the composite-FK idiom (rule 6)
   for each reference; `uq_carrier_id_company`/`uq_vehicle_type_id_company` already exist for a
   vehicle to reference, and `uq_origin_id_company`/`uq_destination_id_company` already exist
   from V8. Add a fresh `uq_vehicle_id_company` if Orders (or Planning after it) needs to
   reference a specific vehicle directly.
2. **The `loadByIds` generic batch-resolution helper** now has two independent copies
   (`RouteService`, `VehicleService`) because `masterdata` and `fleet` must not depend on each
   other (`ModuleBoundaryTest`). If a third module needs the same pattern, consider whether it
   belongs in `shared` as a small generic utility rather than a third copy - it would need to
   stay free of any business-module dependency to pass `shared_must_not_depend_on_business_modules`.
3. **`EffectiveCapacityResolver` is ready for Planning's capacity checks** as-is
   (`docs/architecture/OWNERSHIP_MATRIX.md`, "Capacity checks: Primary owner Java"). It takes
   plain `Vehicle`/`VehicleType` entities and returns an `EffectiveCapacity` record - no
   repository access, so a future planning use case can call it directly once it has both
   entities in hand, the same way `VehicleService` already does.
4. **A vehicle-to-order/trip assignment table, if Orders/Planning's brief asks for one, should
   follow the same "defer until a concrete screen needs it" judgment** Steps 05-07 already
   established for zone geometry, the destination-frequency association and route-frequency
   coupling - not needed by fleet either (no assignment history table was added in V9; a vehicle
   simply carries its current `availability_status`).
5. **`oxlint`'s `react(incompatible-library)` rule flags `react-hook-form`'s `watch()`.** If a
   future form needs to conditionally show/hide/disable a field based on another field's live
   value, prefer reading the value inside a `validate` callback (which already receives the full
   form values with no extra hook) over `watch()` - see section 4's note on
   `VehicleTypeFormModal`.

## 9. Result

Fleet masters are complete end to end: company-scoped CRUD with activate/deactivate for
carriers, vehicle types and vehicles; a flexible (not hardcoded) tax-identification model for
carriers; explicit-unit, positive/nonnegative-validated capacity and physical-dimension fields
for vehicle types with an optional, internally-coherent temperature range; a vehicle model that
optionally attaches to a carrier and always attaches to a vehicle type, with three independently
overridable capacity dimensions; a single `EffectiveCapacityResolver` service that is the one
place vehicle-override-first-else-type-default is computed, reused by every read and ready for
Planning; company-scoped uniqueness even for real-world-unique identifiers like a license plate
(with the cross-tenant-leak reasoning documented as a new migration rule); composite-FK tenant
guarantees on every cross-table reference (carrier, vehicle type); and Bootstrap/SweetAlert2
screens showing plate/code, carrier, type, effective capacity and availability, with no
GPS/tracking UI. 226 backend tests and 155 frontend tests pass; typecheck, lint and both
production builds are clean. Two real defects (a test-scoping ambiguity in
`VehiclesPage.test.tsx` and an `oxlint`-flagged `watch()` usage in `VehicleTypeFormModal`) were
found by running the suites/linter and fixed before this report was written - not found by
reading the code alone.

TMS_GATE=PASS
