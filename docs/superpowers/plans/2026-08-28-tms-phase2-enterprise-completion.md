# TMS by EBIM — Phase 2: Enterprise Completion & Pre-QAS Hardening

**Created:** 2026-08-28 09:35 America/Lima
**Baseline HEAD:** `729f155` · branch `dev` · tree clean
**Baseline gates:** backend 1684 / frontend 97 / E2E 34 pass + 7 skipped · Flyway **V1–V43**
**Next free migration:** **V44**

## Objective

Close the real gaps named by `TMS_ENTERPRISE_READINESS.md` and leave the product ready for a
*separately authorised* QAS promotion. Phase 2 does not repeat JOBS 01–16 and does not deploy.

The readiness assessment's three structural gaps drive the whole phase:

1. **Settlement does not exist** — the largest capability hole (JOB 20).
2. **Delivered quantity is not captured** (D3) — blocks per-unit invoicing and cost allocation
   (JOB 19), and therefore must land *before* settlement.
3. **No evidence a local build cannot produce** — performance, accessibility, operability
   (JOBS 24–26).

## Architecture — unchanged and non-negotiable

```
React + TypeScript + MUI  →  Spring Boot / Java 21  →  PostgreSQL / Supabase / PostGIS
```

Modular monolith. No microservices, no broker, no Kubernetes, no Bootstrap. Cross-module access
only through ports in `com.ebim.tms.shared.reference`, enforced by `ModuleBoundaryTest`.

Every new persisted entity gets: `company_id`, composite FK `(id, company_id)` where it has a
parent, RLS enabled with the `tms_app` tenant policy, grants declared in
`SchemaExposureIntegrationTest`, and a cross-tenant negative test.

## Job sequence and dependencies

| JOB | Deliverable | Depends on | Migration |
|---|---|---|---|
| 17 | Documentation & capability reconciliation | — | none |
| 18 | Generic enum ↔ CHECK guard (**D8**) | — | none unless real drift |
| 19 | Delivered quantity (**D3**) | 18 (guard protects new enums) | V44 |
| 20 | Freight audit & settlement | **19** (cost allocation needs delivered qty) | V45 |
| 21 | Work assignments & resource sequencing (**D5**) | — (uses V42 availability, V38 routing) | V46 |
| 22 | Own-fleet costing (**D6**) | — (uses V38 routing, V42 shifts) | V47 |
| 23 | Operational exceptions & Control Tower V3 | 20, 21 produce exception sources | V48 |
| 24 | Observability & operations completion | 20–23 (metrics for new modules) | none |
| 25 | Performance harness & local baseline | 19–23 (measures the real system) | none |
| 26 | Accessibility foundation (**D9** → partial) | — | none |
| 27 | Phase 2 certification | all | none |

**The 19 → 20 order is load-bearing.** Cost allocation without delivered quantity would have to
infer one, which D3's evaluation forbids.

## Modules affected

- **New:** `settlement` (JOB 20). Everything else extends an existing module.
- `orders` + `planning` — delivered quantity, lifecycle derivation (JOB 19)
- `fleet` + `planning` — work assignment (JOB 21)
- `rates` — own-fleet cost profile, kept semantically apart from carrier price (JOB 22)
- `planning` (control tower) + a shared exception model (JOB 23)
- `shared` — new ports; `architecture` — the enum guard

## Interfaces produced / consumed

| Port | Producer | Consumer | Job |
|---|---|---|---|
| `DeliveredQuantityPort` (or equivalent) | `planning` | `orders`, `settlement` | 19/20 |
| `TripSettlementLookupPort` | `planning` / `rates` | `settlement` | 20 |
| `PayableExportPort` | `settlement` | ERP boundary (artifact impl) | 20 |
| `WorkAssignmentPort` | `fleet`/`planning` | `planning` engines | 21 |
| `OwnFleetCostPort` | `rates` | `planning` | 22 |
| `OperationalExceptionPort` | shared | every producing module | 23 |

## Test strategy

- **Focused test → implement → focused test** during development.
- **`./mvnw clean test` before any JOB is declared PASS.** An incremental `BUILD SUCCESS`
  certifies nothing — this chain proved that twice (JOB 02 brace, JOB 13 JPQL / 323 failures).
- Every new entity: cross-tenant denial test + a DB-constraint test.
- Every invariant: three layers — readable service refusal, entity assertion, DB constraint.
- Money: `BigDecimal` only, compared with `compareTo`. Guarded by `PersistenceMappingTest`.
- Concurrency: real threads where a race is the point (delivery quantity, work assignment,
  invoice duplication).

## Product rules that must survive Phase 2

- GPS informs; **it never moves a lifecycle** (ADR-007).
- ADR-011 stands: ETA computed on request, absent where a leg cannot be measured.
- No background commercial commitment without a real system actor (**D4 stays deferred**; no fake
  UUID).
- Own fleet: **unknown cost ≠ zero cost**.
- Delivered quantity is **never inferred** from ordered / allocated / planned.
- Settlement validates and exports; **ERP pays**. No accounting ledger in TMS.
- Control Tower: hard blockers stay separate from advisories.

## STOP_CHAIN criteria

Stop only for: data-loss risk · migration-history corruption · unresolved tenant-isolation or
authorization defect · baseline unrecoverable · a domain invariant that is fundamentally ambiguous ·
a remote action becoming necessary.

Do **not** stop for: lint, a compile error, Docker startup, one failing fixture, recoverable
migration syntax.

## Resume support

`TMS_PHASE2_MASTER_LOG.md` carries `LAST_COMPLETED_JOB`, `CURRENT_JOB`, `LATEST_MIGRATION`,
`LAST_GOOD_HEAD`, `STOP_CHAIN`, `OPEN_DEBTS`, `NEXT_ACTION` after every job. Per-job evidence in
`TMS_PHASE2_JOB_NN_RESULT.md`.

## Not in scope

Network modelling, what-if, digital twin, network optimisation, automatic GPS lifecycle
transitions, automatic arrival detection, background tender advancement, a fake system user, and
any vendor adapter (tracking or routing) without a concrete provider.

**No push. No deploy. No write to QAS, PRD or hosted Supabase.**
