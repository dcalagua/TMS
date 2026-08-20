# TMS by EBIM - business audit trail (V1)

Owner: `com.ebim.tms.audit` (backend), schema owner
`backend/tms-api/src/main/resources/db/migration/V22__audit_event.sql`.
Scope: the append-only record of important business actions introduced by Job 13 - **not**
Event Sourcing. `tms.audit_event` is a read/report trail that sits alongside the normal
transactional tables; nothing is ever rebuilt from it, and nothing else is derived from replaying
it.

## 1. Why a shared table instead of one per module

Every module already stamps `created_by`/`updated_by` on its own rows (`AuditActorProvider`,
migration V2). That answers "who last touched this row", but not "what happened, in order,
across every module" - the question a support engineer or a compliance review actually asks.
One `tms.audit_event` table, discriminated by `aggregate_type`, answers it with one query instead
of a UNION across a dozen module-owned history tables.

This is a deliberate, narrower sibling of `tms.order_import_batch` (V17) and `tms.import_batch`
(V21): those two record *only* a bulk import's file/row-count summary and are documented
separately (`docs/domain/IMPORT_FLOW_V1.md`). `tms.audit_event` records the action itself - who
did what to which row, when - for imports and for every other action listed below.

## 2. Module boundary: the `AuditRecorder` port

`com.ebim.tms.audit` is a business module like `masterdata`, `fleet`, `orders`, `planning` and
`integration`, and `ModuleBoundaryTest` forbids any of them from depending on each other directly.
So a service in `masterdata` cannot import `com.ebim.tms.audit.application.AuditEventRecorder`.

Instead, every writer depends on `com.ebim.tms.shared.audit.AuditRecorder` - an interface in
`shared`, the same "explicit API" shape `com.ebim.tms.shared.reference.OriginLookupPort` already
established for `masterdata`. `AuditEventRecorder` (in `com.ebim.tms.audit.application`) is the
only implementation; Spring wires it into every other module by interface type.

```
masterdata / fleet / orders / planning / integration
        |  depends on (interface only)
        v
shared.audit.AuditRecorder  ---------------  implemented by  --------------->  audit.application.AuditEventRecorder
                                                                                        |
                                                                                        v
                                                                                 audit.domain.AuditEvent
                                                                                        |
                                                                                        v
                                                                                 tms.audit_event
```

`record(...)` runs inside the caller's own transaction (no `REQUIRES_NEW`, unlike
`IntegrationInboxService`'s delivery record): a business audit event describes something that
happened, so if the change it describes rolls back, the event describing it rolls back too.

## 3. What is recorded

| `aggregateType` | `action` | Written by |
|---|---|---|
| `LOCATION` | `CREATE`, `UPDATE`, `ACTIVATE`, `DEACTIVATE` | `LocationService` |
| `CARRIER` | `CREATE`, `UPDATE`, `ACTIVATE`, `DEACTIVATE` | `CarrierService` |
| `VEHICLE` | `CREATE`, `UPDATE`, `ACTIVATE`, `DEACTIVATE` | `VehicleService` |
| `TRANSPORT_ORDER` | `CREATE`, `UPDATE`, `CANCEL` | `OrderService` (reachable from both the UI and the inbound integration API - see `docs/integrations/INBOUND_API_V1.md`) |
| `TRIP` | `CREATE`, `ASSIGN_ORDER`, `REMOVE_ORDER`, `MOVE_ORDER`, `VEHICLE_CHANGE`, `CANCEL` | `TripService` |
| `PLANNING_RUN` | `CREATE`, `CONFIRM`, `CANCEL` | `PlanningRunService` |
| `SHIPMENT` | `SHIPMENT_CONFIRMED` | `PlanningRunService.confirmTrip`, in the same transaction as `tms.shipment_outbox_event` (see `docs/integrations/OUTBOUND_SHIPMENT_V1.md`) |
| `INTEGRATION_CLIENT` | `CREDENTIAL_CREATE`, `CREDENTIAL_ROTATE`, `CREDENTIAL_REVOKE` | `IntegrationClientService` |
| `MASTER_DATA_IMPORT_BATCH` | `IMPORT_EXECUTED` | `LocationImportService`, `CarrierImportService`, `VehicleImportService`, `VehicleTypeImportService` |
| `ORDER_IMPORT_BATCH` | `IMPORT_EXECUTED` | `OrderImportService` |

The full enums are `com.ebim.tms.shared.audit.AuditAggregateType` and
`com.ebim.tms.shared.audit.AuditAction`, mirrored by `ck_audit_event_aggregate_type` and
`ck_audit_event_action` (migration V22). Extending either is a Java enum constant plus a new
migration adding the value to the `CHECK` list - deliberately not a DB-level open vocabulary,
since the set of actions worth auditing is a business decision, not free text a caller supplies
(`CLAUDE.md`, "Java owns business rules").

Not recorded: reads, list/filter queries, and every `markReadyForPlanning`/`updateRoute`/
`reorderStops` transition that does not change what a support engineer would call "the case" -
kept out to keep the trail readable rather than exhaustive. Add a row for one of these the same
way as any other, when a real need shows up.

## 4. Shape of a row

| Column | Content |
|---|---|
| `company_id` | The tenant, taken from the caller's `CompanyScope` - never from the actor, so the event is scoped exactly like the write it describes |
| `actor_app_user_id` / `actor_email` | Set for a person; both null for a machine |
| `actor_machine_label` | Set for a machine (an integration credential); null for a person. Exactly one of the two actor shapes is ever populated (`ck_audit_event_actor_xor`) |
| `aggregate_type`, `aggregate_id` | What changed |
| `action` | What happened to it |
| `occurred_at` | Server timestamp, DB default `now()` |
| `correlation_id` | Same value as the request's `X-Correlation-Id` / server log lines - ties an event to a specific request end to end |
| `metadata` | Compact JSON, business fields only (a code, a plan number, a count) - **never a secret and never the row-level detail already in the aggregate's own table**. Capped at 4000 characters both in Java (`AuditEventRecorder.MAX_METADATA_LENGTH`) and in the database (`ck_audit_event_metadata_length`) |

A credential's secret, hash, or bearer token never appears here - `IntegrationClientService`
passes only `name` and, for a rotation, `graceHours`. See `docs/integrations/INBOUND_API_V1.md`
section 2 for where the secret itself is handled.

## 5. Append-only, enforced at the grant level

Every other business table in this schema gets the same four-verb grant
(`GRANT SELECT, INSERT, UPDATE, DELETE ON ... TO tms_app`, ADR-005) because V13's
`ALTER DEFAULT PRIVILEGES` applies it automatically to every new table. `tms.audit_event` is the
first table in the schema where migration V22 explicitly `REVOKE`s `UPDATE, DELETE` from
`tms_app` afterwards, so "append-only" is a database fact `tms_app` cannot violate even by
accident - not merely a convention no service happens to break. RLS still applies underneath:
separate `FOR SELECT`/`FOR INSERT` policies (there is nothing left for a `FOR UPDATE`/`FOR DELETE`
policy to guard) keep every row scoped to `tms.current_company_id()`.

## 6. Observability

`AuditEventRecorder` increments the Micrometer counter `tms.audit.events`, tagged `aggregateType`
and `action`, on every call - a volume signal (including import counts:
`aggregateType=MASTER_DATA_IMPORT_BATCH,action=IMPORT_EXECUTED`) without querying
`tms.audit_event` itself. It is readable at `GET /actuator/metrics/tms.audit.events` (with a
`tag` query parameter to break it down), since `metrics` is on the exposed actuator endpoint list
(`application.yml`). See `docs/integrations/INBOUND_API_V1.md` section 8.2 for the companion
integration-delivery counter.

## 7. Reading the trail

There is no dedicated read API in V1 - the deliberately "minimal" scope this job asked for. An
operator or a database client queries `tms.audit_event` directly, scoped by `company_id` (RLS
still applies through `tms_app`) with the indexes `ix_audit_event_company_occurred`
(company timeline) and `ix_audit_event_company_aggregate` (one aggregate's history). A read API
(`GET /api/v1/audit-events?aggregateType=&aggregateId=`) is a natural next step and does not
require a schema change - only a new controller/service pair over the existing table.

## 8. What is deliberately out of scope

- **Not Event Sourcing.** `tms.audit_event` is never replayed to reconstruct state; every
  aggregate's own table remains the source of truth for its current state.
- **No automated retention/archival job.** Nothing deletes old rows (the table cannot be updated
  or deleted into by the application role at all - section 5). Decide a retention policy before
  volume becomes a concern, the same open item `docs/integrations/INBOUND_API_V1.md` section 8.1
  already flags for `payload_snapshot`.
- **No read API yet** - see section 7.
