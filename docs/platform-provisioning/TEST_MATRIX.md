# Platform provisioning — test matrix

Two suites. The token matrix needs no database and runs anywhere; the provisioning suite runs against
a **real PostgreSQL** — never H2, because the schema depends on PostGIS, partial unique indexes,
`EXCLUDE` constraints and row level security, and a test that runs on H2 proves none of them.

Every key used in a test is generated for that test and thrown away. **The QAS key is never read by a
test, and no private key exists in this repository.**

## Running them

```bash
# With Docker available, the harness starts its own PostgreSQL:
./mvnw -B test -Dtest=PlatformTokenVerifierTest,PlatformProvisioningApiIntegrationTest

# Without Docker, point the harness at a local disposable PostgreSQL 17 + PostGIS:
TMS_TEST_DB_URL=jdbc:postgresql://localhost:55450/postgres \
TMS_TEST_DB_USER=tms_test TMS_TEST_DB_PASSWORD=… \
  ./mvnw -B test -Dtest=PlatformTokenVerifierTest,PlatformProvisioningApiIntegrationTest
```

## 1. Machine token — `PlatformTokenVerifierTest` (17, no database)

The clock is fixed: a matrix whose result depends on how long the suite took to get there is a
matrix that gets re-run until it passes.

| # | Case | Expected | Result |
|---|---|---|---|
| 1 | valid token | accepted; `sub`, `jti`, scopes carried | PASS |
| 2 | `actor_id` / `actor_role` present | carried for audit, grant nothing | PASS |
| 3 | `scope` sent as a JSON list | accepted | PASS |
| 4 | expired | `invalid-m2m-token` | PASS |
| 5 | wrong issuer | `invalid-issuer` | PASS |
| 6 | wrong audience | `invalid-audience` | PASS |
| 7 | **HS256** — public key as shared secret | `invalid-m2m-token` | PASS |
| 8 | **`alg=none`** | `invalid-m2m-token` | PASS |
| 9 | RS256, Supabase-shaped (a human token) | `invalid-m2m-token` | PASS |
| 10 | signed by a different EC key | `invalid-m2m-token` | PASS |
| 11 | missing `jti` | `invalid-m2m-token` | PASS |
| 12 | missing `sub` | `invalid-m2m-token` | PASS |
| 13 | missing `scope` | `invalid-m2m-token` | PASS |
| 14 | lifetime over 300s | `invalid-m2m-token` | PASS |
| 15 | opaque credential (the legacy integration shape) | `invalid-m2m-token` | PASS |
| 16 | feature disabled | `feature-not-configured` | PASS |
| 17 | **a private key configured** where the public one belongs | `feature-not-configured` | PASS |

Case 17 matters more than its size suggests: it would have *worked*, because an EC private key
encodes its public half. Failing loudly is the point.

## 2. Provisioning — `PlatformProvisioningApiIntegrationTest` (17, real PostgreSQL)

| # | Case | Expected | Result |
|---|---|---|---|
| 1 | create | 201; organization, company, `app_user` with `auth_user_id IS NULL`, **organization-wide** membership with `ORGANIZATION_ADMIN`, mapping row, `SUCCEEDED` record carrying `sub`, `jti`, `actor_id`, correlation id | PASS |
| 2 | read back | 200; mapping, state, codes — and **no** business data | PASS |
| 3 | unknown tenant | 404 | PASS |
| 4 | replay: same key, same body | 200, `replayed: true`, same `provisioningId`, nothing created twice | PASS |
| 5 | same key, different body | 409 `idempotency-conflict` | PASS |
| 6 | no `Idempotency-Key` | 400 `idempotency-key-required`, nothing created | PASS |
| 7 | same tenant, different key | 409 `tenant-already-provisioned`, still one mapping | PASS |
| 8 | **rollback**: administrator belongs to another tenant | 409 `admin-email-conflict`; **no** organization, **no** company, **no** mapping; a `FAILED` record survives | PASS |
| 9 | **concurrency**: two simultaneous calls, one key | one 201 and one 200; exactly one tenant, one mapping, one record | PASS |
| 10 | read-scope token on create | 403 `missing-scope` | PASS |
| 11 | no token | 401 | PASS |
| 12 | legacy `Basic` integration credential | 401 | PASS |
| 13 | control-plane token on the **human** API (`/api/v1/me`) | 401 | PASS |
| 14 | control-plane token on the **partner** API (`/integration/v1/...`) | 401 | PASS |
| 15 | slug that cannot become a TMS code | 400 `validation-failed` | PASS |
| 16 | `deploymentMode` other than `SHARED` | 400 `validation-failed` | PASS |
| 17 | body with no `controlPlaneTenantId` | 400 | PASS |

Case 8 is the atomicity test as well as the conflict test, and deliberately so: the refusal happens
*after* the organization and company rows have been inserted, so "nothing was left behind" is a claim
about a real rollback rather than about validation that ran first.

Case 9 asserts `201` **and** `200` — not "at least one succeeded". Two 201s would mean two tenants;
a 409 would mean the lock was doing the work a replay should do.

## 2b. Operational lifecycle (`lifecycle.v1`, ADR-021)

| Suite | Runs without Docker | What it proves |
|---|---|---|
| `PlatformLifecycleApiTest` (14) | yes | routes through the real MasterAdmin chain: PUT/GET, replay, `VERSION_CONFLICT`, `STALE_LIFECYCLE`, 404, `PRODUCT_MISMATCH`, `WRONG_ENVIRONMENT`, `INVALID_LIFECYCLE` (14 malformed bodies), scope separation, 401, `JTI_REPLAYED`, other methods denied |
| `TenantSuspensionFilterTest` (10) | yes | writes refused, reads never consult the gate, unscoped passes, fail closed |
| `TenantSuspensionSecurityTest` (6) | yes | user chain: 423 `tenant-suspended` with `error`/`reason`, reads and `/me` continue and carry the state, other organizations write, reactivation resumes, membership first |
| `IntegrationApiTenancyTest$TenantSuspension` (3) | yes | partner chain: 423 before intake, reads continue, reactivation |
| `TenantSuspensionCoverageTest` (2) | yes | every write route is a company-scoped `/api/v1` route, an `/integration` route or the MasterAdmin channel; both tenant chains register the filter |
| `PlatformLifecycleIntegrationTest` (5) | **no** (Testcontainers) | V58 + `JdbcLifecycleRepository` on the whole Flyway history: version rules, guards, append-only, runtime role locked out, SECURITY DEFINER reads |

## 3. What these tests do not prove

- **Nothing has run against QAS.** No QAS URL, no deployed instance, no token minted by the real
  MasterAdmin key. Everything above is local.
- **The real MasterAdmin token has never been verified end to end**, because signing one needs the
  private key, which belongs to MasterAdmin. What *is* verified locally is that the QAS public key
  loads and is used: configured, the service answers `invalid-m2m-token` to a token signed by
  another key — a refusal that can only come from a key that parsed.
- **No load, concurrency-at-scale or volume testing.** Case 9 proves the invariant with two callers,
  not the behaviour of a hundred.
