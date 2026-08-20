# ADR-006 - Canonical Location master (`ADR_LOCATION_MODEL`)

- Status: Accepted
- Date: 2026-08-20
- File name: `docs/architecture/ADR_LOCATION_MODEL.md` (the name Job 01 of the overnight V3 pack
  asks for). It is the sixth ADR in the sequence and is referenced as ADR-006 elsewhere.
- Supersedes: nothing. It **narrows** the "deliberate simplification to keep" paragraph of
  `docs/architecture/OTM_DOMAIN_ALIGNMENT_V1.md` section 2.1, which said the Origin/Destination
  split should only be revisited if the duplicate-DC problem became a real cost.
- Related: ADR-002 (Flyway owns application DDL), ADR-003 (Company is the tenant scope),
  ADR-005 (tenant RLS through `tms_app`).

## Context

### What exists today

`tms.origin` (V6) and `tms.destination` (V7) are two independent company-scoped masters that
both describe *a physical place*:

| | `tms.origin` | `tms.destination` |
|---|---|---|
| Type enum | `WAREHOUSE`, `DISTRIBUTION_CENTER`, `PLANT`, `HUB`, `OTHER` | `CUSTOMER`, `STORE`, `BRANCH`, `HUB`, `DISTRIBUTION_CENTER`, `DELIVERY_POINT` |
| Address | one `address` line | `address`, `address_reference`, `district`, `province`, `department`, `country` |
| Coordinates | `latitude`/`longitude` + generated `geography` | `latitude`/`longitude` + generated `geography` |
| Zone | - | `zone_id` |
| Service time | - | `service_time_minutes` |
| Time zone | `time_zone` | - |
| External reference | `external_reference` (not unique) | `external_reference` (not unique) |

Both are referenced by foreign key from tables that are already in production shape:

| Referencing table | Column | Migration |
|---|---|---|
| `tms.route` | `origin_id` (NOT NULL) | V8 |
| `tms.route_stop` | `destination_id` (NOT NULL) | V8 |
| `tms.transport_order` | `origin_id`, `destination_id` (both NOT NULL) | V10 |
| `tms.planning_run` | `origin_id` (NOT NULL) | V11 |
| `tms.trip_stop` | `destination_id` (NOT NULL) | V11 |

Each of those also carries a **composite** tenant FK (`(origin_id, company_id)` ->
`tms.origin (id, company_id)`), which is what makes a cross-company reference impossible at the
database level.

### The problem

1. **A place that both ships and receives must be created twice**, with two codes and two
   independently maintained addresses, and nothing links the two rows. A distribution centre is
   the obvious case: `DISTRIBUTION_CENTER` and `HUB` are values of *both* enums.
2. **The two shapes are asymmetric for no domain reason.** An origin has no district/province
   and no service time; a destination has no time zone. Neither absence is a business rule.
3. **Neither table has an idempotency key.** `external_reference` exists on both but carries no
   uniqueness constraint, so an inbound integration cannot use it to deduplicate - while
   `tms.transport_order` (V10) already has the `(external_source, external_reference)` pair the
   integration work needs.
4. OTM - the conceptual reference this product borrows vocabulary from - models **one**
   `Location`, with the role it plays (source, destination, depot) being contextual.

### The constraint that shapes the decision

**This work cannot be verified against a database.** Docker Desktop on the development host
returns HTTP 500 because the WSL backend has no distribution, and the native PostgreSQL 18
installation has no PostGIS, which migration V1 requires. Every Testcontainers-backed test in
this repository - all of the migration, constraint, RLS and end-to-end suites - is skipped, not
run. That is a standing environment blocker recorded in `docs/overnight-v3/BASELINE.md` (E-1).

A refactor that rewrites five foreign keys across five tables, and whose correctness can only
be established by executing it, is not a refactor to perform blind.

## Decision

**Introduce `tms.location` as the canonical master, additively. Keep `tms.origin` and
`tms.destination` as compatibility projections of it. Change no existing foreign key.**

Rejected: full unification now (see "Alternatives", 1).

### 1. Schema (migration V14)

`tms.location` is company-scoped and carries the union of what the two legacy tables carry, plus
what neither had:

| Concern | Column |
|---|---|
| Company scope | `company_id` (NOT NULL, FK, leading index, composite `(id, company_id)` unique) |
| Identity | `code` (unique per company), `external_system` + `external_reference` (unique pair per company, partial) |
| Name | `name` |
| Type | `location_type` - the **union** of both legacy enums, so backfill is lossless |
| Address | `address`, `address_reference` |
| Locality | `district`, `province`, `department`, `country` |
| Time zone | `time_zone` |
| Coordinates | `latitude`, `longitude` + `geo_point geography(Point,4326)` GENERATED, GiST-indexed |
| Service time | `service_time_minutes` |
| Zone | `zone_id`, with the composite `(zone_id, company_id)` FK that makes a cross-company zone impossible |
| State | `active` |
| Audit | `created_at`, `updated_at`, `created_by`, `updated_by` |

`tms.location_role` holds the roles, one row per role, so a location may hold several. It is a
pure child of `tms.location` (no `company_id` of its own), the same shape
`tms.frequency_weekly_rule` has under `tms.frequency`.

### 2. Role vocabulary, and why it is not the same thing as the type

Job 01 lists roles as `ORIGIN`, `DESTINATION/SHIP_TO`, `STORE`, `DC`, `PLANT`, `HUB`, `OTHER`.
That list mixes two different questions, and the model keeps them apart while implementing all
seven values:

- **`location_type` answers "what is this place".** One value per location.
- **`location_role` answers "what may this place do".** Many values per location.

Of the seven roles, exactly two carry behaviour today:

| Role | Behaviour |
|---|---|
| `ORIGIN` | The location has a `tms.origin` projection, so it can be a route origin, an order origin and a planning-run origin. |
| `SHIP_TO` | The location has a `tms.destination` projection, so it can be a route stop, an order destination and a trip stop. |
| `STORE`, `DC`, `PLANT`, `HUB`, `OTHER` | Classification only: filtering and reporting. They project nothing and gate nothing. |

This is stated explicitly rather than left implicit, because "role" that sometimes means
capability and sometimes means category is how a master-data model rots.

### 3. Identity: `location_id` is the mapping, id equality is a bonus

`tms.origin.location_id` and `tms.destination.location_id` are added as nullable columns with a
composite `(location_id, company_id)` FK and a partial unique index. That column - not a naming
or id convention - is the authoritative mapping, which is what makes the eventual unification a
single mechanical statement per referencing table:

```sql
UPDATE tms.route r SET origin_id = o.location_id FROM tms.origin o WHERE o.id = r.origin_id;
-- ...then repoint the foreign key at tms.location and drop the legacy table.
```

On top of that, the V14 backfill deliberately gives every pre-V14 row

```
location.id == origin.id       when the group has an origin
location.id == destination.id  otherwise
```

so for all data that existed before this migration those `UPDATE`s touch no value at all. Rows
created afterwards through either API get ordinary generated ids and rely on `location_id`,
which costs nothing because the statement above is correct either way. Choosing not to force id
equality on new rows is what keeps `Origin` and `Destination` on the id strategy they already
had - this migration changes no existing entity's persistence behaviour.

`location_id` stays **nullable** rather than being tightened to `NOT NULL` after the backfill,
for a concrete reason: the integration and constraint test suites seed `tms.origin` and
`tms.destination` with direct SQL, and a `NOT NULL` link would turn every one of those fixtures
into ceremony that tests nothing. The invariant "every origin has a location" is therefore
application-enforced, and that is recorded as debt D-2.

### 4. Backfill: merge on code, never invent a code

The V14 backfill is a `FULL OUTER JOIN` of `tms.origin` and `tms.destination` on
`(company_id, code)`:

- an origin with no same-code destination -> one location, role `ORIGIN`;
- a destination with no same-code origin -> one location, role `SHIP_TO`;
- **both -> one location with both roles.**

Merging on an exact code match within a single company is the conservative reading, not the
aggressive one: this is precisely the duplicate-DC case section 2.1 of the OTM alignment
document identified, and the merge is what removes the duplication. The alternative - creating
`DC-LIMA` and `DC-LIMA-2` - would have kept the duplication *and* invented an identifier no
operator chose, *and* would have made a later code edit through the legacy API unresolvable.

Field precedence when a group has both sides:

| Field | Winner | Why |
|---|---|---|
| `id` | origin | Makes `location.id == origin.id` hold, so the unification `UPDATE` in section 3 is a no-op for pre-V14 data. |
| `name`, `location_type` | origin | The dispatch classification is the operationally significant one. |
| `address` | destination, falling back to origin | The destination shape is the richer one. |
| `address_reference`, `district`, `province`, `department`, `country`, `zone_id`, `service_time_minutes` | destination | The origin has no such column. |
| `time_zone` | origin, then the company's, then `UTC` | The destination has no such column. |
| `latitude`/`longitude` | origin, else destination - **as a pair** | Each legacy table already enforces both-or-neither, so coalescing each column independently cannot mix two places' coordinates. |
| `active` | active if **either** side is active | A merge must not make a row disappear from a list it was in. |
| `created_at` | earliest of the two | |
| `external_reference` | origin, then destination, **then deduplicated** | See below. |

**No legacy row is modified by the merge** beyond having its new `location_id` set. If a merge
turns out to be wrong for a particular pair, `tms.origin` and `tms.destination` still hold their
own untouched attributes, and the fix is to create a second location and repoint - not to
recover lost data.

**`external_reference` deduplication.** Neither legacy table constrains `external_reference`, so
duplicates may exist. The canonical column is unique per company, so the backfill keeps the
first occurrence per `(company_id, external_reference)` - ordered by `code`, then `id`, so it is
deterministic - and leaves the rest NULL on the canonical row. The legacy rows keep their own
values. Locations that lost the race simply do not claim the canonical external identity yet.

### 5. Write model and synchronisation

`tms.location` is the master. `tms.origin` and `tms.destination` are projections of it.
`LocationCompatibilityProjector` is the only class that maintains the relationship, in both
directions, inside the caller's transaction.

**Downward (canonical -> legacy), on every Locations API write.** Adding the `ORIGIN` role
creates the `tms.origin` row; removing it deactivates that row rather than deleting it, because
`route`, `transport_order` and `planning_run` may reference it and `ON DELETE RESTRICT` would
refuse anyway. Field mapping drops what the legacy shape cannot hold (zone and service time on
an origin, time zone on a destination) and narrows `location_type` to each legacy enum, with
`OTHER` and `DELIVERY_POINT` as the respective catch-alls.

**Upward (legacy -> canonical), on every Origins/Destinations API write.** The legacy endpoints
keep working exactly as before and additionally update their linked location. This is what stops
the two models drifting while both write paths exist.

Two asymmetries in the upward direction are deliberate:

- **`code` propagates, and a collision is a 409.** After V14 the two legacy code namespaces are
  merged into one canonical namespace. Renaming an origin to a code another location already
  holds is refused. This is a deliberate tightening and cannot brick an existing row, because
  the backfill guarantees `location.code == origin.code == destination.code` for every linked
  row, so an *unchanged* update always re-proposes the code the location already has.
- **`external_reference` propagates only when free.** A row whose external reference lost the
  deduplication race in section 4 must stay editable, so a collision leaves the canonical value
  alone instead of raising. This divergence exists only for references that were already
  duplicated before V14 and is visible in the Locations screen.

Fields the legacy shape does not have (zone, service time, time zone, the locality fields on an
origin) are never overwritten by an upward sync - they are simply not part of that payload.

### 6. What the UI does

A new **Ubicaciones** screen (`/masters/locations`) is the primary master-data screen: search,
filters, pagination, active state, and a right-side `TmsDrawer` for create/edit, following the
existing shared components.

**Origins and Destinations keep their screens, their routes and their endpoints.** Removing them
would strand a planner who needs to see what a route's origin is, and the synchronisation in
section 5 means keeping them creates no duplicate canonical rows. They are compatibility
surfaces now, and the plan is to retire them when unification completes.

## Alternatives considered

**1. Unify now: move every FK to `tms.location` and drop the legacy tables in V14.** This is the
preferred end state and it is what section 3 is designed to make cheap. It was rejected *for
this step* on one ground only: five NOT NULL composite foreign keys across five tables would be
dropped and recreated, and not one line of it could be executed on this host. "Do not drop old
tables in the same overnight step unless you can prove migration safety and all tests are
green" is the brief's own rule, and the tests cannot be green because they cannot run.

**2. A facade with no new table** - expose `/masterdata/locations` as a read/write view over the
two legacy tables. Zero migration risk, but it delivers no canonical record, no place to put the
idempotency key the integration work needs, no time zone on a destination, no service time on an
origin, and no path to unification. It renames the problem.

**3. A new table with no backfill**, adopted gradually. Removes the merge risk entirely, but the
new Locations screen would open empty on an existing company while Origins and Destinations show
real data - three screens over two datasets, which is more duplication, not less.

**4. Keep the split and add a `related_location_id` cross-link** between an origin and a
destination that are the same place. Cheapest option; leaves the asymmetric shapes, the missing
idempotency key and the double maintenance exactly as they are.

**5. Database triggers to keep the projections in sync** instead of a Java component. Rejected
by `CLAUDE.md`: Java owns business rules. A trigger would also be invisible to the service tests
and would fire for Flyway data migrations.

## Consequences

### Good

- One canonical record per physical place, with roles, in the vocabulary OTM uses.
- The idempotency identity (`external_system` + `external_reference`, unique per company) that
  inbound integrations need now exists on the master they will address.
- A destination has a time zone; an origin has a district, a zone and a service time.
- `geo_point` is a real, GiST-indexed PostGIS geography, ready for the Google Maps work in Job 02
  and for proximity queries later.
- Unification becomes one mechanical `UPDATE` per referencing table plus a foreign-key
  repoint, and for every row that existed before V14 that `UPDATE` changes no value.
- No existing foreign key, table or column was changed or dropped; V1-V13 are untouched.

### Compatibility debt this deliberately leaves behind

| # | Debt | Retire it by |
|---|---|---|
| D-1 | Three tables describe places (`location`, `origin`, `destination`). | Repointing the five FKs at `tms.location` and dropping the legacy tables. |
| D-2 | Two write paths exist and are kept consistent by application code, not by a constraint. | The same step as D-1. |
| D-3 | The downward projection is lossy: `location_type` narrows to each legacy enum, and zone/service-time/time-zone are dropped. Only the projections lose them; the canonical row keeps everything. | The same step as D-1. |
| D-4 | Pre-existing duplicate `external_reference` values leave some canonical rows without one. | An operator resolving the duplicates in the Locations screen. |
| D-5 | A merge on exact code match may unite two places that were genuinely different. | An operator splitting them; both legacy rows kept their own attributes, so nothing is lost. |
| D-6 | `route`, `transport_order`, `planning_run`, `trip_stop` still speak Origin/Destination, so planning cannot yet use a single location with two roles as both ends of a movement. | The same step as D-1. |
| D-7 | **The V14 migration and everything that depends on it are unexecuted.** No Testcontainers test ran on this host. | Running `./mvnw verify` on a machine with a working Docker daemon. |

### Verification status - read this before trusting the table above

| Layer | Status |
|---|---|
| Migration convention tests (no database) | Executed |
| Architecture/layering tests | Executed |
| Capability/permission tests | Executed |
| Frontend unit and component tests, typecheck, build | Executed |
| **Every Testcontainers test: migration, constraints, RLS, API integration, smoke** | **Skipped - Docker unavailable (BASELINE E-1)** |

D-7 is the honest headline: the design is reviewable, the SQL is not proven.
