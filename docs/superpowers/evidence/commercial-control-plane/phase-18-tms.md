# CCP phase 18: TMS under D-14 (DEV/LOCAL only)

- **Worktree:** `TMS/.worktrees/ebim-commercial-control-plane-v1`, branch `feature/ebim-commercial-control-plane-v1`, base `9397ff5`.
- **Scope:** LOCAL only.
  - No push and no QAS.
  - No remote or shared database.
  - No Supabase CLI against any project.
- **Decision:** D-14, approved by the human for DEV/LOCAL, 2026-09-29.
  - Ruling 1: `appActive=false` withdraws the commercial SaaS capabilities, not the operational application.
  - Ruling 2: entitlements may move to PRIMARY in DEV once parity and security are green. No plan, price, quota or limit value is invented.

## Ruling 1: what changed

| Before (phase 12) | Now |
| --- | --- |
| `CommercialAccess(allowed, reason, mode)`: `allowed=false` for `APP_INACTIVE` and for `NO_SNAPSHOT` in PRIMARY | `CommercialAccess(commercialActive, reason, mode)` + `operationAllowed()` (always `true`). The reason codes are unchanged and observable |
| `CommercialAccessGateAdapter` answered `suspended(reason)`, and `CommercialAccessFilter` refused every company-scoped request with `403 commercial-access-suspended` | The adapter follows `operationAllowed()`, so a commercial fact never refuses. It logs at debug level when the commercial surface is off. The filter is kept as the hook for a separate operational-shutdown policy, and it still fails closed |
| `capabilityEnabled` / `limit` ignored `appActive` | The deciding snapshot counts only while `appActive=true`. Otherwise no sellable is granted and no limit is returned |

- `tms.core` (the baseline) is not commercial, and nothing operational is gated on it.
- The web copy for `commercial-access-suspended` (`frontend/tms-web/src/shared/api/problemMessages.ts`) stays. It is harmless: the backend no longer emits that code on a commercial fact.
- The vendored FIX-ENT-v1 README still says "`false` → el SaaS bloquea el acceso operativo". That file is checksum-pinned and was not edited. The ADR-017 amendment records how TMS reads it.

Docs:
- ADR-017: decisions 3 and 5, the consequences, and a new "Amendment - D-14" section.
- `docs/platform-provisioning/ENTITLEMENTS.md`: the mapping, a new enforcement table (commercial surface vs operation), and the operator step citing ruling 2.

## Tests (RED → GREEN)

| Step | Log | Result |
| --- | --- | --- |
| RED 1 | `runs/TM18-01-red.txt` | New `CommercialEntitlementServiceTest.inactiveAppWithdrawsTheCommercialSurface` (DUAL_READ, PRIMARY) fails 2/8: the capability is still granted with `appActive=false` |
| RED 2 | `runs/TM18-02-red.txt` | The tests rewritten for the new semantics do not compile: `commercialActive()` and `operationAllowed()` do not exist |
| GREEN | `runs/TM18-02-green.txt` | Targeted: 113 run, 0 failures, 6 skipped (`PlatformEntitlementsIntegrationTest` needs a database). Includes ArchUnit `ModuleBoundaryTest` and `LayeringTest`, and `EntitlementsFixturesPinTest` |

Tests touched:
- `CommercialAccessTest`: the table now asserts `commercialActive`, and `operationAllowed` is true on every row.
- `CommercialEntitlementServiceTest` gained these tests:
  - `inactiveAppWithdrawsTheCommercialSurface`
  - `primaryWithoutSnapshotStillOperates`
  - `productPrimaryDoesNotReachUnmappedOrganizations`: with PRODUCT scope at PRIMARY, an unmapped organization gets `NOT_UNDER_CONTROL_PLANE` and behaves as legacy.
- `CommercialEntitlementServiceTest` also updated `offlineLastGood` and `currentCompanyAccess`.
- New `CommercialAccessGateAdapterTest`: across 7 fact combinations, the gate never refuses.
- `CommercialAccessSecurityTest` now uses the real adapter and service over the in-memory store:
  - `appActive=false` in PRIMARY → 200;
  - PRIMARY with no snapshot → 200;
  - membership is still checked first.
- `CommercialAccessFilterTest` and `IntegrationApiTenancyTest`: renamed and reworded. They cover the filter mechanism with a fake gate, now framed as the operational-shutdown hook.
- `TmsEntitlementsReceiverTest`: uses the new accessor.
- `PlatformEntitlementsIntegrationTest.runtimeRoleIsLockedOut` now also asserts 42501 when `tms_app` tries to:
  - UPDATE or INSERT on `platform_entitlement_mode`;
  - UPDATE or DELETE on `platform_entitlement_applied`.

  This is the evidence for `legacyWrite: NO_LEGACY_PATH`.
- `MasterAdminMailboxBridgeTest` (X-07 bridge, test code only):
  - `access` answers `operational`, `commercial`, `reason` and `mode`;
  - `mode` accepts tenant or `PRODUCT` scope and requires a reason. It enforces one step at a time, starting from the product mode for a tenant row, as V52's trigger does;
  - a new read-only `modes` returns the product mode and the tenant's effective mode.
- `InMemoryEntitlementStore`: `modeAt(scopeKey)`.

## X-07 with D14 (MasterAdmin `scripts/ccp/tms-x07-e2e.mts`)

- Log: `runs/TM18-03-x07-e2e.txt`. Result: **25/25 PASS, rc=0**.
- Steps 6, 11 and 12 now assert:
  - `appActive=false` → `commercial=false` (`APP_INACTIVE`) and `operational=true`;
  - last-good keeps the same state;
  - v3 restores the commercial surface.
- Phase D14 comes last and is not rolled back:
  - the bridge refuses a mode change without a reason, and refuses SHADOW → PRIMARY;
  - `PRODUCT` moves SHADOW → DUAL_READ → PRIMARY with `D14_REASON`;
  - the GET returns PRIMARY, v3, with the checksum of the last desired snapshot (`appActive=true`);
  - the mapped organization is `APP_ACTIVE` in PRIMARY;
  - an unmapped organization gets `NOT_UNDER_CONTROL_PLANE`;
  - the final state is PRODUCT=PRIMARY and tenant=PRIMARY.
- With `CCP_EVIDENCE_DIR` set, the run writes `d14-tms.json` with:
  - scope `PRODUCT`;
  - `legacyWrite: NO_LEGACY_PATH`;
  - `legacyTenants`: DEMO `UNRESOLVED`;
  - `billing: null`.
- The mapped tenant already carried a tenant-scope PRIMARY override from the phase-12 cohort steps. V52 forbids deleting that row, so both scopes end at PRIMARY.

## Full suite

- The Docker socket is denied inside the sandbox, and running outside it needed an interactive approval that this session could not get. So the PostgreSQL 17 run (`TMS_TEST_DB_URL`) was **not** executed here.
- The full suite without a database ran instead: `runs/TM18-04-suite-nodb.txt`.
  - Result: **1915 run, 0 failures, 0 errors, 406 skipped** (the database-backed tests), `BUILD SUCCESS`, `EXIT=0`.
  - Phase 12 closed at 1904/0/0 with 406 skipped, so this run adds 11 tests.
  - The new write assertions in `PlatformEntitlementsIntegrationTest.runtimeRoleIsLockedOut` are among the skipped tests and have not run on PostgreSQL yet.
- Pending for a run with Docker, as in phase 12:

```
docker run -d --rm --name tms_ccp18_pg -e POSTGRES_PASSWORD=<local-throwaway> -p 127.0.0.1:55918:5432 postgis/postgis:17-3.5
cd backend/tms-api && TMS_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55918/postgres TMS_TEST_DB_USER=postgres \
  TMS_TEST_DB_PASSWORD=<local-throwaway> ./mvnw -o -B clean test \
  "-DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar -Xshare:off" \
  > ../../docs/superpowers/evidence/commercial-control-plane/runs/TM18-05-full-it.txt 2>&1; echo "EXIT=$?" >> ../../docs/superpowers/evidence/commercial-control-plane/runs/TM18-05-full-it.txt
docker rm -f tms_ccp18_pg
```
