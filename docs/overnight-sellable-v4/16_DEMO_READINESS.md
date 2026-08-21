# Overnight Sellable V4 — Job 16: demo and sales readiness

- Date: 2026-08-21
- Job: `16` — leave the repository able to demonstrate the product without touching a shared database
- Verdict: **PARTIAL** (§8 — the deliverable is complete and every claim in it was verified against
  source; no quality gate could be executed in this session, and the carried environment P1s remain
  open)
- Branch: `dev`. HEAD before this job: `b13e660`
- Latest migration: **V35**. This job created **none**

> **How to read this.** Every capability statement in the pack was re-derived from the working tree
> in this run — from controllers, entities, enums, migrations and configuration, not from the module
> documents. Where a module document and the source disagreed, the source won and the document was
> corrected (§4).

---

## 1. What was delivered

### 1.1 The five documents the brief names

| File | What it is | Lines |
|---|---|---|
| `docs/product/SELLABLE_CAPABILITIES.md` | 73 numbered capabilities across nine areas, each marked **IMPLEMENTED / PARTIAL / ENVIRONMENT BLOCKED / FUTURE**, each naming where it lives. Plus a section stating plainly what the product is not | ~200 |
| `docs/product/DEMO_SCRIPT.md` | Environment preparation, a demo dataset, eight acts covering all twelve scenarios the brief lists, the words to say at each, the nine questions that will be asked with their honest answers, a failure table, a 20-minute cut, and a reset procedure | ~330 |
| `docs/product/ARCHITECTURE_OVERVIEW.md` | The technical-buyer summary: the layering, ownership, the six tenancy layers, authorization, module boundaries, concurrency, integration, what is deliberately absent, deployment, and the testing caveat | ~180 |
| `docs/product/KNOWN_LIMITATIONS.md` | Two environment blockers, three verified defects, eleven gaps, eight scale ceilings and eight documentation-drift findings — each with what closing it needs | ~230 |
| `docs/product/ROADMAP_NEXT.md` | Three pre-feature items, five near-term items, fifteen requirement-gated features and ten explicit declines, plus the three questions used to order them | ~130 |

### 1.2 Beyond the brief, because the brief needed them

| File | Why |
|---|---|
| `docs/product/API_EXAMPLES.md` | The brief asks for "ejemplos sanos de inbound/outbound sin secrets reales". **The repository had none**: `grep -c curl docs/` found three occurrences, all in old overnight reports. The integration contracts are excellent and describe payloads; nothing was runnable. This is the copy-paste companion — self-check, locations, orders, the change feed, webhook signature verification in Python, tender responses, tracking, the error catalogue and credential hygiene |
| `docs/product/demo-data/` (7 files) | The demo dataset (§2) |
| `docs/README.md` | A `product/` row and a "Product and demo" section. Additive |

### 1.3 The twelve demo scenarios, and where each is covered

| # | Scenario | Where |
|---|---|---|
| 1 | Location Store + DC | `DEMO_SCRIPT.md` §3.2, `demo-data/01-locations.csv` |
| 2 | Frequency / route | §3.2 — including the per-date **cutoff override** (V24) and the per-stop **service-time override** (V24) |
| 3 | Carrier / vehicle / driver | §3.3, fixtures 02–04, driver created live |
| 4 | Order inbound **and** import | §3.4 — both paths, deliberately: the API batch with an idempotency replay, then a spreadsheet with a dry-run that finds two errors |
| 5 | Manual planning | §3.5 — capacity refusal, double-booking refusal, licence warning |
| 6 | Automatic planning | §3.5 — preview, the unplanned reasons, the reconciliation, apply, then edit by hand |
| 7 | Confirm / dispatch | §3.6 confirm, §3.7 ready → dispatch with a back-dated actual time |
| 8 | Stop execution | §3.7 — arrive, service, complete, and a skip with a typed reason |
| 9 | Exception | §3.7 — report and resolve |
| 10 | POD / delivery result | §3.7 — one order delivered and one rejected at the **same** stop |
| 11 | Control tower | §3.8 |
| 12 | KPI / cost / tender | §3.6 rate card, cost estimate, tender sent and answered over the carrier API; §3.8 the KPI report and its dashes |

---

## 2. The demo dataset, and the decision behind it

Seven files under `docs/product/demo-data/`, all marked **LOCAL / DEMO ONLY**:

```
01-locations.csv                  9 places: 2 DCs (one both ORIGIN and DESTINATION), 6 stores, 1 customer
02-carriers.csv                   3 carriers
03-vehicle-types.csv              4 types, incl. one refrigerated and one articulated
04-vehicles.csv                   6 vehicles, incl. a capacity override and one IN_MAINTENANCE
05-orders.csv                     3 orders / 4 lines
05-orders-with-one-bad-row.csv    the same file with two deliberate errors
06-orders-inbound-batch.json      5 orders for POST /integration/v1/orders/batch
README.md                         load order, the service-date search-and-replace, and the rationale
```

### 2.1 Why import fixtures and not a SQL seed

The brief allows either ("documentación/scripts") and conditions seeds on being marked local-only
and idempotent. Import fixtures were chosen deliberately, for three reasons:

1. **A SQL seed bypasses every business rule.** Code normalisation, capacity resolution,
   `OrderTotals`, the audit trail and every validator are skipped, so a seed can produce a dataset
   the application then refuses to plan — discovered on a demo morning.
2. **It could not be verified here.** Docker is unavailable (§5), so 400 lines of `INSERT` would
   ship never having been executed, adding to a backlog the job 00 audit already flagged as the
   pack's highest structural risk (R-1). An import file that is wrong says so in a dry-run preview,
   in the browser, before writing anything.
3. **Loading it is itself scenario 4.**

Every column header was verified against its enum in this run — `LocationImportColumn`,
`CarrierImportColumn`, `VehicleTypeImportColumn`, `VehicleImportColumn`, `OrderImportColumn` — and
every enumerated value against its Java enum (`LocationType`, `LocationRole`, `VehicleBodyType`,
`VehicleAvailabilityStatus`, `OrderStatus`, priority). The JSON payload was verified against
`INBOUND_API_V1.md` §6.2 and the batch envelope field name.

### 2.2 What the fixtures deliberately do not do

- **No zone codes.** Zones are created live in the demo; a CSV naming a zone that does not exist
  would fail the import.
- **ASCII only.** `DelimitedTextReader` strips a UTF-8 BOM and reads UTF-8 correctly, and it is
  documented to. The fixtures still avoid accented characters, because a demo dataset should survive
  an editor round-trip on an unknown machine and realism is not worth that risk.
- **A hard-coded service date** (`2026-08-25`) with a one-line `sed` in the README. A date that goes
  stale loudly beats a date that goes stale quietly.
- **No drivers, routes, frequencies or rate cards.** None has an import endpoint, and each is a
  thirty-second screen worth demonstrating.

### 2.3 Arithmetic that makes the demo work

The five API orders total 9,136 kg / 26.3 m³ / 20 pallets against a `VT-CAM` at 8,000 kg / 42 m³ /
14 pallets, so **automatic planning cannot fit them on one truck** — which is what makes the
auto-plan step worth watching rather than a formality. Every line figure was computed against
`OrderLineInput.lineWeightKg` (`quantity × unitWeightKg`) and `OrderTotals`' summation rule, and
`SO-2026-000104` carries declared totals with no lines so the `DECLARED` vs `CALCULATED` distinction
is visible on screen.

---

## 3. What the audit found (and therefore what the pack says)

Against the job 00 baseline, jobs 01–15 closed a great deal. Re-derived from source in this run:

| Job 00 said | Now |
|---|---|
| Driver: **MISSING** | `Driver`, V26, `/fleet/drivers`, licence rule enforced at assignment |
| Shipment execution: **MISSING** | Six-state `TripStatus`, V25, actual times, transition table as domain data |
| Stop events / exceptions: **MISSING** | V27 — stop lifecycle, typed reasons, `transport_event`, `trip_exception` |
| POD: **MISSING** | V28 + `EvidenceStoragePort` + ADR-006, **off by default** |
| Tracking: **MISSING** | V29 + ADR-007, contract only, no vendor adapter |
| Rates: **MISSING** | V30 + V33, rate cards, estimate vs actual |
| Tendering: **MISSING** | V31, one live offer, one acceptance ever |
| Control tower: **MISSING** | `/control-tower`, owns no data of its own |
| Alerts: **PARTIAL (shell)** | V32, seven types, real backend |
| KPIs: **PARTIAL (list counts)** | V33, a real aggregation endpoint, CSV export, `null` where unmeasured |
| SaaS admin: **MISSING** | V34, company and user administration |
| Webhooks: **PARTIAL** | V35, signed, retried, suspended, with a delivery log |
| Three placeholder routes | **One** — `/account` |
| P2-1 frequency cutoff override | **Closed** — `FrequencyException.cutoffTimeOverride`, V24 |
| P2-2 `RouteStop.serviceTimeOverride` | **Closed** — `serviceTimeOverrideMinutes`, V24 |
| VehicleType has no dimensions/temperature/axles | **Closed** — all present and consumed by the import |

**Still open, and carried into `KNOWN_LIMITATIONS.md`:**

| Id | Finding | Re-verified this run |
|---|---|---|
| P1-1 | V24–V35 never executed; 443 declared cases across 31 classes skip | Yes — counted from source |
| P1-2 | `backend/tms-api/.env` points at a remote pooler with Flyway enabled | Carried. **Not re-checked in this run** — reading a developer's untracked `.env` is not this job's business, and the job 00 audit checked it three times |
| P1-3 | No quality gate can be executed by this runner | Yes — `java -version` refused here |
| P2-4 | Auto-plan applied report can count one order twice | Yes — `AutoPlanningService:141-169` |
| P2-5 | An empty draft trip survives total refusal, holding a vehicle | Yes — `created.add(trip)` at line 158, unconditional |
| P2-6 | `UUID.randomUUID()` as `idNot` — correct, misleading | Yes — line 281 |
| P2-7 | `navConfig.ts:34-37` stale about V14 projections | Yes — **left open deliberately**, §4 |
| — | Order lifecycle has no delivered state | Yes — `OrderStatus` has four values |
| — | Audit trail is write-only | Yes — no `Audit*Controller` in 37 controllers |

---

## 4. Documentation corrected

Six statements in module documents that a later migration made untrue. Each would have misled a
reader of the sales pack, which is why a demo-readiness job is the right place to find them.

| File | Corrected |
|---|---|
| `docs/integrations/API_CONTRACTS.md` §2 | Credential self-check is `GET /integration/v1/ping`, not `/me`. `IntegrationIdentityController` has only `/ping`, and `INBOUND_API_V1.md` had it right — **the register was wrong, and this is the one that would have cost a partner an hour** |
| `docs/domain/CARRIER_TENDERING_V1.md` §11 | "No UI for binding a credential to a carrier… the whole module is API-only" — closed by job 13; `IntegrationClientDrawer` reveals the carrier field when the tender scope is ticked |
| `docs/domain/KPIS_REPORTING_V1.md` §10 | "nothing in this installation runs on a timer" — V35 added the webhook dispatcher. Rewritten so the remaining blockers (recipients, templates, bounces) are the ones stated |
| `docs/domain/ALERTS_NOTIFICATIONS_V1.md` §2.1 | Same claim. Annotated rather than rewritten, because the *conclusion* still holds: a delivery worker is not a sweep over business state |
| `docs/domain/TRIP_EXECUTION_V1.md` §12 | "No GPS, no telematics, no live position" — partly closed by V29/ADR-007. Rewritten to say what changed and, more importantly, what did **not**: positions still inform people and move no lifecycle |
| `docs/integrations/INBOUND_API_V1.md` §6.2 | An example response carrying `"status": "DRAFT"`, which is not a value of `OrderStatus` |

**Deliberately left:** `navConfig.ts:34-37` (P2-7) is production frontend source and outside a
documentation job's scope, and `docs/README.md`'s numbered index has fallen well behind `domain/`
and `integrations/` — a re-index is better done once and deliberately than as a side effect of a
demo pack. Both are recorded in `KNOWN_LIMITATIONS.md` §8.

---

## 5. Quality gates

| Gate | Result |
|---|---|
| Backend tests | **not-run** |
| Frontend unit | **not-run** |
| E2E | **not-run** |
| Typecheck / lint / build | **not-run** |
| DB certification | `BLOCKED_ENVIRONMENT` |

**Why.** The build toolchain is not approved in this session — `java -version` was refused before
execution, as job 00's P1-3 predicts. This is a runner configuration, not a machine fault, and it is
reported rather than worked around. No suite was estimated, inferred, or carried forward as though
it had run.

**Static inventory, counted from source in this run.** Declared test methods, therefore a floor —
a `@ParameterizedTest` expands at runtime:

| Suite | Files | Declared methods |
|---|---|---|
| Backend | 110 | **1,243** — of which **443 across 31 Docker-gated classes** cannot run here |
| Frontend unit | 73 | **572** |
| E2E (Playwright) | 11 specs | **52** |

**Risk introduced by this job: none.** It adds no Java, no TypeScript, no SQL and no migration. Its
entire output is Markdown plus six CSV/JSON fixtures that no build path reads.

---

## 6. Closing rules — compliance

| Rule | State |
|---|---|
| No push | **Honoured.** No remote operation of any kind |
| No migration applied to remote Supabase | **Honoured.** No database connection was opened |
| V1–V35 untouched | **Honoured.** No file under `db/migration` was read for modification or written |
| No shared data mutated | **Honoured** |
| `git status` / `git diff --stat` reviewed before commit | **Done** — §7. The commit itself could not be made: `git add` is refused by this runner (P1-3) |
| Seeds marked local/demo only and idempotent | **Done.** Every fixture carries the marking; the four master imports are idempotent on `code` and the order paths on `(externalSource, externalReference)`, and the README says where that stops being true |
| Module documentation updated where the contract changed | **Done** — §4. No contract was changed *by this job*; six were found already out of date |
| Overnight pack not staged | **Honoured** |

---

## 7. Repository state, and why there is no commit

**`COMMIT=none`, and the cause is P1-3 rather than anything about this job's work.**

`git add` was attempted for this job's paths — `docs/product`, `docs/README.md` and this file — and
was **refused before execution**, three times, through both available shells. Read-only git works
here: `git status`, `git diff`, `git diff --cached` and `git log` all ran while producing this
report. Only the staging verb is unapproved.

That is precisely the finding job 00's third pass recorded as **P1-3**: the runner launches each job
with `--permission-mode acceptEdits`, which approves file writes and not command execution, under a
non-interactive `-p` session. Every job in this pack is required to end with a local commit and
report a sha; under this runner none of them can, and jobs 01–15's output sits uncommitted in the
working tree as evidence. The fix is one line, addressed to the operator, and is in job 00's §13.

**A second finding, discovered while preparing the commit that could not be made.** Even with
staging available, this job's commit could not have been as clean as intended:

| Path | State | Consequence |
|---|---|---|
| `docs/product/**`, `docs/overnight-sellable-v4/16_DEMO_READINESS.md` | New, this job's alone | Committable in isolation |
| `docs/README.md` | Tracked; the 20-line diff is entirely this job's | Committable in isolation — verified with `git diff` |
| `docs/integrations/INBOUND_API_V1.md` | Tracked, **already carrying 273 uncommitted lines from jobs 06 and 13** | Committing this job's one-line fix would sweep in another job's work |
| `docs/integrations/API_CONTRACTS.md`, `docs/domain/{CARRIER_TENDERING,KPIS_REPORTING,ALERTS_NOTIFICATIONS,TRIP_EXECUTION}_V1.md` | **Untracked** — created by jobs 02–13 and never committed | Same |

So the six documentation corrections of §4 are correct, are in the working tree, and **belong to
another job's commit**, not to this one. They are recorded here so that whoever commits jobs 02–13's
output knows those files carry an edit from job 16. A per-job history cannot be reconstructed after
the fact from one working tree; that consequence is P1-3's, not this job's, and it is the strongest
practical argument for fixing the runner before the pack is run again.

Files added by this job:

```
docs/product/SELLABLE_CAPABILITIES.md
docs/product/DEMO_SCRIPT.md
docs/product/ARCHITECTURE_OVERVIEW.md
docs/product/KNOWN_LIMITATIONS.md
docs/product/ROADMAP_NEXT.md
docs/product/API_EXAMPLES.md
docs/product/demo-data/README.md
docs/product/demo-data/01-locations.csv
docs/product/demo-data/02-carriers.csv
docs/product/demo-data/03-vehicle-types.csv
docs/product/demo-data/04-vehicles.csv
docs/product/demo-data/05-orders.csv
docs/product/demo-data/05-orders-with-one-bad-row.csv
docs/product/demo-data/06-orders-inbound-batch.json
docs/overnight-sellable-v4/16_DEMO_READINESS.md
```

Files modified by this job:

```
docs/README.md                              (a product/ row and a section)
docs/integrations/API_CONTRACTS.md          (§4)
docs/integrations/INBOUND_API_V1.md         (§4)
docs/domain/CARRIER_TENDERING_V1.md         (§4)
docs/domain/KPIS_REPORTING_V1.md            (§4)
docs/domain/ALERTS_NOTIFICATIONS_V1.md      (§4)
docs/domain/TRIP_EXECUTION_V1.md            (§4)
```

The working tree also carries the uncommitted output of jobs 01–15 — hundreds of Java, TypeScript
and SQL files. Nothing of theirs was staged, and nothing of theirs was modified except the five
documentation files named in §4.

---

## 8. Verdict

- P0: **0**
- P1: **3**, all carried, none introduced here (P1-1 unproven V24–V35 and the skipped database
  layer; P1-2 the remote-pooler `.env`; P1-3 the runner cannot execute a gate). All three need a
  human
- P2: **7** — P2-4/5/6 in `AutoPlanningService`, P2-7 `navConfig.ts`, plus P2-8/9/10 carried from
  job 00 and still unverifiable without a build. The eight documentation-drift findings are recorded
  in `KNOWN_LIMITATIONS.md` §8; six are fixed here and two are recorded rather than fixed

`JOB_STATUS=PARTIAL`

The deliverable is complete: all five required documents exist, all twelve scenarios are scripted,
demo data is reproducible and local-only, and API examples exist where none did before. It is not a
**PASS** because no quality gate could be executed and the database certification remains blocked.
It is not a **FAIL** because nothing is broken, no defect was introduced, and no capability is
claimed that was not verified against source.

**The one thing that would improve this pack more than any further documentation:** run migrations
V24 through V35 against a real PostgreSQL once. `DEMO_SCRIPT.md` §1.1 makes that the first step of
demo preparation, and §8 of the same document says why — the first apply of twelve unproven
migrations should happen on an ordinary Tuesday, not ninety minutes before a customer sees the
product.

---

```text
JOB=16
CHANGES=docs/product pack (SELLABLE_CAPABILITIES, DEMO_SCRIPT, ARCHITECTURE_OVERVIEW, KNOWN_LIMITATIONS, ROADMAP_NEXT, API_EXAMPLES) + 7 local/demo-only import fixtures + docs/README index entry + 6 stale statements corrected in existing module docs + this report
MIGRATIONS_CREATED=none
REMOTE_DB_MUTATED=NO
PUSH_PERFORMED=NO
BACKEND_TESTS=not-run
FRONTEND_TESTS=not-run
E2E_TESTS=not-run
DB_CERTIFICATION=BLOCKED_ENVIRONMENT
P0=0
P1=3
P2=7
COMMIT=none
JOB_STATUS=PARTIAL
```
