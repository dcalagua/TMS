# ADR-022 - Material physical profile and load geometry (stackable and nestable goods)

- Status: Accepted (design; implementation in overnight-pack phases 20, 40 and 45)
- Date: 2026-10-18
- Relates to: ADR-001 (React -> Spring Boot -> PostgreSQL), ADR-003 (company scope), ADR-005 (tenant
  RLS), ADR-013 (warehouse dispatch), ADR-025 (warehouse sync v1.1, which carries the profile from
  the warehouse), `docs/domain/CAPACITY_MODEL.md`, `docs/domain/SPLIT_ORDER_EXECUTION.md`,
  `docs/roadmap/2026-10-enterprise/02_APILABLES_ANIDABLES.md` (plan) and `DECISIONES.md` D-02, D-05.
- Migrations: **V59** (profile + line snapshot) and **V62** (geometry, capacity, ledger, snapshot),
  **V63** (loading-metre rate). Numbers are provisional; `PHASE_MAP.md` is the register.

## Context

PLAPASA makes stackable crates and nestable cups and buckets. TMS today sees a load as exactly three
sums - kilograms, cubic metres and pallets - and computes a line's volume as
`quantity x unitVolumeM3` (`orders/domain/OrderLineInput.java:33`). Read from the code at `a57af24`:

- `planning/application/CapacityLimits.java:24` is `(maxWeightKg, maxVolumeM3, maxPallets)`;
  `accommodates` (:56-64) is three inclusive `within` checks, `null` = unlimited, `0` = a real limit.
- `planning/application/CapacityLoad.java:17` is `(weightKg, volumeM3, pallets, orderCount)` and is
  never null in a dimension: an unknown contributes zero (`CAPACITY_MODEL.md`, "Null and zero").
- `fleet/application/EffectiveCapacityResolver.java:22` resolves the three numbers per dimension
  (vehicle override, else vehicle type). `vehicle_type.length_m/width_m/height_m` exist since V9
  (`V9__fleet_masters.sql:91-93`, nullable, `> 0`) and **nothing outside fleet CRUD and import reads
  them**.
- There is no material master: `transport_order_line.material_code` / `material_description` are free
  text (V10:184-185).
- The allocation ledger is `tms.trip_order_assignment` (V11) with `assigned_weight_kg/volume_m3/pallets`;
  V37 added `transport_order.allocated_*` with `ck_transport_order_not_over_allocated`
  (allocated <= total per measure). A partial slice is typed by the planner in three amounts
  (`planning/application/AssignOrderRequest.java:25-49`, an omitted measure is 0); nothing is prorated
  and nothing is split automatically.
- The trip freezes `snapshot_max_weight_kg/volume_m3/pallets` at confirmation (`Trip.confirm`,
  `planning/domain/Trip.java:468-478`), guarded by `ck_trip_confirmed_is_complete` and
  `ck_trip_draft_has_no_snapshot` (V25).
- `HEURISTIC_V1` (`HeuristicPlanningEngine`) packs whole-order totals; `PLANNING_V2`
  (`PlanningEngineV2`) packs `order.pending()`. Both decide through `CapacityLimits.accommodates`.
  The rejection vocabulary is `PlanningProposal.UnplannedReason` (`EXCEEDS_LARGEST_VEHICLE`, ...).
- Rates: `rates/domain/CostUnit` = `KM, KG, M3, PALLET, STOP, HOUR, PERCENT`; the `VOLUME` component
  multiplies `SUM(assigned_volume_m3)` (`planning/infrastructure/TripCostingLookupAdapter.java:116`).

The consequence: 1,000 PLP-571 cups (10.5 x 10.5 x 17.5 cm, nest increment 1.2 cm, 100 per stack)
count as 1.93 m3 when they occupy about 0.15 m3, so the planner opens trucks that are not needed; and
a 2.9 m stack of PLP-1601 is accepted by a 2.5 m box.

## Decision

### 1. `tms.material_profile` - a dated copy, owned by the warehouse (D-02)

Module **`masterdata`** (it already owns locations, zones and routes). One row per company and
`material_code`; the EWM (`md_item`) is the owner and TMS never corrects it - when it disagrees, it
shows it (ADR-025 principle 1).

| Column | Type | Rule |
|---|---|---|
| `id`, `company_id` | uuid | `uq_material_profile_id_company`; FK company |
| `material_code` | text NOT NULL | `uq_material_profile_code (company_id, material_code)`; byte-for-byte the code the ERP puts on `transport_order_line.material_code` (no case folding, the same rule as `sourceSystem`) |
| `description` | text | informative |
| `source_system` | text NOT NULL | `^[A-Z0-9_]{2,40}$`, the producer (`EWM_EBIM`) |
| `source_updated_at` | timestamptz NOT NULL | when the owner changed it; a write older than the stored one is `STALE` (200, not applied) |
| `length_m`, `width_m`, `height_m` | numeric(8,4) | `> 0` or null; one unit in its loading orientation |
| `unit_weight_kg` | numeric(10,3) | `> 0` or null (same scale as the order line) |
| `unit_volume_m3` | numeric(10,4) | `> 0` or null; the declared unit volume |
| `stack_mode` | text | null = not declared; `NONE`, `STACKABLE`, `NESTABLE` |
| `stack_max_quantity` | integer | `> 0` or null; same meaning as EWM `md_item.stack_max_quantity` (units per stack) |
| `nest_increment_m` | numeric(8,4) | `> 0`; **required when `NESTABLE`** (`ck_material_profile_nest_increment`), null otherwise |
| `max_top_load_kg` | numeric(12,3) | `> 0` or null (crush limit of the bottom unit) |
| `orientation_locked` | boolean NOT NULL DEFAULT false | may it be turned 90 degrees on the floor |
| `updated_by_client` | uuid | composite FK to `integration_client (id, company_id)`; who wrote it |
| `version`, `created_at`, `updated_at` | | optimistic lock |

RLS `p_tenant_company_scope` (ADR-005), `GRANT SELECT, INSERT, UPDATE` to `tms_app` (no DELETE: a
profile the warehouse stops sending stays as the last known copy). Audit aggregate `MATERIAL_PROFILE`,
actions `MATERIAL_PROFILE_CREATED`, `MATERIAL_PROFILE_UPDATED` (enum + `ck_audit_event_action`,
`AuditVocabularyMigrationTest`). No rows are seeded.

Write path (contract in `WAREHOUSE_EXECUTION_V1.md` section 7.1):
`PUT /integration/v1/material-profiles/{materialCode}` and `POST /integration/v1/material-profiles/batch`
(<= 500), new scope **`integration.material:write`** (`IntegrationScope.MATERIAL_WRITE`, operations
`MATERIAL_PROFILE_UPSERT` / `MATERIAL_PROFILE_BATCH`, `ck_integration_client_scope_value` restated
with the full list, V55 pattern), `Idempotency-Key` through `IntegrationRequestExecutor` (same key +
other body = 409 `idempotency-key-reused`). Semantics: **absent field keeps, explicit `null` clears**
(the PUT is a merge on the field set the request names; the EWM side sends the full set anyway).

Read path: `GET /api/v1/masterdata/material-profiles` (search, page) and `/{id}`, permission
**`masterdata.material-profile:read`** seeded in `tms.permission` and granted to ORGANIZATION_ADMIN,
COMPANY_ADMIN, PLANNER, VIEWER (V46 shape). Screen "Perfiles de material" under Masters, read-only,
MUI, shows source and `source_updated_at`.

Orders reach the profile through a port, never the table: `shared.reference.MaterialProfileLookupPort`
(`Map<String, MaterialProfileSnapshot> byCodes(UUID companyId, Set<String> codes)`) implemented in
`masterdata`. `ModuleBoundaryTest` stays green by construction.

### 2. Snapshot on the order line (V59)

`tms.transport_order_line` gains nullable columns: `unit_length_m`, `unit_width_m`, `unit_height_m`,
`stack_mode`, `stack_max_quantity`, `nest_increment_m`, `max_top_load_kg`, `orientation_locked`,
`physical_source` (`PROFILE` | `ORDER_LINE` | null) and `material_profile_version` (bigint, the copied
version). Same CHECKs as the profile, plus `ck_transport_order_line_physical_source` (a source iff any
geometry column is set).

Copy rule, applied in `OrderService` (UI), `OrderIntakeService` (`/integration/v1/orders[/batch]`) and
`OrderImportService` when lines are created or replaced:

1. If the ERP sent **any** geometry field on the line (`lengthM`, `widthM`, `heightM`, `stackMode`), the
   **line's block wins as a whole** (`physical_source = ORDER_LINE`) - D-02. A field-by-field merge
   would mix an ERP height with a warehouse nest increment and produce a stack nobody measured.
2. Otherwise, if a profile exists for `material_code`, its block is copied (`PROFILE`, with version).
3. `unit_weight_kg` / `unit_volume_m3` keep today's rule (the line's value) and are **filled from the
   profile only when the line omits them**. This changes totals only for lines created after a profile
   exists, and the order's `totals_source` logic (`OrderTotals`) is untouched.
4. A later profile change does **not** rewrite existing lines (snapshot). Re-applying a profile to open
   orders is a future explicit action, not a side effect.

`OrderUpsertRequest.LineRequest` and `OrderRequest.OrderLineRequest` gain the optional fields
`lengthM, widthM, heightM, stackMode, stackMaxQuantity, nestIncrementM, maxTopLoadKg, orientationLocked`.
A v1 caller that sends none behaves exactly as today.

### 3. `LoadGeometryCalculator` - pure, by footprint (D-05)

`com.ebim.tms.shared.geometry.LoadGeometryCalculator` (shared, because orders, planning and rates all
need the same arithmetic; no Spring, no clock, no repository). Input: a line's quantity and physical
block, a usable height `H` and an optional vehicle width. Output `LineGeometry(status, reason, perStack,
stacks, stackHeightM, lastStackHeightM, effectiveVolumeM3, floorAreaM2)`.

```
heightCap   = STACKABLE: floor(H / h)      NESTABLE: floor((H - h) / dh) + 1      NONE: 1
weightCap   = maxTopLoadKg ? floor(maxTopLoadKg / unitWeightKg) + 1 : unbounded
k           = min(stackMaxQuantity ?: unbounded, heightCap, weightCap)            (k >= 1)
stacks      = ceil(quantity / k)
height(n)   = STACKABLE: n*h     NESTABLE: h + (n-1)*dh     NONE: h
effVolume   = (stacks-1) * L*W*height(k) + L*W*height(quantity - (stacks-1)*k)    (exact last stack)
floorArea   = stacks * L * W
loadingM    = floorArea / (vehicleWidth * fillFactor)                              (only with a width)
```

- `h > H` -> status `DOES_NOT_FIT`, reason `ITEM_TALLER_THAN_VEHICLE`. Never "fits with a warning".
- Missing L, W, h, `stack_mode`, or `nest_increment_m` for NESTABLE, or `k` unbounded (no
  `stackMaxQuantity` and no `H`) -> status `UNMEASURED` with the missing field as reason. **Nothing is
  estimated** (EWM rule 199): the line keeps its declared volume and is counted.
- `orientation_locked=false` lets the floor test try W x L when L x W does not fit the vehicle width;
  it never changes the area. Quantity is in the line's unit; a line whose `uom` is not a unit count
  (`KG`, `M3`, ...) is `UNMEASURED` (`UOM_NOT_COUNTABLE`): no conversion is invented.
- Fixed reference case (unit test): PLP-571, 0.105 x 0.105 x 0.175 m, dh 0.012 m (test assumption),
  max 100, H 2.40 m -> k = 100, stack height 1.363 m, 1,000 u = 10 stacks, effective volume
  0.1503 m3 (naive 1.93 m3), floor 0.1103 m2.
- Rounding: geometry in `numeric` with HALF_UP to 4 decimals for m3/m2, 3 for metres.

### 4. Order geometry and the planning volume (V62)

Line columns: `geometry_status` (`MEASURED`|`UNMEASURED`|`DOES_NOT_FIT`|null), `geometry_reason`,
`stacks`, `stack_height_m`, `effective_volume_m3`, `floor_area_m2`.

Order columns:

| Column | Meaning |
|---|---|
| `planning_volume_m3` numeric(14,4) NOT NULL | what capacity counts: SUM(effective volume of measured lines) + SUM(naive volume of the rest); equals `total_volume_m3` when no line is measured (backfill = `total_volume_m3`) |
| `floor_area_m2` numeric(12,4) NOT NULL DEFAULT 0 | sum of measured lines |
| `max_stack_height_m` numeric(6,3) | max over measured lines (never summed) |
| `geometry_unmeasured_lines` integer NOT NULL DEFAULT 0 | counted, shown |
| `geometry_reference_height_m` numeric(5,2) | the `H` used when the lines were computed |
| `allocated_floor_area_m2` numeric(12,4) NOT NULL DEFAULT 0 | V37 ledger running total |

`total_volume_m3` (naive) stays as the audit figure. `ck_transport_order_not_over_allocated` is
restated in V62 to compare `allocated_volume_m3 <= planning_volume_m3` and
`allocated_floor_area_m2 <= floor_area_m2` (weight and pallets unchanged; existing rows satisfy it
because the backfill makes planning = total). `shared.reference.OrderAmounts` gains `floorAreaM2`
and `maxStackHeightM`, and its `volumeM3` becomes the planning volume - so V37's
`total / allocated / pending` arithmetic keeps one meaning for every measure.

Reference height `H` at order time: `company_settings.reference_load_height_m` (nullable, V62), falling
back to the property `tms.planning.geometry.default-reference-height-m` (2.40 in `application.yml`,
overridable). No fixed number in code or SQL.

### 5. Capacity gains two dimensions: floor and height

- `CapacityLimits` becomes `(maxWeightKg, maxVolumeM3, maxPallets, maxFloorAreaM2, maxStackHeightM,
  loadWidthM)`. `maxFloorAreaM2 = length_m x width_m x fillFactor`; `maxStackHeightM = height_m`;
  null when the vehicle type has no such dimension = **unlimited** (today's behaviour for every
  vehicle type without L/W/H). A zero is impossible (`> 0` CHECKs), so the null/zero table of
  `CAPACITY_MODEL.md` holds unchanged.
- `fillFactor`: `vehicle_type.load_floor_fill_factor` (V62, nullable, `0 < x <= 1`), else
  `company_settings.load_floor_fill_factor`, else property `tms.planning.geometry.default-floor-fill-factor`
  (0.85).
- `CapacityLoad` becomes `(weightKg, volumeM3, pallets, floorAreaM2, maxStackHeightM, orderCount)`;
  `plus` sums the first four and takes the **max** of the height.
- `accommodates` adds `within(floorAreaM2, maxFloorAreaM2)` and `within(maxStackHeightM, maxStackHeightM)`.
  `PlanningCapacityService.requireWithinCapacity` names every failed dimension, now five.
- `EffectiveCapacityResolver.resolve` returns `EffectiveCapacity(maxWeightKg, maxVolumeM3, maxPallets,
  lengthM, widthM, heightM, floorFillFactor)`; `VehicleCapacityReference` carries the same; there is
  still exactly one resolver (OWNERSHIP_MATRIX "Capacity checks"). Vehicles have no L/W/H override in V9
  and none is added.
- **Re-stacking per vehicle.** When a whole-order assignment is checked against a vehicle whose
  `height_m` is lower than the order's `geometry_reference_height_m`, planning recomputes the order's
  geometry with the vehicle's `H` through `shared.reference.OrderGeometryPort.restack(companyId,
  orderIds, heightM)` (implemented in `orders`, reading the line snapshots) before deciding. A line with
  `h > H` makes the order not fit that vehicle (`ITEM_TALLER_THAN_VEHICLE`).
- **Partial slices.** TMS splits by typed amounts, not by line quantities (V37), so a slice cannot be
  "recomputed" without a line ledger that does not exist. Decision: `AssignOrderRequest` gains
  `floorAreaM2` with the same rule as the other three (omitted = 0; `SplitAssignDrawer` prefills the
  pending figure and caps it), and the slice carries the order's `max_stack_height_m` (a slice is still
  made of the order's stacks). Recorded in `DECISIONS_LOG.md` as a deviation from plan 02 section 3.4.

### 6. Ledger and trip snapshot (V62)

- `tms.trip_order_assignment` gains `assigned_floor_area_m2 numeric(12,4) NOT NULL DEFAULT 0` and
  `assigned_stack_height_m numeric(6,3)`; `ck_trip_order_assignment_amounts_nonnegative` is restated to
  include the floor. `TripOrderAssignmentRepository.TripLoad` adds `floorAreaM2` (SUM) and
  `maxStackHeightM` (MAX).
- `tms.trip` gains `snapshot_max_floor_area_m2 numeric(10,3)`, `snapshot_max_stack_height_m numeric(6,3)`,
  `snapshot_load_width_m numeric(6,3)` (each `> 0` or null). `ck_trip_draft_has_no_snapshot` is restated
  to cover them; `ck_trip_confirmed_is_complete` is **not** widened (a vehicle type without dimensions
  still confirms). `Trip.confirm` gains the three values; `CapacityLimits.ofSnapshot` reads them.

### 7. Engines and KPIs

Both engines keep calling `accommodates`; nothing else changes in their loops. `HEURISTIC_V1` uses the
whole order's geometry, `PLANNING_V2` the pending one. Additions:

- `PlanningProposal.UnplannedReason.ITEM_TALLER_THAN_VEHICLE` (an order whose tallest unit exceeds every
  vehicle), next to `EXCEEDS_LARGEST_VEHICLE`.
- `PlanningKpis` gains `floorUtilizationPercent`, `heightUtilizationPercent` (same averaging) and each
  proposed trip a `bindingDimension` (`WEIGHT|VOLUME|FLOOR|HEIGHT|PALLETS`, the highest percentage).
- Acceptance test: two orders of nestables that need two trucks by naive volume fit one by geometry.

### 8. Rates: `LOADING_METER` (V63)

`CostUnit.LOADING_METER`, `RateComponent.LOADING_METERS` (rate card column `amount_per_loading_meter
numeric(14,4)`, `ck_trip_cost_component_component` and `_unit` restated). `CostInputs` gains
`loadingMeters = SUM(assigned_floor_area_m2) / (snapshot_load_width_m x fill)` - `NOT_CALCULABLE` with a
reason when the width is unknown. `VOLUME` keeps reading `SUM(assigned_volume_m3)`, which is now the
planning volume; the component's `quantity_source` says which (`EFFECTIVE_GEOMETRY` when any measured
line contributed, else `ORDER_DECLARED_TOTALS`).

### 9. Warehouse dispatch geometry (V60 receives, phase 45 compares)

The SLS may carry `orders[].stacks`, `effectiveVolumeM3`, `loadingMeters` (ADR-025). TMS stores them on
`tms.external_dispatch_order` (V60) and compares them with the plan as **informative** discrepancies
(`GEOMETRY_VARIANCE`, severity `INFO`): never blocking, never a 4xx.

### 10. UI

Order form: "Ocupa: 10 pilas · 0,15 m³ efectivos · 0,11 m² de piso · alto 1,36 m" and the unmeasured
count. Planning board (`TripCard` `MiniCapacity`) and trip drawer (`CapacityBar`, `CapacityUnit` gains
`floor` and `height`). Vehicle type drawer edits L/W/H and fill factor. MUI only.

## What this ADR does not do

- No 3D loading and no bin-packing: a footprint estimate. A 2D shelf pass can live behind `PlanningEngine`.
- No mixed-material stacks (as EWM: `allow_mixed_materials=false`).
- No unit conversion without a declared unit.
- No change to occupancy or anything in the warehouse beyond what ADR-025 states.

## Consequences

- Every existing order, vehicle type and trip behaves as today until a profile, a line geometry or a
  vehicle dimension exists: null floor/height limits are unlimited and `planning_volume_m3 = total_volume_m3`.
- `CAPACITY_MODEL.md` becomes v2 (five dimensions) and its stale statements on partial assignment
  (whole orders only; "an order id and nothing else") are corrected in the same phase (40).
- One more port read per planning candidate set (batched by order ids), acceptable at 10k orders/day.

## Implementation notes (phase 20, 2026-10-18)

Sections 1-2 are implemented with **V59**; `docs/domain/MATERIAL_PROFILE_V1.md` describes the result.
Four refinements, recorded in `docs/roadmap/2026-10-enterprise/DECISIONS_LOG.md`:

- The permission is `masterdata.material_profile:read`: the permission catalogue's
  `ck_permission_resource_shape` does not allow a hyphen in the resource.
- Unit weight/volume are filled from the profile only when the order declares no total for that measure
  and the line's unit is a count, so no payload valid before V59 becomes invalid.
- On an edit, a line at the same position naming the same material keeps its stored block (lines are
  rewritten as a set), which is what makes rule 4 hold for edits and redeliveries.
- Any of the seven physical fields makes the ERP's block win (still as a whole), not only L/W/H/mode.


## Implementation notes (phase 40 cycle 1, 2026-10-18)

Sections 3, 4, 5 (except re-stacking), 6 and 7 are implemented with **V62**;
`docs/domain/LOAD_GEOMETRY_V1.md` describes the result and `CAPACITY_MODEL.md` is v2. Refinements,
recorded in `docs/roadmap/2026-10-enterprise/DECISIONS_LOG.md`:

- **Re-stacking per vehicle is deferred to cycle 2.** Orders are stacked against the reference `H`;
  a vehicle lower than the stacks is refused on height (conservative), one lower than a unit as
  `ITEM_TALLER_THAN_VEHICLE`. The order stores `max_unit_height_m` to tell the two apart without
  reading lines. `OrderGeometryPort` does not exist yet.
- When the volume was only *declared*, the planning volume keeps the declaration unless every line
  was measured (adding effective volumes to a declaration that already covers them would count twice).
- `planning_volume_m3` is NOT NULL through a `BEFORE INSERT` trigger that copies `total_volume_m3`
  when a writer names none (the backfill's rule), not through a DEFAULT.
- Stack heights are `numeric(9,3)` on the line, the order and the ledger: V59 accepts unit heights a
  `numeric(6,3)` could not hold.
- Fulfilment compares deliveries with the order without the floor (`OrderAmounts.withoutGeometry`).
- UI (section 10) is cycle 2.

## Implementation notes (post-review fixes, 2026-10-18)

- **Re-stacking per vehicle is implemented**, without `OrderGeometryPort`: `OrderPlanningPort`
  gains `geometryProfilesOf(orderIds, companyId)` (orders with a measured line, lines fetched in one
  query) and `planning.application.VehicleLoads` re-stacks with the same `shared.geometry.OrderGeometry`
  `TransportOrder.applyLines` uses. Applied to every vehicle with a height, lower *or* taller than the
  reference (a taller one takes the same goods on less floor). The ledger keeps the reference figures;
  only the vehicle's view of them changes. Slices are re-stacked in proportion to their floor share.
- **Partial slices: an omitted `floorAreaM2` is derived**, no longer zero - the pending floor in the
  largest proportion the stated measures take of theirs (`AssignOrderRequest.amountsAgainst`). With
  zero, the floor stayed pending and a split order never reached `PLANNED`.
- The calculator checks a unit taller than `H` right after the three measures, so
  `ITEM_TALLER_THAN_VEHICLE` wins over an incomplete crush limit or stack mode; non-positive measures
  are missing, bounds are capped at the line's units before they become a long, a count beyond an
  `int` is `QUANTITY_OUT_OF_RANGE`, and figures are rounded up so a tiny unit never rounds to zero.

## Implementation notes (phase 45 cycle 1, 2026-10-18)

Sections 8 and 9 are implemented with **V63** (`V63__rate_loading_meter.sql`); `docs/domain/RATE_ENGINE_V2.md`
sections 4.1-4.2 describe the rate side and `WAREHOUSE_EXECUTION_V1.md` section 7.3 the comparison.
Refinements, recorded in `docs/roadmap/2026-10-enterprise/DECISIONS_LOG.md`:

- **Two reasons, not one.** A non-calculable loading-metre line says `FLOOR_AREA_UNKNOWN` (nothing on
  the trip measured) or `LOAD_WIDTH_UNKNOWN` (no width in the snapshot nor on the vehicle type). Both
  are new values of `ck_trip_cost_component_reason`.
- **Width: snapshot first, then the vehicle type.** The fill factor is read from fleet's resolver at
  estimation time (the snapshot does not freeze it).
- **`EFFECTIVE_GEOMETRY` when the ledger has floor area.** Floor exists only on measured lines, so it is
  the signal that a measured line contributed to the planning volume.
- **Proposal pricing uses the planning volume too** (`ProposalPricer`), so a proposal and the trip it
  becomes are priced on the same cubic metres.
- **Comparison (section 9).** `DispatchReconciler.Code.GEOMETRY_VARIANCE` (`INFO`), one per measure
  (`uom` = `STACKS`, `M3`, `LDM`), only when both sides state the figure. Stacks exact; effective volume
  and loading metres with a 5% relative tolerance. Planned stacks and effective volume are the sums of
  the order's lines, only for a whole order with every line measured; planned loading metres come from
  the assignment's floor (whole or share). It never changes the order's match result or the verification.
