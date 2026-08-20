# TMS by EBIM - master data overview (V1)

Scope: a map of the master-data modules and how they relate, for anyone who needs "where does
this live and what touches it" before diving into one module's own detail. Each module's own
document/ADR is the source of truth for its rules; this page only orients.

## 1. The modules

| Master | Module | Schema owner | Detail |
|---|---|---|---|
| Location (the one physical place: store, warehouse, plant, hub, delivery point) | `com.ebim.tms.masterdata` | `V14__masterdata_canonical_location.sql`, unified in `V23` | `docs/domain/LOCATIONS.md` |
| Zone | `com.ebim.tms.masterdata` | `V6__masterdata_origins_zones.sql` | `docs/database/DATA_MODEL.md` section 9 |
| Location service calendar (`location_frequency`) | `com.ebim.tms.masterdata` | `V15__masterdata_location_frequency.sql` | `docs/database/DATA_MODEL.md` section 15 |
| Route (master route + suggested stop sequence) | `com.ebim.tms.masterdata` | `V8__masterdata_routes.sql` | `docs/database/DATA_MODEL.md` section 9 |
| Carrier | `com.ebim.tms.fleet` | `V9__fleet_masters.sql`, external ref in `V16` | `docs/database/DATA_MODEL.md` section 10 |
| Vehicle Type | `com.ebim.tms.fleet` | `V9__fleet_masters.sql` | `docs/database/DATA_MODEL.md` section 10 |
| Vehicle | `com.ebim.tms.fleet` | `V9__fleet_masters.sql`, double-booking in `V16` | `docs/database/DATA_MODEL.md` section 10, 16.2 |

**There is no Origin master and no Destination master.** An origin is a `Location` holding the
`ORIGIN` operational use; a destination is one holding `DESTINATION`; a store that receives
deliveries and ships its own returns holds both, as one record. `Route`, `RouteStop`,
`TransportOrder`, `PlanningRun` and `TripStop` all reference `tms.location` since V23. The
Origins and Destinations screens are that master filtered by use, not separate CRUDs.

`tms.origin` (V6) and `tms.destination` (V7) survive only as frozen tables: no reader, no
writer, no foreign key, and no write privilege for the `tms_app` role. They are the recovery
path for V14's merge-on-code and will be dropped once V23 has run against a real database. See
`docs/domain/LOCATIONS.md` for the domain contract and `ADR_LOCATION_MODEL.md` for the decision.

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
