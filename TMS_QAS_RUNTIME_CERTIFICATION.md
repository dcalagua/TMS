# TMS by EBIM — QAS runtime certification

**2026-08-28 · promotion of Phase 1 + Phase 2 (V36–V48) from `dev` to `qas`**

---

## Summary

```
QAS_DEPLOYMENT = NOT OBSERVED

QAS_HEAD = 70861e6   (PR #9 merged 2026-08-28 19:20:38Z)

FLYWAY
  CURRENT = V35      (unchanged since 2026-08-25 15:46 UTC)
  PENDING = 13       (V36 … V48)

TMS_APP_RUNTIME_ROLE = PASS (database layer) / NOT EXECUTED (deployed application)

AUTHENTICATED_E2E
  PASS_COUNT = 0
  FAIL_COUNT = 0
  STATUS     = NOT EXECUTED - no environment URL, no credentials

FUNCTIONAL_SMOKE     = NOT EXECUTED
TENANT_ISOLATION     = PASS (database layer, proven) / NOT EXECUTED (API)
SETTLEMENT           = NOT EXECUTED
MAKER_CHECKER        = NOT EXECUTED
WORK_ASSIGNMENTS     = NOT EXECUTED
OWN_FLEET_COSTING    = NOT EXECUTED
DELIVERED_QUANTITY   = NOT EXECUTED
CONTROL_TOWER        = NOT EXECUTED
INTEGRATIONS         = NOT EXECUTED
OBSERVABILITY        = NOT EXECUTED
PERFORMANCE_SMOKE    = NOT EXECUTED
ACCESSIBILITY_QAS    = NOT EXECUTED

OPEN_CRITICAL = 0
OPEN_HIGH     = 1   (QAS-H1: no deployment channel reaches this database)
OPEN_LOW      = 1   (QAS-L1: tms.set_updated_at has a mutable search_path)

D9  = OPEN (PARTIAL)
D10 = OPEN - N/A for this phase, deferred business decision

READY_FOR_QAS_USER_TESTING = NO
READY_FOR_PRODUCTION       = NO
```

**Everything marked NOT EXECUTED is blocked behind one fact: no running QAS backend exists to test
against.** None of them failed. None of them ran.

---

## 1. What was completed

| Step | Result |
|---|---|
| Preflight, git state | **PASS** — `45f4b3b`, tree clean, 51 commits ahead, verified not assumed |
| Preflight, clean gates | **PASS** — backend 1844/0/0, frontend 136, e2e 38+7 skipped, lint/build clean |
| `tms_app` architecture review | **PASS** — see §2 |
| Migration preflight | **PASS** — see §3 |
| Push `dev` | **DONE** — `0757afb..45f4b3b`, local == origin |
| PR `dev → qas` | **#9**, MERGEABLE / CLEAN, no branch protection |
| Merge | **DONE** — `70861e6`, 19:20:38Z, 448 files |
| **QAS deploy** | **NOT OBSERVED** — see §4 |

## 2. Gate 0 — `tms_app`

### The architecture is not what the brief assumed, and it is correct

The brief's stop condition reads as *"the backend must not connect as the schema owner"*. **By design
it does**, and that is right:

- `tms_app` is **NOLOGIN with no password** (V13). **Nothing can connect as it.** It is not a
  credential and was never meant to be one.
- The pool connects as the schema owner, because **Flyway needs to create objects**.
- `TenantScopedDataSource` then issues `SET ROLE tms_app` **per company-scoped request**, publishes
  `tms.company_id`, and resets both when the connection returns to the pool. A reset that fails
  closes the physical connection instead of returning a dirty one.
- Wired by a `BeanPostProcessor` that wraps every `DataSource`, so no module can opt out.

**Checking `SELECT current_user` on a plain connection would therefore be a false alarm.** The
question that matters is the *effective role inside a company-scoped query*.

### Verified against the real QAS database

| Role | superuser | **bypassrls** | login |
|---|---|---|---|
| `tms_app` | no | **no** | **no** |
| `postgres` (owns schema `tms`) | no | **yes** | yes |

So the whole tenant guarantee rests on `SET ROLE tms_app` firing. **It was proven to work**, on QAS,
with real data:

```
role = tms_app
scoped to the owning company  → sees 3 orders
scoped to another company     → sees 0 orders
owner without SET ROLE        → sees 3
```

```
TMS_APP_RUNTIME_ROLE (database layer) = PASS
```

**What is still unproven:** that the *deployed application* performs this switch on every request.
That needs a running backend. The mechanism is proven; its use in the deployed process is not.

## 3. Migration preflight

| | |
|---|---|
| LOCAL VERSION | **V48** |
| QAS VERSION | **V35** |
| PENDING | **13** — V36 … V48 |
| CHECKSUM STATUS | **No drift.** V23 is the only V1–V35 file edited since QAS was built, and it was edited at **08:32 UTC on 2026-08-25**, *before* the 15:46 rebuild that applied it. That rebuild happened *because of* V23 |
| DRIFT | **None.** 36 rows, 35 versioned, **0 failed** |

No historical migration was modified during this promotion. `flyway repair` was not run and must not
be.

### QAS schema, measured after the merge

```
tms tables                     52
Phase 1/2 tables present        0  of 9
permissions                    47  (V48 asserts exactly 60)
```

**The QAS database is untouched by this promotion.**

## 4. QAS_DEPLOYMENT = NOT OBSERVED

### What was watched, and for how long

`spring.flyway.enabled` is `true` under `prod` with no variable to switch it off, and readiness
reports UP only after Flyway finishes. **Nothing but a booting backend writes
`tms.flyway_schema_history`.** So its advancing from V35 is non-repudiable evidence of a deploy — a
better signal than a dashboard, which only proves a container was built.

| Check | 19:20:59 | 19:25:54 | 19:34:21 |
|---|---|---|---|
| `max(version)` | 35 | 35 | 35 |
| rows | 36 | 36 | 36 |

Supabase logs, 19:00–19:27 UTC: **23 supavisor events and 2 postgres events**, the latter being this
session's own queries. **No application connected.**

### Why this is a pattern and not a slow build

**QAS has been at V35 since 2026-08-25 — through a previous merge that carried V43.** The Phase 1
promotion also never reached this database. `TMS_QAS_DEPLOYMENT_FINAL.md` recorded that outcome as
"PR merged, deploy unverified".

### Every channel was checked

```
GitHub Actions workflows          0
GitHub deployments                0
GitHub environments               0
Render CLI / RENDER_API_KEY       absent
AWS / Amplify CLI, credentials    absent
*.onrender.com / *.amplifyapp.com  not in the repository (deliberately - QAS.md)
E2E_BASE_URL / E2E_USER_EMAIL     not set
```

`main` sits at `0b94fb5 "enahnce"`, an old unrelated commit. **If Render is watching `main`, it has
been deploying pre-Phase-1 code all along** — a hypothesis consistent with every observation, and one
only the Render console can confirm.

### What was deliberately NOT done

**The 13 pending migrations were not applied by hand**, though the access to do so existed.

- `docs/environments/QAS.md`: *"El paso 2 usa el mecanismo real del despliegue, no un cliente SQL: lo
  que valida el entorno es que arranque el backend"*.
- ADR-002: Flyway owns application schema.
- A schema hand-built through a SQL client is **not** the product of `V1..V48`, and writing history
  rows to say otherwise would make both this report and that table lie.

**Applying them would have produced a green certification and a fictional environment.**

The backend was also not pointed at QAS from this machine: `LocalProfileDatabaseGuard` refuses it,
and `docs/development/DATABASE_SAFETY.md` records that a `.env` aimed at a hosted project was found
in the working tree three times.

## 5. Open findings

### QAS-H1 — HIGH — no deployment channel reaches this database

**Evidence:** V35 unchanged across two promotions and 14 minutes of observation; zero application
connections in the logs; no CI, no GitHub deployment integration.

**Not a code defect.** The blocker is environmental and can only be resolved from the Render and
Amplify consoles. Candidate causes, indistinguishable from here:

1. The Render service tracks a branch other than `qas` (`main` is a live candidate).
2. Auto-deploy is off, or the service is suspended or deleted.
3. `TMS_DB_URL` is unset — under `prod` there is no fallback, so the process fails fast at startup.
4. `TMS_DB_URL` points at a different database.

**Cause 4 is the one worth checking first**, because it is the only one that fails *silently in the
wrong direction*: Flyway would build the entire schema somewhere it does not belong.

### QAS-L1 — LOW — `tms.set_updated_at` has a mutable `search_path`

Supabase's linter, confirmed. Real hardening, low severity, and **not fixed here**: it needs a new
migration, and adding V49 while V36–V48 cannot deploy would widen a gap nobody can close. Recorded
for the next promotion.

### Not ours

The one ERROR-level advisory is `public.spatial_ref_sys` — **PostGIS's own table**, created by the
extension. **No table in the `tms` schema is flagged**, which corroborates ADR-004/005: `tms` is not
exposed to PostgREST and RLS is enabled throughout.

## 6. Accessibility shade (brief §8)

`forest.light.p` moved from `#5AA97F` (**2.83:1** on white — fails AA) to `#2F8159` (**4.74:1** —
passes). Not a rebrand: `#5AA97F` remains the identity in gradients and decorative backgrounds, which
is what `theme.ts` always said it was for.

Hover and pressed use `#3F8A66` / `#266A49`, both darker than the new base, so both pass. Disabled
state is MUI's own token and unchanged.

**Verified in Chromium** by `e2e/accessibility.spec.ts`; jsdom cannot evaluate contrast at all.
`ACCESSIBILITY_QAS = NOT EXECUTED` — no deployed environment to sweep.

## 7. Cost Allocation Business Decision Required (D10)

**`D10 = OPEN`. Nothing in this phase decided it, implicitly or otherwise.**

TMS can say what a trip cost. It cannot say how that cost divides across the orders on it, because
**no business policy has been chosen**. Settlement works without it; per-order profitability does not.

The candidates, none recommended as universal:

| Basis | Fits | Fails |
|---|---|---|
| `DELIVERED_QUANTITY` | Consumables billed per unit | Mixed cases and undelivered lines |
| `WEIGHT` | Dense freight where weight is the constraint | Light bulky goods pay almost nothing |
| `VOLUME` | Bulky goods, where space is the constraint | Dense goods pay almost nothing |
| `PALLETS` | Palletised operations with uniform units | Mixed or loose loads |

**The right basis is a commercial decision that can differ per customer and per contract.** It should
probably become configurable policy rather than a constant — but that is a design consequence of the
decision, not a substitute for making it.

## 8. What has to happen next

**All from a console this session cannot reach.**

1. **Confirm which branch the Render `tms-api` service tracks.** If it is not `qas`, that is the whole
   finding.
2. **Confirm `TMS_DB_URL` points at `db.ocxmsluzegpkezkpcqjj.supabase.co`** before any deploy runs.
   This is the check with the worst failure mode.
3. Redeploy, then re-verify here: `select max(version::int) from tms.flyway_schema_history` must
   read **48**, with **0 failed**.
4. Supply `E2E_BASE_URL`, `E2E_USER_EMAIL`, `E2E_USER_PASSWORD` and run the 7 authenticated specs.
5. Then, and only then, §§16–24 of the brief can be executed and this report reissued.

## 9. Statement

**The promotion succeeded. The deployment did not happen.**

Code is on `qas` at `70861e6` and is certified to the extent a local build can certify anything —
1844 backend tests, 136 frontend, 38 E2E, clean gates, contiguous immutable migrations. Tenant
isolation is proven at the database layer against the real QAS project.

**No runtime certification of the deployed application was possible, and none is claimed.** The
thirteen items marked NOT EXECUTED did not fail; they never ran. Marking any of them PASS would have
required either applying migrations by hand or inventing results, and both were available.

```
READY_FOR_QAS_USER_TESTING = NO
READY_FOR_PRODUCTION       = NO
```

`READY_FOR_QAS_USER_TESTING` is **NO** for one reason only: **there is nothing deployed for a user to
test.** It is not a judgement about the code.
