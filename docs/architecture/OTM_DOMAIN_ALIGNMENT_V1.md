# OTM Domain Alignment V1

- Status: Informative (not an ADR; introduces no decision and changes no behaviour)
- Date: 2026-08-20
- Scope: maps the domain that exists in this repository today onto the conceptual vocabulary
  of Oracle Transportation Management (OTM).

## Why this document exists

OTM is used here as a **conceptual reference for naming and decomposition only**. It is a
mature vocabulary for the transportation domain, and borrowing its concept boundaries makes
later integration and later hiring conversations cheaper. It is not a target architecture.

Three rules govern every entry below.

1. **TMS stays deliberately simpler than OTM.** OTM separates concepts because it must serve
   global 4PLs with rating, tendering, settlement and multi-leg optimization. Where TMS has no
   such requirement, one TMS concept legitimately covers several OTM ones, and that is recorded
   as a decision, not as a deficiency.
2. **A concept is only CURRENT if it exists end to end** - table, entity, service, endpoint and
   UI - not merely as a table.
3. **Nothing here authorises implementation.** A GAP or FUTURE row is an observation. Building
   it still needs a business requirement and, where it changes a boundary, an ADR.

## Status vocabulary

| Status | Meaning |
|---|---|
| CURRENT | Exists end to end (DB -> service -> API -> UI) and is used by the product |
| PARTIAL | Exists, but materially narrower than the OTM concept, or modelled but not yet consumed by the flows that would give it meaning |
| GAP | Not modelled, and its absence is felt by a flow that already exists |
| FUTURE | Not modelled, deliberately deferred; no current requirement |

## 1. Summary table

| OTM concept | TMS concept today | Where it lives | Status |
|---|---|---|---|
| Location | `Origin` + `Destination` (two separate entities) | `tms.origin`, `tms.destination` | PARTIAL |
| Order Release | `TransportOrder` + `TransportOrderLine` | `tms.transport_order`, `tms.transport_order_line` | CURRENT |
| Shipment | `Trip` | `tms.trip` | PARTIAL |
| Shipment Stop | `TripStop` | `tms.trip_stop` | PARTIAL |
| Service Provider | `Carrier` | `tms.carrier` | CURRENT |
| Equipment Group | `VehicleType` | `tms.vehicle_type` | CURRENT |
| Equipment | `Vehicle` | `tms.vehicle` | CURRENT |
| Itinerary / Route | `Route` + `RouteStop` | `tms.route`, `tms.route_stop` | PARTIAL |
| Calendar / Frequency | `Frequency` + `FrequencyWeeklyRule` + `FrequencyException` | `tms.frequency`, `tms.frequency_weekly_rule`, `tms.frequency_exception` | PARTIAL |
| Order Movement / Leg | - | - | FUTURE |
| Rate / Cost / Settlement | - | - | FUTURE |
| Tender / Tender Response | - | - | FUTURE |
| Driver | - | - | FUTURE |
| Shipment Status / Tracking Event | - | - | FUTURE |

## 2. Concept by concept

### 2.1 Location -> `Origin` and `Destination` (PARTIAL)

OTM models a single `Location` entity, and the *role* a location plays (source, destination,
depot, carrier address) is contextual rather than intrinsic.

TMS splits this into two tables with different shapes:

| | `tms.origin` | `tms.destination` |
|---|---|---|
| Type enum | `WAREHOUSE`, `DISTRIBUTION_CENTER`, `PLANT`, `HUB` | `CUSTOMER`, `STORE`, `BRANCH`, `HUB`, `DISTRIBUTION_CENTER` |
| Address | single `address` string | `address`, `addressReference`, `district`, `province`, `department`, `country` |
| Coordinates | `latitude`, `longitude` (`numeric`) | `latitude`, `longitude` (`numeric`) |
| Zone | - | `zoneId` |
| Service time | - | `serviceTimeMinutes` |
| Time zone | `timeZone` | - |

**Why PARTIAL rather than CURRENT.** Both are fully implemented end to end, but three
divergences from the OTM concept are real and will matter later:

1. **The split is asymmetric, and `HUB` / `DISTRIBUTION_CENTER` appear on both sides.** A
   physical DC that both ships and receives must be created twice, as two rows, with two codes
   and two independently maintained addresses. Nothing links them.
2. **Coordinates are plain `numeric` columns, not PostGIS geometry.** PostGIS is installed by
   V1 and is therefore available, but no spatial type or spatial index is in use, so no
   proximity, containment or distance query is possible today.
3. **Geocoding is not implemented.** Latitude and longitude are hand-entered. There is no
   `VITE_GOOGLE_MAPS_API_KEY` in `frontend/tms-web/src/shared/config/env.ts` and no geocoding
   call anywhere.

**Deliberate simplification to keep.** The two-table split is not obviously wrong: origins are
few, operationally owned and time-zone bearing, while destinations are many and carry
delivery-specific attributes. A unified `Location` with role flags would be closer to OTM and
strictly more complex. This should only be revisited if the duplicate-DC problem above becomes
a real operational cost.

### 2.2 Order Release -> `TransportOrder` (CURRENT)

`tms.transport_order` carries `orderNumber`, `originId`, `destinationId`, `customerName`,
`customerReference`, `serviceDate`, `priority`, a requested time window
(`requestedWindowStart` / `requestedWindowEnd`), `status` and an optimistic-locking `version`.
`tms.transport_order_line` carries material, quantity, uom, unit and line weight, and pallet
quantity.

Status enum: `NOT_READY` -> `READY_FOR_PLANNING` -> `PLANNED`, plus `CANCELLED`.

This is the closest match in the whole model. OTM's Order Release is richer (ship units,
packaged item hierarchy, flexible commodity attributes, involved-party roles), but the TMS
shape covers what planning actually consumes: where from, where to, when, how much weight, how
many pallets.

**Already present and worth noting:** `externalSource` + `externalReference` with a uniqueness
constraint per company. That is the idempotency key an inbound integration needs, and it exists
before the integration does.

### 2.3 Shipment -> `Trip` (PARTIAL)

`tms.trip` is the planned execution unit: `planningRunId`, `tripNumber`, `vehicleId`,
`carrierId`, `plannedDepartureAt`, `status`, and a capacity snapshot
(`snapshotMaxWeightKg`, `snapshotMaxPallets`, `capacitySnapshotAt`).

The capacity snapshot is a genuinely good decision worth preserving: a trip records the
capacity it was planned against, so later edits to a vehicle type cannot retroactively make a
confirmed trip look over- or under-loaded.

**Why PARTIAL.**

- Status is only `DRAFT` -> `CONFIRMED`, plus `CANCELLED`. There is no execution lifecycle -
  no dispatched, in-transit, delivered or closed state. A confirmed trip is the end of the
  modelled world.
- No cost, no rate, no tender, no service level.
- No `routeId`. A trip is not associated with the `Route` master data at all (see 2.7).
- `plannedDepartureAt` is the only time on the trip; there is no planned arrival or completion.

### 2.4 Shipment Stop -> `TripStop` (PARTIAL)

`tms.trip_stop` carries `destinationId`, `sequence` and a service window
(`serviceWindowStart` / `serviceWindowEnd`).

Stops are derived, not hand-built: `TripStopPlanner` collapses the active assignments to one
stop per distinct destination and sets the window to the **envelope** (earliest start, latest
end) of the requested windows of the orders delivered there. `Trip.syncStops` then applies that
while preserving any manual reordering.

The envelope choice is documented in the code as deliberate: with no time-feasibility solver in
V1, claiming an intersected feasible slot would be inventing routing that is deferred by
decision. That reasoning is sound and should be preserved.

**Why PARTIAL.**

- Stops are delivery stops only. There is no pickup stop type, so an origin does not appear in
  the stop list and a multi-pickup trip cannot be represented.
- No planned arrival or departure time per stop, no travel distance or duration between stops,
  no dwell time applied from `destination.serviceTimeMinutes` (the column exists and is
  unused by planning).
- No per-stop execution status.

### 2.5 Service Provider -> `Carrier` (CURRENT)

`tms.carrier` carries `code`, `businessName`, `taxIdType` + `taxIdValue` (unique per company),
and contact fields. Implemented end to end.

Simplified relative to OTM by decision: no carrier contracts, rates, service-level commitments,
capacity commitments, tendering sequence or scorecard. For a TMS where carrier selection is a
manual planner choice, `Carrier` as a master record is the proportionate model.

### 2.6 Equipment Group / Equipment -> `VehicleType` / `Vehicle` (CURRENT)

This pair maps onto OTM cleanly and is the best-aligned area of the model.

`tms.vehicle_type` is the capacity and characteristics template: `maxWeightKg`, `maxPallets`,
`lengthM` / `widthM` / `heightM`, `bodyType` (`DRY_VAN`, `REFRIGERATED`, `FLATBED`, `TANKER`,
`CONTAINER`, `CURTAIN_SIDER`), `temperatureControlled` with min/max celsius, and `axles`.

`tms.vehicle` is the physical unit: `licensePlate`, `carrierId`, `vehicleTypeId`,
`availabilityStatus` (`AVAILABLE`, `IN_MAINTENANCE`, `OUT_OF_SERVICE`), plus
`maxWeightOverrideKg` and `maxPalletsOverride`.

The override-with-fallback rule is resolved in one place (`EffectiveCapacityResolver`) and unit
tested without a database. Keep that.

### 2.7 Itinerary / Route -> `Route` + `RouteStop` (PARTIAL - modelled but not consumed)

`tms.route` carries `code`, `name`, `originId`, `zoneId`, `frequencyId`,
`referenceDistanceKm` and `referenceDurationMinutes`; `tms.route_stop` carries an ordered
`destinationId` sequence.

Structurally this is a good Itinerary analogue: a named, repeatable origin-to-ordered-stops
template with a frequency attached.

**This is the most important finding in this document.** Route is fully built as master data -
migration, entity, service, controller, drawer UI, 13 constraint tests and 13 API tests - but
**nothing consumes it**. A search across `orders`, `planning` and `shared` finds no reference to
`routeId`, `RouteRepository` or route data outside the `masterdata` module itself. `Trip` has no
`routeId` column. `TripStopPlanner` builds the stop list purely from order destinations in
assignment order and never consults a route.

So today a planner maintains routes in one screen and plans trips in another, with no
connection between them. The reference distance and duration are captured and never read.

This is not a defect to fix blindly - it is a **product decision that has not been made yet**.
The two coherent directions are:

- *Route as a planning template*: creating a trip from a route pre-seeds its stop sequence, and
  `referenceDistanceKm` / `referenceDurationMinutes` become the baseline for planned times.
- *Route as a reporting dimension only*: trips reference a route for grouping and analysis, but
  planning stays free-form.

Either needs a decision before more is built on top. Recorded here so a later job does not
silently pick one.

### 2.8 Calendar / Frequency -> `Frequency` (PARTIAL - same disconnection)

`tms.frequency` is a named service calendar; `tms.frequency_weekly_rule` carries per-weekday
`enabled`, `cutoffTime` and `leadTimeDays`; `tms.frequency_exception` carries a date with a
`serviceOverride` boolean and a note.

The weekly-rule-plus-exception shape is a sound simplification of OTM's calendar model, and
cutoff time with lead days is exactly the pair an order-intake rule needs.

**Why PARTIAL.** Like Route, Frequency is reachable only through Route
(`route.frequencyId`) and is consumed by nothing. No order-intake rule, no planning-date
validation and no availability calculation reads it. `cutoffTime` and `leadTimeDays` are
captured and never applied.

### 2.9 Deliberately deferred (FUTURE)

These OTM concepts are absent, and their absence is correct for V1. Listed so that "not built"
is visibly distinct from "not considered".

| OTM concept | Why deferred |
|---|---|
| Order Movement / Leg | Requires multi-leg planning; V1 is single-vehicle, single-trip. Adding it changes the planning model fundamentally. |
| Rate / Cost / Settlement | No freight-settlement requirement. This is the largest single OTM subsystem; adding it early would dominate the model. |
| Tender / Tender Response | Carrier assignment is a manual planner decision today. Tendering needs carrier-facing access, which needs the integration credential model that does not exist yet. |
| Driver | Not modelled. `Vehicle` carries no driver, and there is no driver entity, licence or hours-of-service concept. |
| Shipment Status / Tracking Event | Explicitly deferred by `CLAUDE.md` along with GPS/telematics and live map tracking. |
| Optimization / Sourcing | `PlanningMode` declares `AUTOMATIC`, but only `MANUAL` is reachable. OR-Tools and route optimization are deferred by decision. |

Note that `PlanningMode` declares two values (`MANUAL`, `AUTOMATIC`) while only `MANUAL` is
constructible - `PlanningRun` hard-assigns `PlanningMode.MANUAL` and nothing else ever writes
`mode`. `V11`'s `ck_planning_run_mode` check accepts both strings, so the column is ready for a
future automatic run without one existing. This is good design: the extension point exists
without the complexity behind it. It does mean `AUTOMATIC` is currently unreachable vocabulary
rather than behaviour, and a reader should not infer that an automatic mode is implemented.

## 3. Cross-cutting observations

### 3.1 Tenancy is modelled consistently and is not an OTM concept

Every business table carries `company_id`, every root repository method is
`...AndCompanyId(...)`, and V13 adds PostgreSQL RLS policies keyed on
`tms.current_company_id()` for the non-owner `tms_app` role. Child tables
(`frequency_weekly_rule`, `frequency_exception`, `transport_order_line`) inherit the tenant
through an `EXISTS` against their parent rather than denormalising `company_id`.

OTM handles multi-tenancy through domains; the TMS Organization/Company model is a different
and simpler decomposition. No alignment work is needed here.

### 3.2 Audit columns are universal

Every business table carries `createdAt`, `updatedAt`, `createdBy`, `updatedBy`. There is no
audit *event* table - `com.ebim.tms.audit` contains only a `package-info.java`. Row-level
attribution exists; a change history does not.

### 3.3 Capability declared without an implementation

`Capability.TRANSPORT_MONITOR_VIEW` (backed by `Permission.MONITORING_TRANSPORT_READ`) is
defined in the authorization catalogue, but no monitoring controller, service or page exists.
It is a placeholder, and harmless, but it means the permission catalogue currently over-states
what the product does.

## 4. What this document does not do

It proposes no migration, renames nothing and moves no boundary. Any change to Route or
Frequency consumption (2.7, 2.8) changes how planning works and therefore needs its own ADR,
per the rule in `CLAUDE.md` that an implementation must not silently diverge from the
architecture of record.

## 5. References

- `docs/architecture/TMS_ARCHITECTURE_V1.md` - architecture of record
- `docs/architecture/ADR-003-multitenancy-company-scope.md` - Organization/Company tenancy
- `docs/architecture/ADR-005-tenant-rls-runtime-role.md` - tenant RLS
- `docs/domain/ORDER_LIFECYCLE_V1.md` - order status transitions
- `docs/domain/PLANNING_MANUAL_V1.md` - manual planning model
- `docs/domain/CAPACITY_MODEL.md` - capacity resolution and snapshots
- `docs/database/DATA_MODEL.md` - table-level detail
