# TMS by EBIM - master data overview (V1)

Scope: a map of the master-data modules and how they relate, for anyone who needs "where does
this live and what touches it" before diving into one module's own detail. Each module's own
document/ADR is the source of truth for its rules; this page only orients.

## 1. The modules

| Master | Module | Schema owner | Detail |
|---|---|---|---|
| Location (canonical store/warehouse/plant) | `com.ebim.tms.masterdata` | `V14__masterdata_canonical_location.sql` | `docs/architecture/ADR_LOCATION_MODEL.md` |
| Zone | `com.ebim.tms.masterdata` | `V6__masterdata_origins_zones.sql` | `docs/database/DATA_MODEL.md` section 9 |
| Location service calendar (`location_frequency`) | `com.ebim.tms.masterdata` | `V15__masterdata_location_frequency.sql` | `docs/database/DATA_MODEL.md` section 15 |
| Route (master route + suggested stop sequence) | `com.ebim.tms.masterdata` | `V8__masterdata_routes.sql` | `docs/database/DATA_MODEL.md` section 9 |
| Carrier | `com.ebim.tms.fleet` | `V9__fleet_masters.sql`, external ref in `V16` | `docs/database/DATA_MODEL.md` section 10 |
| Vehicle Type | `com.ebim.tms.fleet` | `V9__fleet_masters.sql` | `docs/database/DATA_MODEL.md` section 10 |
| Vehicle | `com.ebim.tms.fleet` | `V9__fleet_masters.sql`, double-booking in `V16` | `docs/database/DATA_MODEL.md` section 10, 16.2 |

`Origin` (V6) and `Destination` (V7) still exist as **compatibility projections** synchronised
from `Location` by `LocationCompatibilityProjector` on every Location write - they are not a
second source of truth. See `ADR_LOCATION_MODEL.md` for why: `Route`, `TransportOrder`,
`PlanningRun` and `TripStop` still reference `origin_id`/`destination_id`, and migrating every one
of those foreign keys onto `location_id` in one step was rejected as unnecessary churn for a
schema Location already serves correctly through the projection.

## 2. Common shape every master follows

- **Company-scoped.** Every table carries `company_id`, enforced by application code
  (`CompanyScope`, never a bare id) and by RLS as defense in depth (ADR-005).
- **Deactivate, never delete.** Every master has `active`/`activate()`/`deactivate()`, no delete
  endpoint. A master already referenced by history (a past order, a past trip) must stay
  resolvable.
- **`created_by`/`updated_by`, stamped server-side.** `AuditActorProvider` resolves the acting
  `app_user` from the authenticated request; a client can never claim to be someone else's edit.
- **Create/update/activate/deactivate write an audit event.** Since Job 13,
  `LocationService`/`CarrierService`/`VehicleService` each call
  `com.ebim.tms.shared.audit.AuditRecorder` after every write - see
  `docs/domain/AUDIT_TRAIL_V1.md` for the shape of an event and which actions are covered per
  master. `Zone`, `LocationFrequency` and `Route`/`VehicleType` writes are not yet audited this
  way (Job 13 scoped the list to the masters explicitly named "affecting planning" -
  location/store and carrier/vehicle); extending coverage is a service-level change, not a schema
  one.

## 3. How a master gets created

Two paths, both ending at the same service (`LocationService.create`, `CarrierService.create`,
...), so both are subject to the same validation, uniqueness and tenancy rules:

1. **Manually**, through the TMS web UI - one record at a time.
2. **In bulk**, through the Import Center - a spreadsheet of many records at once, with a
   dry-run preview before anything is written. See `docs/domain/IMPORT_FLOW_V1.md`.

Locations and Transport Orders have a third path: the machine-to-machine inbound API
(`docs/integrations/INBOUND_API_V1.md`), for a partner system delivering continuously rather than
a person uploading a file. Carriers, Vehicle Types and Vehicles have no inbound API endpoint in
V1 - fleet data changes far less often than orders or store lists, and was not asked for.

## 4. Where to look next

- Location model and the Origin/Destination projection: `docs/architecture/ADR_LOCATION_MODEL.md`
- Full entity/constraint/index detail: `docs/database/DATA_MODEL.md`
- Bulk import mechanics: `docs/domain/IMPORT_FLOW_V1.md`
- Audit trail for master-data changes: `docs/domain/AUDIT_TRAIL_V1.md`
- Tenancy and RLS: `docs/architecture/ADR-003-multitenancy-company-scope.md`,
  `docs/security/RLS_STRATEGY.md`
