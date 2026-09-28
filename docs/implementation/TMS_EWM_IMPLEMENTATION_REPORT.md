# TMS ⇄ EWM integration v1 - implementation report

**Date:** 2026-09-28 · **Author:** autonomous run (Claude Opus 5.5) under the approved brief of 2026-09-27

## 1. Executive summary

Phases 0-9 are implemented and tested, and the two products were run against each other on
disposable local infrastructure: all nine end-to-end scenarios pass. Phase 10 (logistics documents)
is implemented for ingestion and reading; document delivery tracking is deliberately left as an ADR
because it needs legal and fiscal decisions. Phase 11 (carrier/driver portal) is **not implemented**:
TMS has no safe identity narrower than a company, and inventing one is a security decision this run
must not take alone - ADR-016 proposes it. Nothing was pushed to `dev`/`main`, merged, or run against
a shared database.

**Three levels, kept apart on purpose:**
1. **Functional implementation - complete** for the approved integration scope (Phases 0-10).
   Phase 11 is blocked by a security decision (ADR-016), and document tracking is deferred by
   decision (ADR-015 section 5).
2. **Local technical certification - PASS.** Every gate below passed:
   - TMS backend: 2162/2162.
   - TMS frontend: 189/189.
   - EWM full `clean verify`: unit 1425/1425, integration 1577/1577, BUILD SUCCESS in 6 min 04 s.
   - E2E: 9/9 on the final heads.
3. **Human review and QAS - still pending.** Neither branch is ready to merge into `dev` until
   they are done.

**Push:** both feature branches are pushed. Nothing was merged or pushed to `dev`/`main`.

Along the way the certification found and fixed **9 real defects** that the existing suites did not
catch (section 15).

## 2. TMS branch

`feature/tms-ewm-integration-v1`, from `dev` @ `692ff4f` (= `origin/dev`). The five pre-existing local
commits (`ce5660e`, `2593b34`, `4bb5dc2`, `3d17bab`, `f4169e9`) were cherry-picked without
duplication. Work from two parallel worktrees (`wip/adr014-scheduling-release`,
`wip/web-warehouse-tracking`) was cherry-picked into it; the temporary gap-tolerance commit
`8c7058f` was deliberately left out.

## 3. EWM branch

`feature/tms-connector-v1` in worktree `../IACLAUDE/WMS-by-EBIM-tms-connector`, from `dev` @ `7c086e8`
(= `origin/dev`). The test-infrastructure fix `9f85546` (from `fix/ewm-test-infra`) was cherry-picked
as `9fff5e3`. It touches three test files only. The main EWM checkout was left on `dev` with its untracked `docs/quality/` untouched.

## 4. Commits

### TMS (oldest first)
| Commit | Subject |
|---|---|
| ddbbace | docs(adr): ADR-013 dispatch source, ADR-014 scheduling and release, warehouse contract v1 |
| 8c60285 | test(planning): characterise split orders meeting execution |
| 0254d41 | fix(orders): refuse cancelling an order with part of it on a trip |
| 42d209a | fix(integration): treat shipmentNumber as an opaque company-scoped reference |
| 98bb380 | test(integration): an unchanged ERP redelivery never un-releases an order |
| 606c845 | fix(orders): support split-order execution safely |
| 19eaa28 | refactor(planning): centralize dispatch readiness |
| 439b45b | docs(adr): record the approved decisions of ADR-013, ADR-014 and warehouse contract v1 |
| 5cc80c7 | feat(integration): publish origin warehouse code, route, driver and stop sequence on shipments |
| 1b99a77 | feat(planning): support machine-attributed dispatch and the dispatch confirmation mode |
| cb5a663 | feat(planning): publish SHIPMENT_CHANGED when the driver of a committed shipment changes |
| 26f960e | feat(integration): add external dispatch reconciliation and warehouse milestones |
| b6d46da | feat(web): add dispatch confirmation mode to company settings |
| e113c4d | feat(web): dispatch override with reason |
| 930cbc8 | feat(web): warehouse dispatch card in trip workspace |
| 6a604a5 | feat(web): warehouse advisories and timeline events |
| 3825e91 | feat(web): logistics documents on orders and trips |
| 0e5a1c0 | feat(web): add delivery tracking workspace |
| 61ae03e | feat(orders): add route resolution port |
| 5a3a163 | feat(orders): add blocking holds |
| 246aa73 | feat(orders): add scheduling eligibility read model |
| 76317f1 | feat(orders): gate release on eligibility, single and bulk |
| bc8d74b | feat(planning): respect holds and route frequency in candidates |
| 5c0fba8 | feat(control-tower): surface holds on committed trips |
| 31c188e | test(orders): cover scheduling and release end to end |
| 46fe172 | feat(web): add scheduling and release workspace |
| c87e486 | docs(adr): record ADR-014 implementation notes and the holds and release guide |
| 3aee687 | test(security): mock the scheduling read model behind OrderService |
| b8ae11b | feat(orders): record logistics documents beside orders |
| 4392668 | docs(adr): ADR-016 carrier and driver portal identity (proposed, blocked on a security decision) |
| 3286017 | feat(orders): expose allocated and pending amounts on order views |
| 51a7c30 | feat(web): show when a released order is already partly planned |
| 08ac13d | fix(warehouse): an UNAPPLIED dispatch with NOT_APPLIED is a MISMATCH and reaches the Control Tower |
| a7e7384 | test(e2e): local TMS <-> EWM end-to-end harness for Warehouse Execution v1 |
| df60341 | docs(implementation): TMS <-> EWM integration v1 report |
| (final) | docs(implementation): final certification results (this revision of the report) |

### EWM (oldest first)
| Commit | Subject |
|---|---|
| 1b6ab50 | fix(outbound): la anulación de un pedido comprometido deja de ser silenciosa |
| 65fb65a | fix(integration): el DELETE del ORR pasa por el mismo caso de uso de anulación |
| 9f08756 | feat(integration): destinos de salida con autenticación BEARER |
| 8d6bb1e | feat(integration): contratos JSON de salida y su clave de idempotencia |
| d17c236 | feat(outbound): carga planificada desde un plan externo (POL) y V50 |
| 12f248f | feat(integration): conector TMS — aviso firmado, pull del plan y carga planificada |
| e37c190 | feat(integration): hitos de carga hacia el TMS |
| b4bfe3a | feat(integration): confirmación de despacho hacia el TMS |
| 2777929 | docs(integration): el conector TMS v1 — configuración, mapeo, reglas y reintentos |
| 4676087 | fix(integration): el plan de un viaje ya despachado por el TMS igual crea la carga |
| 1e56ff0 | fix(integration): las horas hacia el TMS viajan sin recortar a segundos |
| 9fff5e3 | fix(test): isolate scheduled workers and container lifecycle (cherry-pick of `9f85546`: `application-test.yml`, `NeoRetailSeedSmokeIT`, new `ScheduledWorkersOffInTestProfileTest`) |

## 5. Migrations created

| Product | Migration | Content |
|---|---|---|
| TMS | V52 `dispatch_mode_and_machine_attributable_dispatch` | `company_settings.dispatch_confirmation_mode`; `trip.dispatch_source`, `dispatched_by_client`, `ready_by_client`; V25 person-only pairs replaced by exclusive-ors (V31 pattern); backfill of past departures as OPERATOR **without re-stamping `updated_at`**; `planning.trip:dispatch-override` (ORGANIZATION_ADMIN, COMPANY_ADMIN); permission action shape widened by exactly one hyphen between words; `DISPATCH_OVERRIDDEN` audit action; `allocated_*` comments |
| TMS | V53 `external_dispatch_and_warehouse_milestones` | `external_dispatch`, `external_dispatch_order`, `warehouse_milestone`; RLS + per-verb least privilege; two integration scopes; four warehouse timeline types |
| TMS | V54 `order_hold_and_release_audit` | `order_hold` (actor XOR person/credential), `orders.hold:manage`, audit actions `ORDER_RELEASED`, `ORDER_HOLD_PLACED`, `ORDER_HOLD_RELEASED` (keeps V52's `DISPATCH_OVERRIDDEN`) |
| TMS | V55 `logistics_documents` | `logistics_document`, `logistics_document_order` (N:M), scope `integration.document:write` |
| EWM | V49 `outbound_bearer_auth` | BEARER outbound destinations, token encrypted with the existing key |
| EWM | V50 `tms_connector` | connector configuration, webhook events inbox, planned orders of a load (`out_shipment_load_plan_order`), operational exceptions |

No applied migration was edited. `transportReference` needed no migration on either side.

## 6. ADRs and documents updated

- **ADR-013** (decisions section 13), **ADR-014** (questions closed in section 10; implementation notes in section 11), **WAREHOUSE_EXECUTION_V1** (implemented; §4.3 severities, status codes, read-back), **OUTBOUND_SHIPMENT_V1** (additive fields; every event type now has a source), **SPLIT_ORDER_EXECUTION** (official R1-R3, the ten scenarios, defects found), **SHIP_UNITS_AND_ALLOCATION_V1** (new meaning of `allocated_*`), `docs/domain/SCHEDULING_RELEASE_AND_HOLDS.md` (new), `CLAUDE.md` summaries.
- **New:** ADR-015 (logistics documents), ADR-016 (carrier and driver portal identity, proposed).
- **EWM:** `docs/integrations/TMS_CONNECTOR_V1.md` (configuration, mapping, rules A-F of ORR cancellation, retries, E2E).

## 7. Implemented

**Phase 0 - hardening.**
- A. Split orders R1-R3: departure never blocks the remainder; an order closes with its last carrier from every trip's deliveries; an allocation pins cancel and edit (unchanged ERP upserts stay UNCHANGED). Also: a released share returns an IN_EXECUTION order to the pool; order locks are taken in id order and refreshed.
- B/C/D: cancel with partial allocation refused; `shipmentNumber` opaque; UNCHANGED upsert test kept.
- E (EWM): one ORR cancellation use case for screen and integration DELETE, with rules A-F (`ORDER_HAS_PACKED_UNITS`, `ORDER_COMMITTED_TO_LOAD`, `ORDER_ALREADY_SHIPPED`), reservations/picking released, outbox event, audit.

**Phase 2.** `DispatchReadiness`: one evaluator for `dispatch()`, the Control Tower and the external path (characterisation tests first; no functional change). Later extended with `ORDER_HOLD_ON_COMMITTED_TRIP`.

**Phase 3 (ADR-013).** Dispatch mode (DB, settings, port, adapter, API, UI, audit); machine-attributed dispatch; `SHIPMENT_CHANGED` on committed driver change; additive shipment fields; `external_dispatch`; `POST /integration/v1/dispatch-confirmations` (2 MB, inbox idempotency, business key + canonical hash, outcomes APPLIED/RECONCILED/UNAPPLIED/RECORDED_UNMATCHED/UNCHANGED/STALE, discrepancies never 4xx); pure `DispatchReconciler`; modes MANUAL/EXTERNAL_REQUIRED/HYBRID with a pessimistic trip lock (exactly one dispatch); override (`planning.trip:dispatch-override`, reason, `OPERATOR_OVERRIDE`, `DISPATCH_OVERRIDDEN`, timeline note; 409 `dispatch-requires-external-confirmation` without it); Control Tower advisories `DISPATCH_MISMATCH`, `AWAITING_WAREHOUSE_DISPATCH`, `EXTERNAL_DISPATCH_UNMATCHED`, `ORDER_HOLD_ON_COMMITTED_TRIP`; trip workspace "Despacho de almacén" card.

**Phase 4.** `POST /integration/v1/warehouse-milestones` (batch, eventId idempotency, 200/207), stored as `WAREHOUSE_*` timeline events; the dispatch document adds `WAREHOUSE_DISPATCH_CONFIRMED`. No lifecycle moves.

**Phase 5 (EWM).** BEARER destinations; new JSON outputs `TMS_DISP` and `TMS_MILE` (SVS/IHT/SLS untouched); signed webhook receiver (HMAC over raw body, 5-minute window, dedupe by id); pull of the plan with Bearer; planned outbound load (create/update/cancel by load state, `external_load_number = shipmentNumber`, orders by exact ERP key); milestone and SLS publication with retry/DLQ and replay.

**Phase 6 (ADR-014).** `RouteResolutionPort` (RESOLVED/NOT_FOUND/AMBIGUOUS/NOT_CONFIGURED); scheduling read model (ELIGIBLE/WARNING/BLOCKED with reasons, release deadline in the company's zone, both frequencies); `order_hold`; release single (extended `mark-ready`) and bulk (`POST /orders/release`, 200/207); candidates exclude blocking holds in search, auto-plan and assignment; auto-planning honours route frequency and never picks a FREQUENCY_OVERRIDE order for that date; committed-order hold advisory and dispatch blocker.

**Phase 7.** "Programación y Liberación" screen under Operación.

**Phase 8.** Real local E2E, section 13-14.

**Phase 9.** "Seguimiento de Reparto" under Operación, over existing trips/Control Tower/tracking/warehouse data; no new state.

**Phase 10.** ADR-015; V55 ingestion `POST /integration/v1/logistics-documents` and reads per order and per trip; UI tables.

## 8. Partial

- **Delivery tracking (Phase 9):** the day board has no per-trip delivery counts, last position or warehouse verification in the board response; those are shown in the row's side panel only (loaded on demand, to avoid one request per row). A backend aggregate would unlock them.
- **EWM connector:** no periodic reconciliation pull (`GET /shipments?updatedSince=`) yet - recovery is webhook redelivery, event reprocess or the admin `sync`; no console screen for the connector (API complete).
- **Holds from integrations:** the schema supports a credential as actor; no machine endpoint places holds yet.
- **English translations** of the new UI strings.

## 9. Pending (not started, by decision)

- **Phase 11 - carrier/driver portal:** blocked on ADR-016 (a carrier- or driver-scoped identity, new roles and an RLS predicate need an explicit security decision).
- **Document delivery tracking** (ADR-015 section 5): needs the tracked unit, the legal meaning of "delivered" per document type, and who issues a GRE transportista.
- **Split-order limitation L1** (re-planning only a failed share needs a consumed-quantity ledger - a schema change).

## 10. TMS tests

- Baseline on the composed branch before any change: **2035 tests, 0 failures, 0 errors, 0 skipped** (179 classes).
- Final full suite on the branch head (`a7e7384`, run without CPU contention): **2162 tests, 0 failures,
  0 errors, 0 skipped** (189 classes).
- Note: one earlier full run under heavy contention (three parallel builds plus the E2E containers)
  had a single timeout in the pre-existing `PlanningConstraintIntegrationTest.concurrentAssignmentOfTheSameOrderBlocksThenFails`
  (10 s lock wait); it passed 3/3 in isolation and in the clean full run above.
- Frontend: lint exit 0 (warnings only, pre-existing), typecheck clean, **29 test files / 189 tests passing**, production build OK.

## 11. EWM tests

- Baseline before any change: unit **1401 / 0 / 0 / 0**, integration **1525 / 0 / 0 / 0**.
- After the connector (before the two E2E fixes): unit **1420 / 0 / 0 / 0**, integration **1575 / 0 / 0 / 0**.
- **Invalid / noisy certification run (2026-09-28, head `1e56ff0`) - not a gate.** A `clean verify`
  started before the test-infrastructure fix was terminated on purpose (exit 143). Summary at the point
  it stopped: `Tests run: 594, Failures: 1, Errors: 0, Skipped: 0`.
  - `BulkImportLargeFileIT.cincoMilFilas`: all 5000 rows imported correctly, but in ~389 s against a
    180 s limit. The threshold is deliberately unchanged.
  - Known noise: `TransferDispatchWorker` of the cached `NeoRetailSeedSmokeIT` context kept retrying
    its dead PostgreSQL (localhost:64917) every ~50 s (325 log lines). Test infrastructure, not a
    product defect: `wms.interwarehouse.worker-enabled` is not disabled in `application-test.yml`.
  - Both were addressed in a separate worktree; this run must not be used as the final gate.
- **Test-infrastructure fix, certified in a parallel worktree** (`fix/ewm-test-infra`, commit `9f85546`,
  based on this branch's head `1e56ff0`, 3 test files, no production code):
  - Root cause confirmed: scheduled workers are now off in the test profile and the NeoRetail
    container lifecycle is isolated. Targeted tests pass: NeoRetailSeedSmokeIT 1/1,
    InterWarehouseTransferIT 28/28, ScheduledWorkersOffInTestProfileTest 4/4 (it fails 2/4 when the
    worker is re-enabled, a verified mutation).
  - After the fix: 0 connection-refused lines (the old partial run had 125) and no transfer worker
    after teardown. The remaining 1177 "connection has been closed" warnings come from the live
    shared container's pool, predate the fix, and fail no test.
  - Its full suite: surefire **1425 run / 0 fail / 0 err / 0 skip**; failsafe **1577 run / 1 fail /
    0 err / 0 skip**. The single failure is again `BulkImportLargeFileIT.cincoMilFilas` (276 s > 180 s);
    run time 3 h 49 min.
- **Environment sanitised before the definitive gate.** 15 Testcontainers Postgres/PostGIS containers
  were orphans: 7-13 h old, no Ryuk reaper, no live client connection, and no test JVM running. Only
  those were removed, together with their anonymous test volumes. Nothing was touched in other
  projects' stacks, there was no `docker system prune`, and Docker was not restarted.

  | Measure | Before | After |
  |---|---|---|
  | Running containers | 77 | 62 |
  | Container CPU (sum) | ~325% | ~161% |
  | Container memory | ~6.3 GiB of the 7.7 GiB VM | ~5.6 GiB |
  | Fresh Postgres ready | 36 s | 0-1 s |

  Unrelated Supabase `realtime` services of other projects kept restart-looping and were left alone.
- **Isolated BulkImport gate** (5000 rows, 180 s limit and assertion unchanged): run 1 **29.9 s**,
  run 2 **23.9 s**, both PASS.
  - The earlier 254-389 s runs were environmental degradation. The A/B had already ruled out code:
    `ddef16a`, historically about 23 s, took about 260 s on the saturated machine.
- **Definitive EWM gate** (`./mvnw clean verify`, run alone on head `9fff5e3`): **BUILD SUCCESS**.
  - surefire **1425 run / 0 failures / 0 errors / 0 skipped**;
  - failsafe **1577 run / 0 failures / 0 errors / 0 skipped**;
  - total **6 min 04 s**, compared with 3 h 49 min on the saturated machine;
  - `BulkImportLargeFileIT` inside the suite: **9.96 s**.
- **Log checks on the definitive run:**
  - 0 "Connection refused" (so none against the destroyed NeoRetail container);
  - 0 `TransferDispatchWorker` lines, so no worker after teardown and no zombie NeoRetail context;
  - 0 Hikari "Failed to validate connection" warnings. The 1177 seen under saturation were pool
    churn of a live container, not the corrected bug, and made no test fail.

## 12. Frontend build and typecheck

TMS `frontend/tms-web`: `npm run lint` exit 0; `npm run typecheck` clean; `npm test` all green;
`npm run build` OK (only the existing chunk-size warning). EWM: typecheck and the related Vitest files
(45 tests) pass.

## 13. E2E performed

`scripts/e2e/tms-ewm/run.sh` (TMS repo): builds disposable PostGIS (TMS, :55440) and Postgres (EWM,
:55441) containers, a local JWKS on :55499 standing in for Supabase Auth (RSA key per run, RS256,
`aud=authenticated`), TMS on :8080 and EWM on :8081, runs nine scenarios and tears everything down.
Secrets are generated per run into `/tmp/tms-ewm-e2e`, never committed. About 12 minutes.

## 14. E2E results (final certification, 2026-09-28)

Run with `BUILD=1 scripts/e2e/tms-ewm/run.sh` after the green EWM gate. Both jars were rebuilt from
the final heads: TMS `df60341` (code identical to `a7e7384`) and EWM `9fff5e3`.
- **Result:** **9/9 PASS** in 6 min 36 s.
- **Cleanup:** both E2E containers were torn down afterwards.
- **Consistency:** the values below match the earlier certification run on `a7e7384`/`1e56ff0`.

| # | Scenario | Result | Evidence |
|---|---|---|---|
| 1 | EXTERNAL_REQUIRED, 97/100 | PASS | Webhook 202 → pull → load `CRG-000001` with `external_load_number=SH-00000001`; `WAREHOUSE_LOADING_STARTED`, `WAREHOUSE_LOAD_READY` on the TMS timeline; SLS 201 APPLIED; trip IN_TRANSIT, source INTEGRATION; MISMATCH with QUANTITY_VARIANCE line 1 (100 planned / 97 dispatched); DISPATCH_MISMATCH advisory |
| 2 | HYBRID, manual first | PASS | Manual 200 (OPERATOR); SLS 201 RECONCILED/MATCHED; one TRIP_DISPATCHED |
| 3 | EWM unavailable | PASS | Webhook attempts 1-2 RETRYABLE_FAILURE; manual dispatch 200; after restart attempt 3 delivered, load created, SLS RECONCILED |
| 4 | TMS unavailable | PASS | EWM delivery DEAD_LETTER after 8 attempts; replay after restart 201 APPLIED; `actual_departure_at` = EWM's historical `shippedAt`, not the receive time. 4b: SLS before confirmation → UNAPPLIED + NOT_APPLIED + advisory, trip stays CONFIRMED |
| 5 | Duplicate SLS | PASS | Same key replays the stored 201; a new key → 200 UNCHANGED; one document, one dispatch |
| 6 | Manual and SLS at once | PASS | One TRIP_DISPATCHED; four further races: one dispatch each |
| 7 | Trip cancelled, then SLS | PASS | LOADING load → EWM `CANCEL_REQUIRES_INTERVENTION` + exception; SLS UNAPPLIED with TRIP_CANCELLED + advisory. 7b: DRAFT load cancelled, `WAREHOUSE_LOAD_CANCELLED` on the timeline |
| 8 | Wrong warehouse | PASS (variant) | TMS origin re-pointed to CD02 after the plan was sent; the real SLS from CD01 → WAREHOUSE_MISMATCH, MISMATCH, advisory |
| 9 | Split order | PASS | 60/100 on trip 1 → APPLIED; order READY_FOR_PLANNING with 40 pending and eligible; 9b: the 40 on a second trip/load → order IN_EXECUTION, EWM order COMPLETED |

## 15. Additional defects found (all fixed test-first)

1. **TMS - delivery ceiling:** the second half of every split order that recorded quantities was refused (other trips' deliveries were counted against this trip's share). Now per-trip + order-wide ceilings.
2. **TMS - reopen:** a reopened order (ADR-009) could never be planned whole again (its finished trip's whole-order row held the unique slot).
3. **TMS - dispatch race:** a dispatch racing the planning of the remainder lost with a spurious 409 in 10 of 12 runs (stale entity flushed after the lock).
4. **TMS - external dispatch race:** locking a trip by id after reading it by shipment number used the stale entity; now one locking statement.
5. **TMS - UNAPPLIED not surfaced:** a document left UNAPPLIED was stored MATCHED and raised no advisory.
6. **TMS - untested gate refusals:** blocked resource and deactivated driver at dispatch had no test (characterised).
7. **EWM - committed ORR cancelled silently** and **ORR DELETE bypassing the domain** (the two reported defects).
8. **EWM - plan of an already-dispatched trip ignored**, so after an EWM outage in HYBRID the SLS could never reconcile.
9. **EWM - times truncated to seconds**, making a dispatch appear earlier than the TMS ready step.

## 16. Autonomous decisions

- `IN_EXECUTION → READY_FOR_PLANNING` when an undeparted share is released (mirror of R1), so a remainder never disappears.
- Permission action shape widened by one hyphen to keep the approved name `planning.trip:dispatch-override`; no platform super-admin exists in TMS, so only the two admin roles hold it.
- Discrepancy severities (ERROR/WARNING/INFO); only ERROR makes a document MISMATCH. Extra codes `DISPATCH_BLOCKER` (includes an open tender offer the credential cannot withdraw) and `NOT_APPLIED`.
- Quantity comparison: per line (summed across handling units) for whole-order assignments; per order by weight with 1% tolerance for split shares; warehouse code matches the origin's external reference or, if none, its code.
- A superseded revision re-sent with other content is STALE, not 409.
- Dispatch-document idempotency hashes the parsed document (whitespace-insensitive); the raw bytes are stored.
- Milestones and documents for unknown shipments/orders are stored and reported, never refused.
- ADR-014: `ROUTE_NOT_CONFIGURED` makes eligibility WARNING without requiring a reason; the integration upsert releases only ELIGIBLE or WARNING-without-override orders (a zero-capacity order is now created NOT_READY instead of 409).
- EWM: planned orders live in their own table (a stop without packages violates EWM's control view); transport changes on READY_TO_SHIP loads become exceptions; `sourceSystem` is set per connector.
- `trip_exception` is not used for machine facts (person-only by schema).

## 17. Risks

- **Integration release gating (ADR-014):** ERPs that release orders late will now see them stay NOT_READY with `CUTOFF_MISSED`/`FREQUENCY_OVERRIDE` until a person releases them. Intended, but it changes day-to-day operation for companies with calendars; roll out with the readiness report ADR-014 asks for.
- **EXTERNAL_REQUIRED** depends on the warehouse's clock: an SLS stamped before the TMS confirm/ready time is UNAPPLIED by design (no invented times). EWM now sends full precision; large clock skew still needs a person.
- **Timing-sensitive tests:** one pre-existing raw-SQL concurrency test (`PlanningConstraintIntegrationTest`, 10 s lock wait) timed out once under heavy CPU contention from parallel builds and passed 3/3 in isolation.
- **EWM:** a message generated while a destination is paused stays PENDING on the DEFAULT destination (recover by regenerate); the reconciliation pull is not built.

## 18. Backward compatibility

- All new API fields are additive; OUTBOUND_SHIPMENT_V1 unchanged for existing consumers; unknown fields ignored.
- `MANUAL` is the default dispatch mode: every existing company behaves as before; a settings request without the new field keeps the current mode.
- Historical departures backfilled as OPERATOR without changing `updated_at` (the reconciliation feed is unaffected).
- Companies/origins without routes keep releasing (`NOT_CONFIGURED`, informative).
- EWM: NONE/BASIC destinations and SVS/IHT/SLS outputs unchanged; the ORR DELETE keeps its contract and now returns a domain error code where it used to cancel silently.

## 19. TMS standalone

Fully operational without a WMS: MANUAL mode, no code path waits for a warehouse; all new warehouse
features are inert until a credential posts documents.

## 20. EWM standalone

Unchanged unless a TMS connector is configured; the ORR cancellation rules apply to every EWM
deployment (a behaviour fix: committed orders are no longer cancelled silently).

## 21. EWM + TMS integrated

Verified end to end locally (section 14). Recommended start mode for EWM + TMS deployments:
`HYBRID`, then `EXTERNAL_REQUIRED` once the warehouse flow is stable.

## 22. New endpoints

**TMS integration API (`/integration/v1`, Bearer credential):** `POST /dispatch-confirmations`
(`integration.dispatch:write`), `POST /warehouse-milestones` (`integration.warehouse-milestone:write`),
`POST /logistics-documents` (`integration.document:write`); `GET /shipments/{n}` gains additive fields.

**TMS user API (`/api/v1`):** `GET /planning/trips/{id}/warehouse`, `GET /planning/trips/{id}/warehouse/documents/{docId}/raw`,
`GET /planning/trips/{id}/documents`, `GET /orders/{id}/documents`, `POST /orders/{id}/mark-ready` (optional `overrideReason`),
`POST /orders/release`, `GET /orders/scheduling`, `GET /orders/scheduling/summary`, `GET /orders/{id}/scheduling`,
`GET|POST /orders/{id}/holds`, `POST /orders/{id}/holds/{holdId}/release`; `POST /planning/trips/{id}/dispatch` accepts `overrideReason`;
company profile accepts `dispatchConfirmationMode`.

**EWM:** `POST /api/v1/integration/tms/webhooks/{connectorId}` (signature-authenticated);
`/api/v1/admin/integrations/tms/{connector, sync, webhook-events, webhook-events/{id}/reprocess, plans, exceptions, exceptions/{id}/resolve}`.

## 23. Final payloads

- **TMS → WMS:** webhook envelope and `GET /integration/v1/shipments/{n}` as in WAREHOUSE_EXECUTION_V1 §3.2-3.3 (with `originExternalReference`, `routeCode`, `driver`, `orders[].stopSequence`).
- **WMS → TMS:** `DISPATCH_CONFIRMED` and `WAREHOUSE_MILESTONE` exactly as §4.1-4.2, `sourceSystem = EWM_EBIM` (producer), `orders[].externalSource` = ERP namespace verbatim; response per §4.1/§4.3.
- Golden fixtures: EWM `backend/wms-api/src/test/resources/contracts/tms/v1/`.

## 24. New screens

- **Empresa → Despacho:** mode selector with explanations; confirmation when switching to EXTERNAL_REQUIRED.
- **Trip Workspace:** "Despachar con override" (permission + mandatory reason); "Despacho de almacén" card (mode, source, SLS/revision, load, physical time, plan vs real, verification chip, discrepancies, orders, milestones, raw document drawer); "Documentos" card.
- **Control Tower:** labels/icons for the four new advisories; timeline labels for warehouse events with an "Integración" chip.
- **Operación → Programación y Liberación:** summary by origin/route/date (total, elegibles, warnings, bloqueados, holds), filters, table with eligibility and reasons, single and bulk release (with reason when required), holds, reasons panel, go to order, open/create Planning Run.
- **Operación → Seguimiento de Reparto:** day KPIs and trip table (progress, next stop, alerts) with an on-demand side panel (deliveries, stops, last position, warehouse card) and link to the Trip Workspace.
- **Pedidos:** "Parcialmente planificado" chip; "Documentos" section in the order drawer.

## 25. Recommended next step

1. Human review of both branches (start with V52-V55, the permission shape change, and the ADR-014 integration release gating), then a QAS rollout in `HYBRID` for one company with the E2E harness as acceptance test.
2. Decide ADR-016 (portal identity) and ADR-015 section 5 (document tracking).
3. Add the EWM reconciliation pull and the delivery-tracking board aggregates.

```
IMPLEMENTATION_COMPLETE=true
LOCAL_TECHNICAL_CERTIFICATION=PASS
E2E=9/9_PASS
READY_FOR_QAS=true
SAFE_TO_MERGE_DEV=false

TMS_EWM_INTEGRATION_STATUS=COMPLETE
SAFE_TO_REVIEW=true
SAFE_TO_MERGE_TMS=false
SAFE_TO_MERGE_EWM=false
```

- **Why `COMPLETE`:** it describes the TMS <-> EWM integration v1 scope. Everything in that scope is
  implemented and certified locally.
- **Explicitly outside it** (see section 9):
  - the carrier/driver portal, blocked by a security decision (ADR-016);
  - document delivery tracking, deferred for legal and fiscal decisions (ADR-015 section 5).
- **Why the merge flags stay `false`:** not because of a red test. Both branches change the security
  model and the release semantics of integrated orders:
  - new permissions and a widened permission shape;
  - machine actors on trips;
  - a signature-authenticated EWM endpoint;
  - eligibility-gated ERP release.

  That needs human review and a QAS pass before `dev`.
