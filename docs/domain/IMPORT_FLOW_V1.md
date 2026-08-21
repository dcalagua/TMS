# TMS by EBIM - bulk import flow (V1)

Owner: `com.ebim.tms.orders.application.imports` (orders) and
`com.ebim.tms.{masterdata,fleet}.application.imports` (master data), schema owners
`V17__orders.sql`'s `order_import_batch` and `V21__master_data_import_batch.sql`'s
`import_batch`. Scope: the spreadsheet-driven bulk create path for Transport Orders, Locations,
Carriers, Vehicle Types and Vehicles - the "Import Center" (Job 09) plus the order bulk import
that predates it (Job 05).

This complements `docs/integrations/INBOUND_API_V1.md`: that document is machine-to-machine JSON
delivery; this one is a human uploading a spreadsheet through the TMS web UI. Both end up calling
the same underlying services (`OrderService`, `LocationService`, ...), so both are subject to the
same tenancy, validation and uniqueness rules - an import cannot do anything a manual create could
not.

## 1. The two guarantees every import gives

Every `*ImportService` (`OrderImportService`, `LocationImportService`, `CarrierImportService`,
`VehicleImportService`, `VehicleTypeImportService`) makes the same two promises:

1. **All or nothing.** `apply` runs in one transaction and writes nothing at all if a single row
   has an issue. There is no partial-file state to reconcile.
2. **Idempotent.** Re-uploading the same file after a network failure or an operator mistake is
   safe: a row whose identity already exists in the company is skipped (`SKIPPED_DUPLICATE`), not
   duplicated and not treated as an error.

| Entity | Identity |
|---|---|
| Transport Order | `(externalSource, externalReference)` |
| Location | `externalReference` under `externalSystem` when present, otherwise `code` |
| Carrier | `code` |
| Vehicle | `code` **or** `licensePlate` (either match skips the row) |
| Vehicle Type | `code` |

## 2. The two-step UI flow

Every import endpoint set follows the same shape:

```
GET  {base}/import/template     download an empty spreadsheet with the expected columns
POST {base}/import/preview      dry run: parse + validate, write nothing, return a per-row report
POST {base}/import              apply: parse + validate again, then persist if the file is clean
```

`{base}` is `/orders/import` for orders and `/masterdata/{entity}/import` /
`/fleet/{entity}/import` for the four master-data entities. `preview` and `apply` share one
`evaluate(...)` method inside each service, so a preview can never describe an outcome different
from the one applying produces - what the operator approved in the preview is exactly what gets
written.

An operator's real workflow is: download the template, fill it in their own tooling, upload for a
preview, fix whatever the per-row issue list names, and only then apply. Nothing about `apply`
re-asks for confirmation beyond the file itself - the preview step is the confirmation.

## 3. Cross-tenant safety at the file level

An import template never accepts a UUID - every reference (a zone, a carrier, an origin, a
destination) is named by its business **code**, exactly like a form field would be. This is
structural, not just validated: a code naming another company's master does not resolve, and is
reported the same way a code that never existed anywhere is reported (`resource-not-found`-shaped
issue) - indistinguishable from the caller's side, so a spreadsheet cannot be used to probe
whether a code exists in someone else's tenant.

## 4. The batch record

Every successful `apply` writes exactly one row - `tms.order_import_batch` for orders,
`tms.import_batch` (discriminated by `entity_type`) for the four master-data entities - in the
same transaction as the rows it created. Recorded: file name, format, SHA-256 of the uploaded
bytes, row/created/skipped counts, who ran it, when. A dry run never writes one; a rolled-back
`apply` leaves no batch row either.

These two tables predate `tms.audit_event` (V17 and V21, versus V22) and are kept as they are -
`V21`'s own migration comment explains why `order_import_batch` was not retrofitted onto the
shared `import_batch` shape: applied migrations are immutable, and Orders keeps its own table
rather than an already-applied one being edited to fit a later idea. Consolidating the two into
one shape is tracked as a live, non-urgent finding
(`docs/security/OVERNIGHT_V3_TENANCY_REVIEW.md`, "P2-3").

## 5. Audit trail and metrics (Job 13)

Every successful `apply` also calls `AuditRecorder.record` once, after the batch row is written,
in the same transaction:

| `aggregateType` | `aggregateId` | `action` | `metadata` |
|---|---|---|---|
| `ORDER_IMPORT_BATCH` | the `order_import_batch` row's id | `IMPORT_EXECUTED` | `externalSource`, `createdCount`, `skippedCount` |
| `MASTER_DATA_IMPORT_BATCH` | the `import_batch` row's id | `IMPORT_EXECUTED` | `entityType`, `createdCount`, `skippedCount` |

See `docs/domain/AUDIT_TRAIL_V1.md` for the shape of an audit event and why it lives in a
different table from the batch record above (one records "an import ran and produced N rows",
the other records "this is one entry in the company's list of important things that happened").
The same call also increments the `tms.audit.events` Micrometer counter
(`aggregateType=MASTER_DATA_IMPORT_BATCH|ORDER_IMPORT_BATCH,action=IMPORT_EXECUTED`), which is
the cheapest way to watch import volume without querying either table.

## 6. What is deliberately out of scope for V1

- **No scheduled/unattended imports.** Every import is triggered by an authenticated person
  through the UI (`requireAppUserId`, not `writerAppUserId` - see `AuditActorProvider`); there is
  no drop-folder or cron-triggered variant. A system that must deliver on a schedule uses the
  machine-to-machine inbound API instead (`docs/integrations/INBOUND_API_V1.md`).
- **No partial apply / row-level retry.** All-or-nothing is the whole safety model (section 1);
  splitting a file into "apply the good rows, report the bad ones" would need per-row commit
  semantics this design deliberately avoids.
- **No raw file retention.** Only the SHA-256 and the file name are kept, in the batch record -
  the uploaded bytes themselves are never stored, mirroring the inbound API's default
  `retain-payloads: false` stance (`docs/integrations/INBOUND_API_V1.md` section 8.1).
