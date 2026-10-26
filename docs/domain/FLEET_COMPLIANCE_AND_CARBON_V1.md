# Fleet compliance documents (V72) and carbon footprint (V73)

Backlog items 7 and 9 of the October 2026 evaluation. Code in English, screens in Spanish/English.

## 1. Fleet compliance documents (V72)

**Tables** (owned by `fleet`): `tms.fleet_document` (one paper of one vehicle *or* one driver -
two typed FK columns, never a polymorphic id), `tms.fleet_document_policy` (per company and type:
`mandatory`, `enforcement` BLOCK|WARN; sparse - no row = the type's default) and
`tms.fleet_compliance_settings` (per company: `expiry_alert_days`, default 30). RLS tenant policy +
restrictive carrier policy (a portal session sees none of it); no DELETE grant - a paper is
deactivated, never deleted. Permissions `fleet.document:read|manage` and
`fleet.document_policy:manage`, folded into `FLEET_VIEW` / `FLEET_MANAGE`.

**Types** (`shared.reference.FleetDocumentType`, international names, local names in the labels):
vehicle - `REGISTRATION`, `TECHNICAL_INSPECTION` (RTV/CITV), `MANDATORY_INSURANCE` (SPPAT/SOAT),
`OPERATING_PERMIT` (ANT/MTOP/MTC), `CARGO_INSURANCE`, `VEHICLE_OTHER`; driver -
`MEDICAL_CERTIFICATE`, `DEFENSIVE_DRIVING_COURSE`, `DRIVER_OTHER`.

**Status** (`FleetDocumentStatus`, derived on read, never stored): `VALID`, `EXPIRING_SOON` (within the
company window, still valid), `EXPIRED` (last valid day, inclusive, has passed), `UNRECORDED` (no
expiry date known).

**The rule** (`fleet.domain.FleetComplianceEvaluator`, pure). Per type, the documents on file are one
piece of evidence and the best wins (a renewal clears the expired paper beside it):

| Situation | Finding |
|---|---|
| any document valid on the day | none (about to expire is still valid) |
| only undated documents | `UNRECORDED`, warning - only if the type is mandatory |
| latest expiry has passed | `EXPIRED`, **blocking** when enforcement = BLOCK, warning otherwise |
| no document | `UNRECORDED`, warning - only if the type is mandatory |

Not knowing is never expired (V26's licence rule). Defaults: **nothing mandatory**, the four legal
vehicle papers BLOCK when on file and expired, the rest WARN - so a company that never records a
paper sees no change.

**Where it is enforced** (through `shared.reference.FleetCompliancePort`, implemented by
`FleetComplianceService`):

- `TripService` - creating a trip with a vehicle, swapping the vehicle, naming the driver: a blocking
  finding on the **planning date** is a 400 naming the paper and the plate/driver.
- `AutoPlanningService` - a vehicle with a blocking finding is not offered to the engine.
- `DispatchReadiness` - new check `COMPLIANCE_DOCUMENTS` / blocker `COMPLIANCE_DOCUMENT_EXPIRED`,
  judged **today** (the gate refuses; an external dispatch records it as a discrepancy).
- `TripDetailView.compliance` - every finding (blocking or not) shown on the trip workspace.
- Work assignments - `ResourceRejectionReason.COMPLIANCE_DOCUMENT_EXPIRED|UNRECORDED`; conflicts now
  carry `blocking`, `feasible` ignores warnings and `confirm` refuses only blocking ones. Whole-day
  findings are evaluated even on an empty day.

**API** `/api/v1/fleet/documents` (list with `subjectType`, `vehicleId`, `driverId`, `documentType`,
`status`, `active`; soonest expiry first), `/summary` (counts + ageing buckets), `/missing`,
`/subjects/{VEHICLE|DRIVER}/{id}`, CRUD + activate/deactivate, `/api/v1/fleet/document-policy`
(GET/PUT). **UI** «Documentos de flota» (`/fleet/documents`), a «Documentos» section in the vehicle
and driver drawers, the policy drawer, findings on the trip workspace, warnings in «Días de trabajo».

## 2. Carbon footprint (V73)

`vehicle_type.fuel_type` (descriptive) and `vehicle_type.emission_factor_kg_co2e_per_km` (nullable,
`0 < f <= 50`). **No factor, no figure**: "sin datos", never zero; TMS ships no default factor.

`kg CO2e = distance x factor`, where the distance is the trip workspace's own «Recorrido»
(`TripRouteMetrics`, V38, with its provenance: an estimated distance makes an estimated footprint;
unmeasured legs are counted). Split between the trip's orders with **`CostAllocator` on the company's
cost-allocation basis** (V68), so an order's share of the carbon is its share of the cost.
Computed on read, never stored.

Lives in `settlement` beside cost to serve: `TripEmissionService`, `EmissionCalculator` (pure),
`EmissionController` - `GET /api/v1/settlement/emissions/trips/{tripId}`,
`GET /api/v1/settlement/emissions?from&to&groupBy=CUSTOMER|DESTINATION|ROUTE` (trips not cancelled by
planning date, range <= 93 days, <= 2000 trips, trips without a figure counted by reason) and
`/export` (CSV/JSON). Planning answers `TripEmissionSourcePort`; fleet answers
`EmissionFactorLookupPort`. Permission `settlement.emission:read` (four office roles), capability
`EMISSIONS_VIEW`. **UI** «Huella de carbono» card on the trip workspace, section in «Reportes y KPIs»,
fuel/factor fields in the vehicle type drawer.

## 3. Demo seed (PLAPASA)

`supabase/seeds/plapasa_demo_seed.sql` §4b: five-type policy (registration, RTV and SPPAT mandatory +
BLOCK; operating permit mandatory + WARN; cargo policy WARN), 30-day window, 21 papers (CAM-02's RTV
expires 2026-11-15 and COND-02's medical certificate 2026-11-10 - «Por vencer»; CAM-04's cargo policy
expired 2026-09-30 and only warns - nothing blocks a demo trip) and demo diesel factors (NHR 0.30,
furgón 0.55, camión 10 t 0.90, tráiler 1.10 kg CO2e/km - assumptions to be replaced by PLAPASA's).

## 4. Pending

- `MigrationConventionTest.versionsAreUniqueAndContiguous` fails on this branch alone: V70/V71 come
  from sibling branches; contiguous once integrated (renumber if needed).
- Carrier-level documents (subject `CARRIER`), evidence photo per document, an expiry notification job
  and a «Costo para servir» emissions column were left out.
