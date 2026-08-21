# Overnight V3 - SaaS tenancy and security review

Scope: everything added by the overnight V3 run (commits `0c1229a..5647c0f`, Flyway migrations
V14-V21) audited against the tenancy model of record - ADR-003 (Organization/Company scoping),
ADR-004 (schema exposure) and ADR-005 (tenant RLS runtime role).

Question asked of every new capability: **can a caller in company A reach, reference, or write
anything belonging to company B?**

Reviewed path, per module: `UI/API -> Controller -> Service -> Repository -> DB -> Security -> Tests`.

**Outcome: no P0 or P1 tenancy defect found in application or database code.** One P1 was found
in the *test suite* - the guard that proves new tables carry a tenant policy had gone stale - and
is fixed here. Remaining items are P2, recorded below.

---

## 1. What was added, and where the tenant boundary sits

| Migration | New tables | Tenant key |
|---|---|---|
| V14 | `location`, `location_role` | `company_id`; `location_role` via parent |
| V15 | `location_frequency` | `company_id` |
| V16 | *(columns only)* `carrier.external_reference`, `vehicle.external_reference`, `trip.planning_date` | existing |
| V17 | `order_import_batch` | `company_id` |
| V18 | `integration_client`, `integration_client_scope`, `integration_request` | `company_id`; scope via parent |
| V19 | *(columns only)* `trip.shipment_number`, `trip.route_id` | existing |
| V20 | `shipment_outbox_event` | `company_id` |
| V21 | `import_batch` | `company_id` |

34 tables now exist in `tms`. 26 carry `p_tenant_company_scope`, 8 are the identity/authorization
catalogue carrying `p_backend_managed`. No table is policy-less.

---

## 2. Findings by control

### 2.1 Company ownership on every business object - PASS

Every new table either carries `company_id` with a foreign key to `tms.company`, or is a child
whose tenant is its parent's (`location_role`, `integration_client_scope`) - the idiom V13 already
established for `frequency_weekly_rule` and `transport_order_line`. A `company_id` copy on a child
was deliberately not added, because it is denormalised state that can drift from the parent.

Evidence: `V14:84`, `V15:49`, `V17:134`, `V18:64,213`, `V20:36`, `V21:27`.

### 2.2 No IDOR via a UUID from another company - PASS

The repository layer has no unscoped lookup at all. Every finder that takes an id also takes a
company:

```
grep -rn "findById(" --include=*.java src/main | grep -v "AndCompanyId"   ->   no matches
```

`CarrierRepository`, `VehicleRepository`, `VehicleTypeRepository`, `LocationRepository`,
`LocationFrequencyRepository`, `OriginRepository`, `DestinationRepository`, `RouteRepository`,
`ZoneRepository`, `TransportOrderRepository`, `TripRepository`, `PlanningRunRepository`,
`IntegrationClientRepository` and `IntegrationRequestRepository` all expose
`findByIdAndCompanyId` / `findByIdInAndCompanyId` and nothing weaker. The few finders without a
company in the signature take a parent id that the caller already resolved company-scoped
(`FrequencyExceptionRepository`, `TripOrderAssignmentRepository`, `RouteStopRepository`,
`TransportOrderLineRepository`), and their tables are policed through the parent at the DB level.

The company itself is never taken from the request body. User-facing endpoints resolve it from
`CompanyScope`, which `CompanyScopeFilter` validates against the caller's membership snapshot
before Spring evaluates `@PreAuthorize`. Machine endpoints resolve it from the credential.

Authorization coverage: every business endpoint carries `@PreAuthorize`. The three endpoints
without one are `MeController` (principal-scoped by definition), `IntegrationIdentityController#ping`
(authenticated by the credential itself) and `SystemInfoController#info`.

### 2.3 Composite tenant-safe foreign keys - PASS

Every new cross-table reference has the `(id, company_id)` composite twin alongside the plain FK,
so a mismatched pair is impossible in the database and not merely unlikely in the service:

| Reference | Composite constraint |
|---|---|
| `location.zone_id` | `fk_location_zone_company` (V14:121) |
| `origin.location_id` | `fk_origin_location_company` (V14:211) |
| `destination.location_id` | `fk_destination_location_company` (V14:217) |
| `location_frequency.location_id` | `fk_location_frequency_location_company` (V15:56) |
| `location_frequency.frequency_id` | `fk_location_frequency_frequency_company` (V15:60) |
| `integration_request.integration_client_id` | `fk_integration_request_client_company` (V18:218) |
| `trip.route_id` | `fk_trip_route_company` (V19:104) |
| `shipment_outbox_event.trip_id` | `fk_shipment_outbox_event_trip_company` (V20:42) |

No new reference was found without its composite twin.

### 2.4 RLS coverage for new tenant tables - PASS (guard repaired)

All eight new tables call `ENABLE ROW LEVEL SECURITY` and create a policy in the same migration,
`FOR ALL` with both `USING` and `WITH CHECK` - never `USING` alone, which would permit a write
into another company and merely hide the result.

**P1 (fixed in this change): the test that guards this had gone stale.**
`SchemaExposureIntegrationTest` compared the live schema against two hand-written constants,
`APPLICATION_TABLES` and `TENANT_SCOPED_TABLES`. Both were last updated for V14 and were missing
the seven tables added by V15, V17, V18, V20 and V21. Because the assertions are exact-match, the
tests would have *failed* rather than passed silently - but they are gated on Docker, which is
unavailable in this environment, so 334 database tests skip and the drift went unnoticed for four
commits.

Two changes:

1. The two constants are brought up to date (34 tables / 26 tenant-scoped).
2. A **self-maintaining** assertion is added that cannot go stale, because it asks PostgreSQL the
   structural question instead of comparing to a list: *every table carrying a `company_id` column
   must carry `p_tenant_company_scope`*, with `membership` as the single declared exception (it
   defines the tenant, so it cannot be keyed on one). A ninth table added by a future migration
   that forgets its policy now fails without anyone remembering to edit a constant. A companion
   assertion refuses any table left with RLS enabled and no policy at all - which does not leak,
   but denies every row to `tms_app` and would surface only as a production outage.

### 2.5 The integration credential cannot cross a company - PASS

The strongest form of the rule, because the client is never asked which tenant it wants:

- The bearer token is `clientId.secret`. `IntegrationAuthenticationService` resolves the client
  and takes the company from `client.companyId()` - there is no company input to validate.
- `X-Company-Id` naming a different company is **refused, not ignored**
  (`IntegrationAuthenticationFilter:112`), so a partner who copied the header from the user-facing
  documentation is told rather than quietly writing into whichever tenant their key belongs to.
- The credential resolves its company through `JdbcCompanyScopeLoader`, which returns a
  `CompanyScope` with an **empty permission set**. Combined with authorities that are integration
  scopes only, a partner credential cannot reach a user-facing endpoint even though that endpoint
  is company-scoped too - containment by construction rather than by routing.
- `TenantScopedDataSource` keys on the `CompanyScopedAuthentication` interface, not on the
  user token type, so the machine surface - the one with no human reading the result - is covered
  by RLS rather than being the single path that is not.
- Authentication itself runs unscoped on the owner connection, which is correct and unavoidable:
  resolving a client id to its company is what *decides* the tenant. Everything after it runs as
  `tms_app` with that company published.

Secret handling: 256-bit CSPRNG secret, SHA-256 at rest, `MessageDigest.isEqual` constant-time
comparison, returned exactly once at create/rotate, never stored in plaintext, never logged,
never on the authentication object (`getCredentials()` returns `null` deliberately). The choice of
a fast digest over a KDF is justified in `IntegrationSecrets` by the input's entropy and is sound.
No Supabase service-role key is involved anywhere in this path.

### 2.6 An import cannot reference another company's master data - PASS

Structurally, not merely by validation: **no import template accepts a UUID.** All 54 import
columns across Location, Carrier, Vehicle, VehicleType and Order imports are business codes
(`CARRIER_CODE`, `VEHICLE_TYPE_CODE`, `ZONE_CODE`, `ORIGIN_CODE`, `DESTINATION_CODE`, ...). Codes
are resolved with `findByCompanyIdAndCodeIn(scope.companyId(), codes)`, so another company's code
does not resolve and is reported as an unknown code - indistinguishable from one that never
existed, which is also the right answer for information disclosure.

The same holds for the inbound integration API: `OrderUpsertRequest` and `LocationUpsertRequest`
contain no UUID field at all.

Already covered by tests: *"another company's zone code cannot be reached, and is reported like any
unknown code"* (Location import), *"another company's carrier code ..."* and *"another company's
vehicle type code ..."* (Vehicle import), *"another company's origin code ..."* and *"the same
external reference in another company is a different order"* (Order import).

### 2.7 The outbound shipment read cannot cross a company - PASS

`IntegrationShipmentService` sources the company from `principal.companyId()` on all three
endpoints (`search`, `find`, `searchEvents`); no company reaches it from the request. Below it,
`ShipmentPublicationAdapter` and `TripViewAssembler` resolve every reference through
`...InCompany(ids, companyId)` ports - runs, origins, vehicles, carriers, routes, destinations and
orders alike - so a detail response cannot assemble a foreign row into a legitimate shipment.
`findByShipmentNumberAndCompanyId` makes a shipment that exists only in company B a 404 for
company A rather than a 403, so the number space is not probeable.

**Test gap found and closed:** `shipment_outbox_event` (V20) was the one new tenant table with no
database-level tenancy test - it appeared only in `PlanningApiIntegrationTest`. Added
`ShipmentOutboxTenancyIsolationIntegrationTest`, covering RLS read filtering, the fail-closed
unscoped transaction, `WITH CHECK` on insert, `UPDATE`/`DELETE` reach, and both directions of the
composite foreign key. This table deserved its own class because of what consumes it: a partner
polling with a machine credential and no human reading the answer.

### 2.8 Google Maps introduces no server secret - PASS

The key is browser-side only (`VITE_GOOGLE_MAPS_API_KEY`), referenced in exactly four frontend
files and in **no Java file**. No key value is committed; `.env`/`.env.*` are git-ignored with
`.env.example` whitelisted, and no `.env` file is tracked. `.env.example` documents the required
HTTP-referrer and API restrictions in Google Cloud Console, and the app degrades to manual
latitude/longitude entry when the key is absent rather than failing to load.

### 2.9 Logs contain no credentials or tokens - PASS

Seven log statements exist in the `integration` package. All carry the client id, which is public
by design; none carries a secret, a token, or a payload. `IntegrationAuthenticationService` logs
the real rejection reason server-side while returning one indistinguishable message to the caller,
so a partner can debug a misconfiguration without an attacker learning whether a client id exists.

The `Authorization` header is read in four places, always for a presence check or to extract the
token, never logged. Production log levels are `root: WARN`, `com.ebim.tms: INFO`.

### 2.10 Flyway history - PASS

`git diff 0c1229a..HEAD -- db/migration` reports **8 files changed, 1350 insertions(+), 0
deletions(-)**: V1-V13 are byte-for-byte unchanged and only V14-V21 were added. No competing
Supabase migration history exists.

### 2.11 No direct business data access from the browser - PASS

The Supabase client appears in three source files only - `supabaseClient.ts`, `AuthContext.tsx`
and `env.ts` - and is used for sign in / refresh / sign out. There is not a single `.from(...)` or
`.rpc(...)` business call in the frontend; every grep hit is `Array.from`. All business data flows
`React -> Spring Boot -> PostgreSQL` as ADR-001 requires.

---

## 3. Unresolved items

### P0 - none

### P1 - none outstanding

The single P1 (stale RLS guard, section 2.4) is fixed in this change. It is recorded rather than
dropped because the *reason* it went unnoticed is still true: the database tests cannot run in this
environment, so nothing would have caught it before a deployment.

### P2 - accepted, tracked

**P2-1. Shipment, order and plan numbers come from cluster-wide sequences.**
`tms.shipment_number_seq` (V19), `tms.transport_order_number_seq` (V10) and
`tms.planning_run_number_seq` (V11) are global, and `uq_trip_shipment_number` is a global unique
constraint rather than a per-company one. A tenant can therefore infer other tenants' activity
volume from the gaps between the numbers it is assigned. No data crosses the boundary - the read
APIs are all company-scoped - but the *rate* of other tenants' operations is observable. V19
continued an existing V10/V11 pattern rather than introducing a new class of issue. Fixing it means
a per-company sequence or an opaque identifier, which is a schema change worth its own ADR.

**P2-2. Database tests cannot be executed in this environment.**
Docker is unavailable (WSL has no installed distribution) and the local PostgreSQL 18 has no
PostGIS, which V1 requires and V6/V7/V14 depend on for generated `geography` columns. 334 tests
skip, including every assertion in sections 2.4 and 2.7. The tests added and repaired here compile
but **have not been executed**. This is what allowed P1 to go unnoticed for four commits, and it
will allow the next one too. Highest-value environment fix available.

**P2-3. `order_import_batch` (V17) and `import_batch` (V21) are near-duplicates.**
Both record a company-scoped import audit row with the same shape. Both are correctly tenant-keyed
and policed, so this is a consolidation question, not a security one - but two tables meaning the
same thing is how one of them later gets a control the other does not.

---

## 4. Evidence index

| Claim | Where to re-run it |
|---|---|
| No unscoped `findById` | `grep -rn "findById(" --include=*.java src/main \| grep -v AndCompanyId` |
| Every endpoint authorized | endpoint count vs `@PreAuthorize` count per controller |
| V1-V13 immutable | `git diff --stat 0c1229a..HEAD -- backend/tms-api/src/main/resources/db/migration/` |
| Every table policed | `SchemaExposureIntegrationTest#everyCompanyColumnIsPoliced` |
| No policy-less table | `SchemaExposureIntegrationTest#noTableIsLeftWithoutAPolicy` |
| Outbox tenancy | `ShipmentOutboxTenancyIsolationIntegrationTest` |
| Credential tenancy | `IntegrationApiTenancyTest`, `IntegrationTenancyIsolationIntegrationTest` |
| Import cross-company codes | `LocationImportApiIntegrationTest`, `VehicleImportApiIntegrationTest`, `OrderImportApiIntegrationTest` |
| Outbound read tenancy | `IntegrationShipmentApiTest` |
| No browser business access | `grep -rn "\.from(\|\.rpc(" frontend/tms-web/src` |

Test run backing this review (`./mvnw test`, after the changes in this commit): **671 tests run,
0 failures, 0 errors, 329 skipped - BUILD SUCCESS**. Every skip is a Docker-gated database test,
including all 6 of `ShipmentOutboxTenancyIsolationIntegrationTest` and all 10 of
`SchemaExposureIntegrationTest`. Those two classes are compiled and wired but **were not
executed** - see P2-2. Nothing in this review claims a database assertion passed.
