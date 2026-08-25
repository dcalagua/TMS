# TMS by EBIM - data model (V1 identity, tenancy and master data)

Owner: Flyway migrations under `backend/tms-api/src/main/resources/db/migration` (ADR-002).
Scope of this document: the identity/tenancy baseline (V1-V5) plus the master data tables
Step 05 onwards adds on top of it, without changing its rules.

## 1. Where the schema lives

| Object | Location | Why |
|---|---|---|
| Application tables | schema `tms` | `supabase/config.toml` exposes only `public` and `graphql_public` through the Data API, so a separate schema removes the HTTP surface entirely |
| Flyway history | `tms.flyway_schema_history` | one history, next to the objects it describes |
| PostGIS | extension in `public` | shared platform capability; V6 (Step 05) is the first migration that uses it, for `tms.origin.location` |
| Supabase `auth`, `storage` | untouched | Supabase-managed; Flyway never creates or alters them |

Consequences for the backend: `spring.flyway.default-schema=tms` and
`hibernate.default_schema=tms` are set once in `application.yml`, so entities do not repeat
the schema on every `@Table`.

## 2. Entity relationships

```mermaid
erDiagram
    ORGANIZATION ||--o{ COMPANY : "owns"
    ORGANIZATION ||--o{ MEMBERSHIP : "scopes"
    COMPANY      ||--o{ MEMBERSHIP : "scopes (nullable)"
    APP_USER     ||--o{ MEMBERSHIP : "acts through"
    MEMBERSHIP   ||--o{ MEMBERSHIP_ROLE : "holds"
    ROLE         ||--o{ MEMBERSHIP_ROLE : "granted by"
    ROLE         ||--o{ ROLE_PERMISSION : "bundles"
    PERMISSION   ||--o{ ROLE_PERMISSION : "granted by"

    ORGANIZATION {
        uuid id PK
        text code UK "^[A-Z0-9][A-Z0-9_-]{1,31}$"
        text name
        bool active
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK "app_user"
        uuid updated_by FK "app_user"
    }
    COMPANY {
        uuid id PK
        uuid organization_id FK
        text code "unique per organization"
        text name
        text tax_identifier
        text time_zone "default UTC"
        bool active
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
    APP_USER {
        uuid id PK
        uuid auth_user_id UK "Supabase auth.users.id, no FK"
        text email UK "lower-cased, shape checked"
        text full_name
        bool active
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
    MEMBERSHIP {
        uuid id PK
        uuid app_user_id FK
        uuid organization_id FK
        uuid company_id FK "NULL = organization-wide"
        bool active
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
    MEMBERSHIP_ROLE {
        uuid membership_id PK,FK
        uuid role_id PK,FK
        timestamptz created_at
        uuid created_by FK
    }
    ROLE {
        uuid id PK
        text code UK "ORGANIZATION_ADMIN, COMPANY_ADMIN, PLANNER, VIEWER"
        text name
        text description
        text scope_level "ORGANIZATION | COMPANY"
        bool system_managed
        bool active
    }
    PERMISSION {
        uuid id PK
        text resource "iam.company, masterdata.origin, ..."
        text action "read, manage"
        text code UK "generated: resource:action"
        text description
    }
    ROLE_PERMISSION {
        uuid role_id PK,FK
        uuid permission_id PK,FK
        timestamptz created_at
    }
```

ASCII summary of the tenancy path used on every authenticated request:

    Supabase auth.users.id  (from the validated JWT)
              |
              v
    tms.app_user.auth_user_id -> app_user.id
              |
              v
    tms.membership  (active rows)  ->  organization_id [+ company_id | NULL]
              |
              v
    tms.membership_role -> tms.role -> tms.role_permission -> tms.permission

## 3. Design decisions and their reasons

### 3.1 Company is the operational scope, organization is the boundary

Per ADR-003. Business tables from Step 05 onwards carry `company_id` **only**.

`membership` is the single deliberate exception that carries both `organization_id` and
`company_id`, and the reason is recorded in the migration itself:

1. `company_id IS NULL` means *organization-wide* membership; without `organization_id`
   such a row could not name its tenant.
2. The composite foreign key `(company_id, organization_id) -> company (id, organization_id)`
   makes "the company must belong to this organization" a database guarantee. A single
   `company_id` column would leave that invariant to application code.

### 3.2 `app_user` is not tenant-scoped

A person may work for more than one organization, so identity is global and tenancy comes
from `membership`. `app_user` therefore has no `organization_id`.

### 3.3 No foreign key to `auth.users`

`app_user.auth_user_id` holds the Supabase Auth user id, unique and nullable, with no FK:

- Flyway must not depend on or lock the Supabase-managed `auth` schema (ADR-002);
- TMS stays portable if the identity provider changes;
- integration tests run on plain PostgreSQL, where `auth` does not exist;
- nullable because an administrator can create a profile before the invitation is accepted.

The mapping is established server-side by the backend after JWT validation (Step 03).

### 3.4 `membership_role` is a link table (refinement of ADR-003)

ADR-003 sketched a single role on the membership row. A many-to-many link is used instead
because a person frequently holds more than one role in the same company, and a single role
column would force duplicate membership rows for the same `(user, company)` pair - which
the unique indexes correctly forbid. The ADR decision (membership is the source of truth
for tenancy and role) is unchanged; only its cardinality is refined. `role.scope_level`
records whether a role belongs on an organization-wide or company-scoped membership; Java
enforces that pairing, because expressing it in SQL would need a trigger for no real gain.

### 3.5 Deletes never erase history

Every foreign key is `ON DELETE RESTRICT` except two pure configuration links:

| Link | Behaviour | Reason |
|---|---|---|
| `role_permission.role_id` | `CASCADE` | deleting a role legitimately removes its own grants |
| `membership_role.membership_id` | `CASCADE` | the link has no meaning without its membership |

Everything else - organizations with companies, companies with memberships, users that
appear in `created_by`/`updated_by` - refuses to be deleted. Long-lived rows are
**deactivated** through their `active` flag.

### 3.6 Actor columns only where an actor exists

`created_by`/`updated_by` reference `app_user` on `organization`, `company`, `app_user`,
`membership` and `membership_role`. `role` and `permission` have none: their rows are
reference data inserted by migration V3, where no actor exists. Inventing a system user to
fill the column would be worse than leaving it out.

### 3.7 `updated_at` is stamped by the database

`tms.set_updated_at()` runs `BEFORE UPDATE` on every table that has `updated_at`, so the
column is correct no matter which writer produced the change and cannot be spoofed by the
application. `now()` is the transaction timestamp, so `created_at` and `updated_at` are
equal for a row inserted and updated in the same transaction - that is intended.

The triggers deliberately carry no `WHEN (OLD.* IS DISTINCT FROM NEW.*)` filter: PostgreSQL
rejects whole-row references in a `BEFORE` trigger `WHEN` clause on tables with generated
columns, which `tms.permission` already has and the spatial tables of Steps 05/06 will have.

### 3.8 Normalization is enforced, not assumed

| Column | Rule |
|---|---|
| `organization.code`, `company.code` | `^[A-Z0-9][A-Z0-9_-]{1,31}$` |
| `role.code` | `^[A-Z][A-Z0-9_]{2,39}$` |
| `permission.resource` | dotted lower snake case, e.g. `masterdata.origin` |
| `permission.action` | lower snake case, e.g. `read`, `manage` |
| `app_user.email` | must equal `lower(btrim(email))` and match a basic address shape |
| names, `time_zone`, `tax_identifier` | not blank when present |

`company.time_zone` is only checked for blankness: a `CHECK` cannot query
`pg_timezone_names`. Java validates the IANA value.

## 4. Indexes

| Index | Purpose |
|---|---|
| `uq_organization_code` | organization codes are unique installation-wide |
| `uq_company_organization_code` | company codes unique per organization; also serves "companies of an organization" |
| `uq_company_id_organization` | target of the membership composite FK |
| `uq_app_user_email`, `uq_app_user_auth_user_id` | one profile per address, one profile per auth identity |
| `uq_membership_user_organization_company` (partial, `company_id IS NOT NULL`) | one membership per user and company |
| `uq_membership_user_organization_wide` (partial, `company_id IS NULL`) | one organization-wide membership per user |
| `ix_membership_app_user_active` (partial, `active`) | the hot path: JWT -> app_user -> active memberships |
| `ix_membership_company`, `ix_membership_organization` | administration screens: who may act here |
| `ix_role_permission_permission`, `ix_membership_role_role` | reverse lookups and impact analysis |
| `uq_permission_resource_action`, `uq_permission_code` | one row per capability, one stable string form |

Two partial unique indexes are used instead of `UNIQUE NULLS NOT DISTINCT` so the intent is
explicit and does not depend on a PostgreSQL 15+ behaviour change.

## 5. Reference data shipped by migrations

Migration V3 inserts the authorization catalogue - and nothing else. No organization, no
company, no user, no membership, no credential.

| Role | Scope | Grants |
|---|---|---|
| `ORGANIZATION_ADMIN` | ORGANIZATION | all 29 permissions |
| `COMPANY_ADMIN` | COMPANY | all except `iam.organization:manage` |
| `PLANNER` | COMPANY | reads company, master data and fleet; manages `orders.order` and `planning.trip` |
| `VIEWER` | COMPANY | read-only on company, master data, fleet, orders and trips |

Permissions cover the V1 modules only: `iam.*`, `masterdata.*`, `fleet.*`, `orders.order`,
`planning.trip`, `audit.log`. `audit.log` has a `read` permission and deliberately no
`manage`: the audit trail is append-only.

Demo tenants and users live in `supabase/seeds/local_dev_seed.sql`, outside the migration
history, and are verified by `LocalSeedIntegrationTest`.

## 6. What the tests prove

`backend/tms-api/src/test/java/com/ebim/tms/database/`:

| Test | Proves |
|---|---|
| `FlywayMigrationIntegrationTest` | the history applies to an empty database, validates, is idempotent, replays deterministically, and PostGIS is present |
| `TenancyConstraintIntegrationTest` | company code scoping, cross-organization membership refusal, membership uniqueness, RESTRICT deletes, cascade limits, email/code normalization, `updated_at` trigger, seeded catalogue |
| `SchemaExposureIntegrationTest` | RLS enabled on every table (including `origin`/`zone` since V6, `destination`/`frequency`/`frequency_weekly_rule`/`frequency_exception` since V7, `route`/`route_stop` since V8, and `carrier`/`vehicle_type`/`vehicle` since V9, `transport_order`/`transport_order_line` since V10, and `planning_run`/`trip`/`trip_stop`/`trip_order_assignment` since V11), no policies, not forced, PUBLIC and Supabase API roles denied, nothing published in `public` |
| `MasterDataConstraintIntegrationTest` | origin/zone code uniqueness is per-company, not installation-wide; FK to a real company; code normalization; the latitude/longitude pair and range checks; the generated `location` column reflects a valid pair and is `NULL` when coordinates are absent; `origin_type` is restricted to the catalogue; defaults and actor columns |
| `MasterDataDestinationFrequencyConstraintIntegrationTest` | the same class of proof as above, extended to V7: destination/frequency code uniqueness per company; destination coordinate pair/range checks and generated `location`; `destination_type` and nonnegative `service_time_minutes`; a destination's `zone_id` must belong to its own company even though the two FK columns are separate; weekly rule `day_of_week` range and per-frequency uniqueness and nonnegative `lead_time_days`; weekly rules and exceptions cascade-delete with their frequency; exception date uniqueness and non-blank note |
| `MasterDataRouteConstraintIntegrationTest` | the same class of proof, extended to V8: route code uniqueness per company; a route's origin/zone/frequency must belong to its own company even though the FK columns are separate; nonnegative reference distance/duration; a route stop's destination and company must both match its route; positive sequence; a destination cannot appear twice on one route; stops cascade-delete with their route; **the `DEFERRABLE INITIALLY DEFERRED` sequence constraint** - a two-stop in-place swap survives `COMMIT`, and a genuine unresolved duplicate still fails, just at `COMMIT` instead of at the statement |
| `FleetConstraintIntegrationTest` | the same class of proof, extended to V9: carrier/vehicle-type/vehicle code uniqueness per company; carrier tax-id pair uniqueness and normalization; carrier email normalization/shape when present; vehicle-type `max_weight_kg`/`max_volume_m3` strictly positive and `max_pallets` nonnegative-including-zero; optional dimensions positive when present; `body_type` restricted to the catalogue; the temperature-range coherence rule (requires `temperature_controlled`, `min <= max`); vehicle license-plate normalization/shape and per-company uniqueness; a vehicle's carrier and vehicle type must both belong to the vehicle's own company even though the FK columns are separate; override capacities positive/nonnegative when present; `availability_status` restricted to the catalogue; defaults and actor columns |
| `OrderConstraintIntegrationTest` | the same class of proof, extended to V10: `order_number` uniqueness is global (section 12.1) while the external-reference pair is per-company and partial (section 12.2); a reference with no source is rejected; an order's origin/destination must belong to its own company even though the FK columns are separate; time-window pair/order checks; `priority`/`status` restricted to their catalogues; `cancel_reason` requires `status = CANCELLED`; totals nonnegative; a line's quantity strictly positive, `uom` normalized, unit weight/volume positive when present, line number unique per order; lines cascade-delete with their order; defaults and actor columns |
| `PlanningConstraintIntegrationTest` | the same class of proof, extended to V11: an order has at most one **open whole-order assignment** across every trip, a closed one frees it again and both rows survive, and a `whole_order = false` allocation is deliberately outside the index (section 14.4); two concurrent transactions assigning the same order - the second blocks until the first commits, then fails; an assignment's trip and order must belong to its own company; the removal stamp is coherent in both directions; a `CONFIRMED` trip must carry a vehicle, a departure and its frozen capacity while a draft may carry none of them (section 14.5); a trip's vehicle must be its own company's; trip numbers unique per run; one open draft run per company/origin/date, freed by confirming or cancelling; `plan_number` globally unique; a run's origin must be its own company's; a stop's destination must be its own company's; one stop per destination; **the `DEFERRABLE` stop-sequence constraint** - an in-place reorder survives `COMMIT` while a genuine duplicate still fails at it; stops cascade-delete with their trip and a service window must be a real window |
| `ApplicationDatabaseStartupIntegrationTest` | the real Spring context boots with datasource + JPA + Flyway against PostgreSQL |
| `LocalSeedIntegrationTest` | the local seed still matches the schema and carries no credential |
| `MigrationConventionTest` | naming, contiguous versions, no destructive DDL, no `auth`/`storage` DDL, no tenant data in migrations, no `supabase/migrations` |

The origin/zone vertical slice itself (controller through repository, company scoping,
permissions) is proven end to end by
`backend/tms-api/src/test/java/com/ebim/tms/masterdata/api/OriginZoneApiIntegrationTest.java`
rather than repeated in the database-level test above - see
`docs/overnight/05_ORIGINS_ZONES.md` section 4 for that coverage. The destination/frequency
slice (Step 06) is proven the same way by
`backend/tms-api/src/test/java/com/ebim/tms/masterdata/api/DestinationFrequencyApiIntegrationTest.java`
- see `docs/overnight/06_DESTINATIONS_FREQUENCIES.md` section 3 for that coverage. The route
slice (Step 07) is proven the same way by
`backend/tms-api/src/test/java/com/ebim/tms/masterdata/api/RouteApiIntegrationTest.java` - see
`docs/overnight/07_ROUTES.md` section 5 for that coverage. The fleet slice (Step 08) is proven
the same way by `backend/tms-api/src/test/java/com/ebim/tms/fleet/api/FleetApiIntegrationTest.java`
- see `docs/overnight/08_FLEET.md` section 5 for that coverage. The orders slice (Step 09) is
proven the same way by `backend/tms-api/src/test/java/com/ebim/tms/orders/api/OrderApiIntegrationTest.java`
- see `docs/overnight/09_ORDERS.md` for that coverage.

## 7. Master data: origins and zones (Step 05, migration V6)

The first business masters built on top of the V1-V5 baseline, following every rule section 8
sets for the migrations after it unchanged: `company_id NOT NULL` with an FK to `tms.company` and an index
leading with it, `created_at`/`updated_at` plus the `set_updated_at` trigger, actor columns
(a real actor - the authenticated caller - exists for both tables), RLS enabled in the same
migration, and a normalized-code `CHECK` rather than convention alone.

```mermaid
erDiagram
    COMPANY ||--o{ ORIGIN : "scopes"
    COMPANY ||--o{ ZONE : "scopes"

    ORIGIN {
        uuid id PK
        uuid company_id FK
        text code "unique per company, ^[A-Z0-9][A-Z0-9_-]{0,31}$"
        text name
        text origin_type "WAREHOUSE | DISTRIBUTION_CENTER | PLANT | HUB | OTHER"
        text address
        numeric latitude "9,6 - both present or both absent with longitude"
        numeric longitude "9,6"
        geography location "GENERATED ALWAYS, Point/4326, GiST indexed"
        text time_zone "IANA id, checked non-blank; Java validates the value"
        text external_reference "optional EWM/external code, never a FK"
        bool active
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
    ZONE {
        uuid id PK
        uuid company_id FK
        text code "unique per company"
        text name
        text description
        bool active
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
```

### 7.1 `location` is generated, not written

`tms.origin.location` is `GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude),
4326)::geography) STORED`. Neither JPA nor the API ever populate it: `Origin` (the entity)
does not map the column at all, so create/update stay ordinary numeric-column CRUD
(architecture section 8's requirement) while the database derives the spatial value for a
future nearest-origin/within-radius query. `ST_MakePoint` propagates `NULL`, so an origin
with no coordinates simply has a `NULL` location - no `CASE` needed, and
`ck_origin_coordinates_pair` guarantees latitude/longitude are never half-set. `ix_origin_location`
is a GiST index maintained on every write starting now, so a later step's spatial query does
not need a second migration to add one retroactively.

### 7.2 `external_reference` is the EWM boundary, not a foreign key

Per the repository's independence rule (no shared internal tables or cross-product FKs
between TMS and EWM), `origin.external_reference` is a free-text optional column - a place to
record a future EWM warehouse id or similar - never a foreign key into another product's
schema. Nothing reads or validates it against an external system in V1.

### 7.3 Zones stay attribute-only in V1

`tms.zone` intentionally carries no geometry: code, name, optional description, active,
audit. The step brief asked for zone geofencing to be *possible* later without forcing
polygons in now; a future migration can add a nullable geometry column to this same table
without changing its shape or breaking existing rows, exactly like `location` was added to
`origin` without touching `organization`/`company`.

### 7.4 Indexes added by V6

| Index | Purpose |
|---|---|
| `uq_origin_company_code`, `uq_zone_company_code` | codes unique per company, free to repeat across companies (ADR-003) |
| `ix_origin_company`, `ix_zone_company` | the hot path: "list mine", company-scoped queries lead with `company_id` |
| `ix_origin_location` (GiST) | future spatial queries against `location`; not yet queried in V1, maintained from day one |

## 8. Master data: destinations and frequencies (Step 06, migration V7)

Follows V6's shape unchanged (section 9 below): `company_id NOT NULL` with an FK and a
leading index, actor columns, RLS enabled in this same migration, normalized-code `CHECK`s.

```mermaid
erDiagram
    COMPANY  ||--o{ DESTINATION : "scopes"
    COMPANY  ||--o{ FREQUENCY   : "scopes"
    ZONE     ||--o{ DESTINATION : "optionally groups"
    FREQUENCY ||--o{ FREQUENCY_WEEKLY_RULE : "owns"
    FREQUENCY ||--o{ FREQUENCY_EXCEPTION   : "owns"

    DESTINATION {
        uuid id PK
        uuid company_id FK
        text code "unique per company, ^[A-Z0-9][A-Z0-9_-]{0,31}$"
        text name
        text destination_type "CUSTOMER | STORE | BRANCH | HUB | DISTRIBUTION_CENTER | DELIVERY_POINT"
        text address
        text address_reference "landmark/access note"
        text district
        text province
        text department
        text country "default PE, not blank"
        numeric latitude "9,6 - both present or both absent with longitude"
        numeric longitude "9,6"
        geography location "GENERATED ALWAYS, Point/4326, GiST indexed"
        uuid zone_id "optional, composite FK guarantees same company as zone"
        integer service_time_minutes "nonnegative, default 0"
        text external_reference "optional EWM/external code, never a FK"
        bool active
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
    FREQUENCY {
        uuid id PK
        uuid company_id FK
        text code "unique per company"
        text name
        text description
        bool active
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
    FREQUENCY_WEEKLY_RULE {
        uuid id PK
        uuid frequency_id FK "cascades from frequency, no own company_id"
        smallint day_of_week "1=Monday..7=Sunday, unique per frequency"
        bool enabled
        time cutoff_time
        integer lead_time_days "nonnegative"
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
    FREQUENCY_EXCEPTION {
        uuid id PK
        uuid frequency_id FK "cascades from frequency, no own company_id"
        date exception_date "unique per frequency"
        bool service_override "true = extra service date, false = blackout"
        text note
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
```

### 8.1 `destination.zone_id` reuses the composite-FK idiom, not a new one

A destination's zone must belong to the destination's own company. Rather than trusting
application code alone, migration V7 adds `tms.zone.uq_zone_id_company UNIQUE (id,
company_id)` and a composite FK `fk_destination_zone_company FOREIGN KEY (zone_id,
company_id) REFERENCES tms.zone (id, company_id)` - the exact pattern `tms.membership` (V2)
uses for `company_id`/`organization_id`. `MATCH SIMPLE` (the default) means the FK is
satisfied whenever `zone_id IS NULL`, so an optional zone stays optional. Java (`
DestinationService.requireZoneInScope`) is still the primary check - it is what turns a
mismatch into a clean 400 instead of a raw constraint violation - the database constraint is
defense in depth for the same reason RLS is (architecture section 4.2).

### 8.2 Frequency is a header plus two owned child collections, not hardcoded booleans

The step brief is explicit that five or seven boolean columns on `frequency` would not be
future-friendly. `frequency_weekly_rule` carries the Monday-Sunday cadence (one row per
configured day, `enabled` plus optional `cutoff_time`/`lead_time_days`) and
`frequency_exception` carries date-specific overrides (an extra pickup or a blackout) -
completely separate concerns that can each evolve without touching the other or the header.
Both children cascade from `frequency` and carry no `company_id` of their own, the same shape
`tms.membership_role` uses as a pure child of `tms.membership` (V2): "the row has no meaning
without its parent."

`FrequencyService.update` replaces the whole weekly-rule set inside the same transaction as
the header update (`Frequency.replaceWeeklyRules`, diff by `day_of_week`: update a day
present in both the request and the database, add a new day, and let Hibernate's
`orphanRemoval` delete a day the request no longer includes) - so a rule can never be left
half-replaced or orphaned. Exceptions are not diffed as a set: each is created or deleted
individually through its own sub-resource (`POST`/`DELETE
/masterdata/frequencies/{id}/exceptions`), because a calendar override is an independent fact
about one date, not a slot in a fixed weekly grid.

### 8.3 A destination-frequency association table was deliberately not added in V7

The "Frequency model" brief recommends an explicit destination-frequency association (rather
than a rigid single FK) so multiple schedules can be modeled later. V7 does not add that
table: neither the Destinations nor the Frequencies frontend section of the brief asks for an
assignment screen, and nothing in Orders/Planning (not built yet) has a concrete requirement
for it. Building the join table now, with no reachable API and no screen, would be exactly
the speculative complexity the repository instructions ask to avoid - the same judgment V6
already made for zone geometry ("no geometry in V1... a future migration can add it without
changing this shape"). When Orders/Planning defines what "which frequency serves this
destination" needs to look like, add the table then; `destination`/`frequency` are already
shaped so a later join table can reference both without any change to either.

**Superseded by section 15.** Job 03 of the overnight-v3 pack is that concrete requirement, and
`tms.location_frequency` (migration V15) is that table - associating the canonical `tms.location`
(V14) rather than the legacy `tms.destination`, since V14 postdates this paragraph.

### 8.4 Indexes added by V7

| Index | Purpose |
|---|---|
| `uq_destination_company_code`, `uq_frequency_company_code` | codes unique per company, free to repeat across companies (ADR-003) |
| `ix_destination_company`, `ix_frequency_company` | the hot path: "list mine", company-scoped queries lead with `company_id` |
| `ix_destination_zone` (partial, `zone_id IS NOT NULL`) | "destinations in this zone" without indexing the common no-zone case |
| `ix_destination_location` (GiST) | future spatial queries against `location`, matching `ix_origin_location` (V6) |
| `ix_frequency_weekly_rule_frequency`, `ix_frequency_exception_frequency` | "rules/exceptions of this frequency", the only way either child table is ever queried |
| `uq_zone_id_company` (on `tms.zone`) | composite-FK target for `destination.zone_id`, see section 8.1 |

## 9. Master data: routes (Step 07, migration V8)

Follows V6/V7's shape unchanged (section 10 below): `company_id NOT NULL` with an FK and a
leading index, actor columns, RLS enabled in this same migration, normalized-code `CHECK`s.

```mermaid
erDiagram
    COMPANY  ||--o{ ROUTE      : "scopes"
    ORIGIN   ||--o{ ROUTE      : "starts"
    ZONE     ||--o{ ROUTE      : "optionally groups"
    FREQUENCY ||--o{ ROUTE     : "optionally schedules"
    ROUTE    ||--o{ ROUTE_STOP : "owns, ordered"
    DESTINATION ||--o{ ROUTE_STOP : "visited by"

    ROUTE {
        uuid id PK
        uuid company_id FK
        text code "unique per company, ^[A-Z0-9][A-Z0-9_-]{0,31}$"
        text name
        uuid origin_id FK "mandatory, composite-FK tenant guarantee"
        uuid zone_id "optional, composite-FK tenant guarantee"
        uuid frequency_id "optional, composite-FK tenant guarantee"
        numeric reference_distance_km "8,2 - planner-entered, nonnegative"
        integer reference_duration_minutes "planner-entered, nonnegative"
        bool active
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
    ROUTE_STOP {
        uuid id PK
        uuid route_id FK "cascades from route"
        uuid company_id FK "denormalized from route - see 9.1"
        uuid destination_id FK "composite-FK tenant guarantee"
        integer sequence "1-based, contiguous, server-assigned - see 9.2"
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
```

A master `tms.route` is a reusable planned corridor - company-scoped origin plus the ordered
`tms.route_stop` destinations - and is deliberately distinct from a future dynamically
calculated Trip route (deferred by decision, per the repository's OR-Tools/route-optimization
deferral): no geometry, no live position, no optimizer output, just a named sequence a planner
sets up once and a Trip can point at later.

### 9.1 `route_stop` carries its own `company_id`, unlike every other pure child so far

`tms.frequency_weekly_rule` and `tms.frequency_exception` (V7) are pure children of their
parent with no `company_id` of their own, because neither references another company-scoped
table. `tms.route_stop` is different: it references `tms.destination`, another company-scoped
table, which section 10 rule 6 requires a composite-FK tenant guarantee for - and that
guarantee needs `company_id` on the *referencing* row, not just the parent. So `route_stop`
denormalizes `company_id` from its route and carries two composite FKs: `(route_id,
company_id) -> route (id, company_id)` (the stop's company must match its own route's company)
and `(destination_id, company_id) -> destination (id, company_id)` (the stop's destination
must belong to that same company). This is the refinement rule 7 below captures: being a pure
child (no lifecycle without its parent) and needing its own `company_id` are independent
questions, not the same one.

`tms.route` itself needs the identical guarantee for `origin_id` (mandatory) and the optional
`zone_id`/`frequency_id`, reusing `tms.zone.uq_zone_id_company` (V7) and adding
`uq_origin_id_company`/`uq_frequency_id_company` in this migration.

### 9.2 Stop order is server-assigned from array order, not a client-supplied number

`RouteRequest.stops` is the whole ordered stop list (a plain `destinationIds` array of ids until
V24 gave a stop something to say beyond which place it is - see section 19); the server assigns
`sequence = 1..N` from the array's position (`Route.replaceStops`), so a request can never ask
for a duplicate or gapped sequence - "sequence starting at a consistent convention" from the
step brief is satisfied by construction rather than by validating a client-supplied number.
Reordering (the frontend's move up/down) resends the whole list in its new order and is applied
as an update-only diff keyed by `destination_id` (`Route.replaceStops`, the same
diff-and-replace-via-`orphanRemoval` shape `Frequency.replaceWeeklyRules` (V7) established), not
a delete-then-insert.

That diff necessarily passes a swap (two stops trading positions) through a transient duplicate
`(route_id, sequence)` pair mid-transaction. `uq_route_stop_route_sequence` is declared
`DEFERRABLE INITIALLY DEFERRED` so PostgreSQL checks it at `COMMIT` instead of at each
statement - the standard idiom for a reorderable unique-position list. See the V8 migration
comment on that constraint and `MasterDataRouteConstraintIntegrationTest` (which proves both
that a legitimate swap survives commit and that a genuine unresolved duplicate still fails, just
later) for the two-sided proof of this decision.

### 9.3 A destination may appear at most once per route

`uq_route_stop_route_destination UNIQUE (route_id, destination_id)` forbids a route from
stopping at the same destination twice. Documented decision, matching how the step brief asks
duplicates to be "explicitly forbidden or intentionally supported with a documented reason": a
master Route in V1 is a corridor of distinct stops; a legitimate "visit the same place twice"
itinerary belongs to Trip-level planning (not built yet), not a reusable master. Revisit only if
a concrete round-trip use case appears - the same bar section 8.3 sets for the deferred
destination-frequency association table.

### 9.4 List and detail deliberately return different shapes

The step brief is explicit that a route list must not load every destination for each row
(N+1). `RouteService.list` therefore never touches any route's `stops` collection; it resolves a
stop *count* per page with one batched `GROUP BY` query
(`RouteStopRepository.countByRouteIds`) and returns `RouteView` (count only). `RouteService.get`
(and create/update/activate/deactivate) returns `RouteDetailView`, which does include the
ordered stops with each one's resolved destination - fetched with the same batched
`findAllById` discipline `DestinationService.loadZones` (V7) established for a single route's
stop set, never one query per stop.

### 9.5 Deactivating an origin or destination does not erase route history

Deactivation only flips the `active` boolean; the row is never deleted, so every FK a route or
route stop holds remains valid and the composite-FK tenant guarantees keep working. A route (or
a route editor screen) can therefore keep resolving and displaying a stop whose destination has
since been deactivated - `RouteFormModal` on the frontend prefers the route's own
`destinationCode`/`destinationName` over the active-only dropdown fetch for exactly this reason.

### 9.6 Indexes added by V8

| Index | Purpose |
|---|---|
| `uq_route_company_code` | codes unique per company, free to repeat across companies (ADR-003) |
| `ix_route_company` | the hot path: "list mine", company-scoped queries lead with `company_id` |
| `ix_route_origin`, `ix_route_zone` (partial), `ix_route_frequency` (partial) | reverse lookups and the list filters |
| `ix_route_stop_route`, `ix_route_stop_destination` | "stops of this route" (the only way `route_stop` is queried per-route) and "routes touching this destination" |
| `uq_route_id_company` (on `tms.route`) | composite-FK target for `route_stop.route_id` |
| `uq_origin_id_company` (on `tms.origin`), `uq_destination_id_company` (on `tms.destination`), `uq_frequency_id_company` (on `tms.frequency`) | composite-FK targets `route`/`route_stop` need - the first cross-table references either table has had |

## 10. Fleet masters: carriers, vehicle types and vehicles (Step 08, migration V9)

Follows V6/V7/V8's shape unchanged (section 11 below): `company_id NOT NULL` with an FK and a
leading index, actor columns, RLS enabled in this same migration, normalized-code `CHECK`s, and
the composite-FK tenant guarantee (rule 6) for `tms.vehicle`'s references into `tms.carrier` and
`tms.vehicle_type`.

```mermaid
erDiagram
    COMPANY      ||--o{ CARRIER      : "scopes"
    COMPANY      ||--o{ VEHICLE_TYPE : "scopes"
    COMPANY      ||--o{ VEHICLE      : "scopes"
    CARRIER      ||--o{ VEHICLE      : "optionally operates"
    VEHICLE_TYPE ||--o{ VEHICLE      : "defaults capacity for"

    CARRIER {
        uuid id PK
        uuid company_id FK
        text code "unique per company, ^[A-Z0-9][A-Z0-9_-]{0,31}$"
        text business_name
        text tax_id_type "flexible free text, normalized upper - see 10.1"
        text tax_id_value "flexible free text, normalized upper"
        text contact_name "optional"
        text phone "optional"
        text email "optional, normalized lower, shape-checked"
        text external_reference "optional, free text - see 16.1"
        bool active
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
    VEHICLE_TYPE {
        uuid id PK
        uuid company_id FK
        text code "unique per company"
        text name
        numeric max_weight_kg "10,2 - strictly positive"
        numeric max_volume_m3 "10,3 - strictly positive"
        integer max_pallets "nonnegative, may be zero"
        numeric length_m "6,2 - optional, positive when present"
        numeric width_m "6,2 - optional, positive when present"
        numeric height_m "6,2 - optional, positive when present"
        text body_type "optional, small fixed catalogue"
        bool temperature_controlled
        numeric min_temperature_celsius "optional, requires temperature_controlled"
        numeric max_temperature_celsius "optional, requires temperature_controlled"
        integer axles "optional, >= 1"
        bool active
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
    VEHICLE {
        uuid id PK
        uuid company_id FK
        text code "unique per company"
        text license_plate "unique per company, normalized upper - see 10.2"
        uuid carrier_id "optional, composite-FK tenant guarantee"
        uuid vehicle_type_id "mandatory, composite-FK tenant guarantee"
        numeric max_weight_override_kg "10,2 - optional, positive when present"
        numeric max_volume_override_m3 "10,3 - optional, positive when present"
        integer max_pallets_override "optional, nonnegative when present"
        text availability_status "AVAILABLE | IN_MAINTENANCE | OUT_OF_SERVICE - see 10.3"
        text external_reference "optional, free text - see 16.1"
        bool active
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
```

### 10.1 `carrier.tax_id_type`/`tax_id_value` are a flexible pair, not a fixed enum

Unlike `origin_type`/`destination_type` (a small catalogue enforced with `CHECK ... IN (...)`),
a carrier's legal/tax identification is deliberately free text: identifier types vary by country
(RUC in Peru, RFC in Mexico, CNPJ in Brazil, EIN in the US, ...), and hardcoding a per-country
catalogue would either force TMS to add a migration for every new operating country or force an
awkward `OTHER` bucket. Both halves are still normalized (`upper(btrim(...))`, per section 3.8 -
"normalization is enforced, not assumed") so `uq_carrier_company_tax_id UNIQUE (company_id,
tax_id_type, tax_id_value)` actually catches `'ruc'`/`'RUC'` as the same type instead of letting
normalization drift create a duplicate the constraint cannot see.

### 10.2 A vehicle's license plate is unique per company, not installation-wide

A license plate is a real-world identifier a planner would expect to be globally unique - but
`uq_vehicle_company_license_plate` scopes it to `company_id` anyway, matching every other
master's code uniqueness (ADR-003) rather than carving out a special case. Two reasons:

1. **Consistency with the tenancy model.** Every other uniqueness rule in this document is
   company-scoped; a single globally-unique column would be a one-off exception with no
   corresponding capability (V1 has no installation-wide fleet registry).
2. **A global constraint is a cross-tenant information leak.** If plate uniqueness were
   enforced across companies, company A could learn whether company B has a vehicle with a
   specific plate simply by attempting to register it and observing a 409 instead of success -
   the same class of leak the rest of the API avoids by answering a cross-company read with 404,
   never 403 or 409 (see `RouteApiIntegrationTest.crossCompanyAccessIsBlocked` and its fleet
   equivalent, `FleetApiIntegrationTest.Vehicles.crossCompanyAccessIsBlocked`).

If a genuine cross-company fleet registry becomes a requirement, it is a new, explicitly
designed capability - not a side effect of tightening this constraint.

### 10.3 `vehicle.availability_status` is a current-state flag, not a scheduling calendar

The step brief asks for "availability baseline that does not pretend to be a full scheduling
calendar." `availability_status` is one column with three values (`AVAILABLE`,
`IN_MAINTENANCE`, `OUT_OF_SERVICE`) recording the vehicle's current operational state - not a
calendar of future slots, shifts or bookings. Trip/planning-level scheduling (deferred - not
built in V1) is a separate, later concern; this column intentionally does not anticipate its
shape.

### 10.4 Effective capacity is resolved in Java, not stored

`tms.vehicle`'s three override columns (`max_weight_override_kg`, `max_volume_override_m3`,
`max_pallets_override`) are independently nullable - a vehicle may override one, two, or all
three dimensions and fall back to its `tms.vehicle_type`'s defaults for the rest. Nothing in the
schema materializes the *resolved* value: `EffectiveCapacityResolver`
(`com.ebim.tms.fleet.application`) is the single place that computes it (vehicle override first,
otherwise the type default, per dimension), used by every read (`VehicleView.effectiveMaxWeightKg`
etc.) and available for Planning's future capacity checks
(`docs/architecture/OWNERSHIP_MATRIX.md`, "Capacity checks") without a second implementation of
the same rule.

### 10.5 Indexes added by V9

| Index | Purpose |
|---|---|
| `uq_carrier_company_code`, `uq_vehicle_type_company_code`, `uq_vehicle_company_code` | codes unique per company, free to repeat across companies (ADR-003) |
| `uq_carrier_company_tax_id` | a legal identifier is registered once per company - section 10.1 |
| `uq_vehicle_company_license_plate` | a plate is registered once per company - section 10.2 |
| `ix_carrier_company`, `ix_vehicle_type_company`, `ix_vehicle_company` | the hot path: "list mine", company-scoped queries lead with `company_id` |
| `ix_vehicle_carrier` (partial, `carrier_id IS NOT NULL`) | "vehicles of this carrier" without indexing the common owned-fleet case |
| `ix_vehicle_type` | "vehicles of this type", the list filter and the capacity-resolution lookup |
| `uq_carrier_id_company` (on `tms.carrier`), `uq_vehicle_type_id_company` (on `tms.vehicle_type`) | composite-FK targets `vehicle.carrier_id`/`vehicle.vehicle_type_id` need |

## 12. Orders (Step 09, migration V10)

Follows V6-V9's shape unchanged (section 13 below): `company_id NOT NULL` with an FK and a
leading index, actor columns, RLS enabled in this same migration, the composite-FK tenant
guarantee (rule 6) for `tms.transport_order`'s references into `tms.origin` and
`tms.destination`. Table names avoid the reserved word `ORDER`: `tms.transport_order` /
`tms.transport_order_line`, per the step brief's own suggestion. The full status lifecycle is
documented in `docs/domain/ORDER_LIFECYCLE_V1.md`, not repeated here.

```mermaid
erDiagram
    COMPANY     ||--o{ TRANSPORT_ORDER      : "scopes"
    ORIGIN      ||--o{ TRANSPORT_ORDER      : "ships from"
    DESTINATION ||--o{ TRANSPORT_ORDER      : "ships to"
    TRANSPORT_ORDER ||--o{ TRANSPORT_ORDER_LINE : "owns"

    TRANSPORT_ORDER {
        uuid id PK
        uuid company_id FK
        text order_number "system-generated, globally unique - see 12.1"
        text external_source "optional, pairs with external_reference"
        text external_reference "optional idempotency key - see 12.2"
        uuid origin_id FK "mandatory, composite-FK tenant guarantee"
        uuid destination_id FK "mandatory, composite-FK tenant guarantee"
        text customer_name "optional, free text - not a CRM FK"
        text customer_reference "optional, free text"
        date service_date "required"
        text priority "LOW | NORMAL | HIGH | URGENT"
        time requested_window_start "optional - both or neither with end"
        time requested_window_end "optional"
        text status "NOT_READY | READY_FOR_PLANNING | PLANNED | CANCELLED"
        text cancel_reason "optional, requires status = CANCELLED"
        numeric total_weight_kg "14,3 - transactional snapshot, see 12.3"
        numeric total_volume_m3 "14,4 - transactional snapshot"
        numeric total_pallets "12,2 - transactional snapshot"
        bigint version "optimistic lock - see 12.4"
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
    TRANSPORT_ORDER_LINE {
        uuid id PK
        uuid order_id FK "cascades from transport_order, no own company_id"
        integer line_number "1-based, contiguous, server-assigned"
        text material_code "snapshot, not a product-master FK"
        text material_description "snapshot"
        numeric quantity "12,3 - strictly positive"
        text uom "normalized upper, free text - see 12.5"
        numeric unit_weight_kg "10,3 - optional, positive when present"
        numeric unit_volume_m3 "10,4 - optional, positive when present"
        numeric line_weight_kg "14,3 - computed snapshot, quantity * unit_weight_kg"
        numeric line_volume_m3 "14,4 - computed snapshot"
        numeric pallet_quantity "10,2 - optional, direct input, not derived"
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
```

### 12.1 `order_number` is the one uniqueness rule that is deliberately *not* company-scoped

Rule 9 (section 13) requires uniqueness to scope to `company_id`, even for a real-world-unique
value, because a global constraint on a value a *user chooses* lets one company probe whether
another company has registered a specific value. `order_number` is different in kind: it is
generated once, by the backend, from `tms.transport_order_number_seq`
(`OrderService.generateOrderNumber`, formatted `TO-00000001`) - nobody ever supplies or guesses
one to "attempt" a collision, so a global `UNIQUE (order_number)` carries none of rule 9's
cross-tenant leak risk. It is intentionally an exception to rule 9, not a violation of it.

### 12.2 The external-reference idempotency pair

`external_source`/`external_reference` model "this order was already created by that upstream
system, for that external id" - the step brief's idempotency/external-reference uniqueness
strategy. Both are optional (a manually created order has neither), but
`ck_transport_order_external_pair_complete` forbids a reference with no source, so
`uq_transport_order_external UNIQUE (company_id, external_source, external_reference) WHERE
external_reference IS NOT NULL` can never be defeated by two different-but-both-NULL sources -
the same reasoning `carrier`'s `tax_id_type`/`tax_id_value` pair (section 10.1) needed, applied
to a nullable pair instead of a mandatory one. V1 treats this as a uniqueness guard
(`OrderService.create` rejects a duplicate pair with 409, `ConflictException`), not a
return-prior-result idempotent replay cache - the same "add the fuller behaviour only when a
concrete integration needs it" judgment section 8.3 made for a destination-frequency
association table.

### 12.3 Header totals are a documented transactional snapshot, not a live aggregate

Planning (step 10, not built yet) needs fast, stable weight/volume/pallet totals per order to
check capacity against a vehicle without summing lines on every read. `total_weight_kg`/
`total_volume_m3`/`total_pallets` are safe to persist as a snapshot only because exactly one
thing ever writes `transport_order_line`: `TransportOrder.applyLines`, called from inside the
same transaction and the same method that recomputes all three totals from the line set it just
wrote (`TransportOrder.recomputeTotals`). There is no code path - no controller, no job, no raw
repository call - that changes a line without also updating the header snapshot in the same
flush; `OrderApiIntegrationTest` proves recomputation directly (add a line, remove a line,
change a quantity - the header always matches the sum). If a second
writer of order lines is ever introduced (a future EDI importer that bypasses `OrderService`,
for instance), it must call the same recomputation path - the snapshot is not safe against a
second, uncoordinated writer.

### 12.4 `version` is the first optimistic-locking column in the schema

The step brief asks for "optimistic locking/versioning where concurrent edits matter" - the
first module in this repository where two people plausibly edit the same row through separate
HTTP round trips. `OrderRequest.version` is required on update and is compared explicitly by
`OrderService.requireCurrentVersion` against the persisted order's version *before* any change
is applied, which is what actually catches the realistic case (a client editing a stale copy
loaded before someone else's change landed) - JPA's own `@Version` check over this column
(`jakarta.persistence.Version` on `TransportOrder.version`) is kept as a second, narrower
backstop for two transactions racing to flush at the same instant, which the explicit check
alone would not catch. Both paths translate to the same `ConflictException`
(`OrderService.saveOrConflict`, `ApiExceptionHandler.handleOptimisticLockingFailure`). A future
module that needs the same guarantee should follow this two-layer shape rather than relying on
`@Version` alone.

### 12.5 `uom` is free text, matching `carrier.tax_id_type`'s reasoning

Unit-of-measure vocabularies vary by ERP/customer (`EA`, `KG`, `BOX`, `PAL`, ...); a fixed
`CHECK ... IN (...)` catalogue would hardcode one system's vocabulary the same way a fixed
tax-id-type catalogue would have (section 10.1). `uom` is normalized (`upper(btrim(uom))`) but
not restricted to a fixed set.

### 12.6 `orders` resolves origin/destination through a port, not through `masterdata` directly

`ModuleBoundaryTest` forbids any business module from depending on another (`orders` must not
import `com.ebim.tms.masterdata`), but a transport order genuinely needs to validate and display
a `masterdata`-owned origin and destination. `com.ebim.tms.shared.reference` defines two small
ports (`OriginLookupPort`, `DestinationLookupPort`) that carry no `masterdata` type; `masterdata`
provides the only implementation (`OriginLookupAdapter`, `DestinationLookupAdapter`,
`masterdata.infrastructure`), and `orders.application.OrderService` depends only on the port
interfaces. This is the "explicit API" `ModuleBoundaryTest`'s own rule message points to - see
that test and `ArchitectureTest` for the enforcement, and rule 10 (section 13) for the pattern
generalized for the next module that needs it (Planning, referencing fleet vehicles/carriers and
orders themselves).

### 12.7 Indexes added by V10

| Index | Purpose |
|---|---|
| `uq_transport_order_number` | `order_number` is globally unique - see 12.1 |
| `uq_transport_order_external` (partial) | the idempotency pair - see 12.2 |
| `ix_transport_order_company` | the hot path: "list mine", company-scoped queries lead with `company_id` |
| `ix_transport_order_company_status`, `ix_transport_order_company_service_date` | the list screen's status/date filters, composed with the company scope that always applies |
| `ix_transport_order_origin`, `ix_transport_order_destination` | the list screen's origin/destination filters |
| `uq_transport_order_id_company` (on `tms.transport_order`) | composite-FK target for a future Planning trip-assignment table (rule 6) |
| `ix_transport_order_line_order` | "lines of this order", the only way `transport_order_line` is ever queried |
| `uq_transport_order_line_order_line_number` | one line number per order |

## 13. Rules for the next migrations

1. Business tables carry `company_id NOT NULL` with an FK to `tms.company` and an index
   that leads with it. Never both scope columns without a documented reason - a pure child
   of another business table (no meaning without its parent, like `membership_role` or
   `frequency_weekly_rule`) is the one documented exception.
2. New tables get `created_at`, `updated_at`, the `set_updated_at` trigger, and actor
   columns when a real actor exists.
3. New tables are added to the `ENABLE ROW LEVEL SECURITY` list in the same migration.
4. Spatial columns follow `docs/database/MIGRATION_STRATEGY.md` section on PostGIS.
5. Every vertical slice adds a cross-tenant isolation test (ADR-003 compliance rule).
6. A foreign key from one company-scoped table into another (like `destination.zone_id`)
   gets the composite-FK tenant guarantee from section 8.1, not just a same-column FK.
7. Being a pure child of another table (rule 1's exception) and needing your own `company_id`
   (rule 6) are independent questions. A child table that *itself* references another
   company-scoped table needs `company_id` denormalized from its parent so it can carry rule
   6's composite FK too - see section 9.1 (`route_stop`), which needs both.
8. A reorderable unique-position column (a list a user can drag/move) declares its uniqueness
   constraint `DEFERRABLE INITIALLY DEFERRED` so an update-only reorder diff can pass through
   a transient duplicate mid-transaction without failing - see section 9.2
   (`uq_route_stop_route_sequence`).
9. A uniqueness rule always scopes to `company_id`, even for a column that describes a
   real-world-unique thing (a license plate, a legal tax id) - never installation-wide. A
   global constraint would leak cross-tenant existence information through a conflict
   response; see section 10.2. The one documented exception is a value nobody chooses or
   guesses, like a system-generated sequential number - see section 12.1.
10. A business module that needs to resolve a row owned by another business module (not a
    pure lookup value like an enum) does so through a small port interface in
    `com.ebim.tms.shared.reference` (or a similarly-scoped `shared` subpackage), implemented
    by an adapter inside the owning module - never by importing the owning module's
    repository or entity directly. See section 12.6 (`OriginLookupPort`/
    `DestinationLookupPort`) - the pattern to reuse when Planning (step 10) needs to resolve
    fleet vehicles/carriers or orders.
11. A table whose rows are plausibly edited by two different HTTP requests in separate round
    trips (not just raced within one) gets a `bigint version` column
    (`jakarta.persistence.Version`) plus an explicit "does the request's version match the
    persisted version" check in the service *before* any field is changed - relying on JPA's
    `@Version` alone only catches two transactions racing to flush at the same instant, not a
    client submitting a stale form. See section 12.4.

12. An invariant that spans two rows of the same table - "at most one open X per Y" - is
    expressed as a **partial unique index** over exactly the shape the current version writes,
    plus a service-level pre-check that exists only to produce a readable message. The index is
    what actually holds under concurrency; the pre-check is what makes the common case friendly.
    Scope the `WHERE` clause so it constrains today's shape without foreclosing tomorrow's - see
    section 14.4 (`uq_trip_order_assignment_open_whole_order`), which excludes closed history rows
    so a reassignment is legal and excludes partial allocations so a future split is not blocked.
    A partial index cannot be `DEFERRABLE`, so a transaction that supersedes such a row must close
    the old one and flush before inserting the new one (rule 8's problem, solved by statement
    order instead).
13. A row that is superseded is **closed, not overwritten or deleted**: a status column plus
    `*_at`/`*_by`/`*_reason` stamps, and every query that means "what is in force now" filters on
    the status (with a partial index on it, so history never slows the hot path). See section 14.3
    (`trip_order_assignment`) - the concrete form of section 3.5's "deletes never erase history"
    for a table whose rows are replaced rather than deactivated.

V6 (Step 05) is the first migration to follow rules 1-5 against a real business table; V7
(Step 06) is the first to need rule 6. V8 (Step 07) is the first to need rules 7-8. V9
(Step 08) is the first to need rule 9 explicitly, though it was implicit in every prior
company-scoped unique constraint. V10 (Step 09) is the first to need rule 9's documented
exception and the first to need rules 10-11. V11 (Step 10) is the first to need rules 12-13,
and reuses 6-11 unchanged; its own model is section 14, which follows this section so that the
rule numbering stays stable for the references that already point at it. Step 11 onward should
match this shape rather than reinvent it.

## 14. Manual planning: runs, trips, stops and assignments (Step 10, migration V11)

Four tables, all following section 13's rules 1-11 unchanged, plus the two new rules (12-13) that
V11 is the first to need. The domain contract is `docs/domain/PLANNING_MANUAL_V1.md`; the capacity
rules are `docs/domain/CAPACITY_MODEL.md`.

```mermaid
erDiagram
    COMPANY      ||--o{ PLANNING_RUN : "scopes"
    ORIGIN       ||--o{ PLANNING_RUN : "departs from"
    PLANNING_RUN ||--o{ TRIP : "contains"
    VEHICLE      ||--o{ TRIP : "runs (nullable while draft)"
    CARRIER      ||--o{ TRIP : "operates (nullable)"
    TRIP         ||--o{ TRIP_STOP : "visits"
    DESTINATION  ||--o{ TRIP_STOP : "is stopped at"
    TRIP         ||--o{ TRIP_ORDER_ASSIGNMENT : "carries"
    TRANSPORT_ORDER ||--o{ TRIP_ORDER_ASSIGNMENT : "is planned through"
```

### 14.1 A trip inherits its run's origin instead of repeating it

`tms.trip` has no `origin_id`. Every trip of a run departs from that run's origin, so the step
brief's "company/origin consistency" is structural: there is no second copy that could disagree.
Its `company_id` *is* denormalized from the run, because rule 7 requires it - a trip references
`tms.vehicle` and `tms.carrier`, both company-scoped, and needs its own `company_id` to carry
rule 6's composite foreign keys into them.

### 14.2 A planning run stores no counters

`planning_run` has no `trip_count`, no `order_count` and no totals. Section 12.3 documents why an
order's header totals are safe to persist - the backend is the sole writer of the lines they
summarise, and both change in one transaction. A run's counts fail that test: they change through a
*different* aggregate (assignments on trips) on nearly every request, so a stored counter would buy
one query per page and cost a permanent drift risk. `TripRepository.countByPlanningRunIds` and
`TripOrderAssignmentRepository.countByPlanningRunIds` return them in one grouped query per page.

### 14.3 The assignment aggregate, and why not `transport_order.trip_id`

`tms.trip_order_assignment` is an explicit aggregate rather than a foreign key on the order,
because a `trip_id` column could not do three things (full reasoning in
`docs/domain/PLANNING_MANUAL_V1.md` section 3):

1. **Carry allocated quantities.** `assigned_weight_kg`/`assigned_volume_m3`/`assigned_pallets` are
   snapshotted from the order header at assignment time, and the capacity service sums *these*,
   never the order. A future partial assignment is therefore a second row with smaller numbers and
   `whole_order = false`, with no change to any capacity code and no schema migration of existing
   rows. The optional `trip_order_line_allocation` table is deliberately not created in V1: it
   would have no writer. It hangs off `trip_order_assignment.id` + `transport_order_line.id` when
   line-level allocation actually arrives.
2. **Keep history** (rule 13). Removal sets `status = 'REMOVED'` with `removed_at`/`removed_by`/
   `removal_reason`; a move closes the source row and opens a new one. Both survive.
3. **Express the concurrency invariant in the database** - see 14.4.

### 14.4 The open-assignment invariant is a partial unique index

```sql
CREATE UNIQUE INDEX uq_trip_order_assignment_open_whole_order
    ON tms.trip_order_assignment (order_id)
    WHERE status = 'ACTIVE' AND whole_order;
```

Java takes the *trip's* row lock (`SELECT ... FOR UPDATE`) before every mutation, which serialises
two planners filling the same truck. It cannot serialise two planners assigning the same order to
*different* trips - a lock on trip A says nothing about trip B - so that case is refused here, by
the database, and surfaces as a 409. The index is partial in both directions on purpose: closed
rows are outside it (a reassignment is legal) and `whole_order = false` rows are outside it (a
future split is not blocked by the invariant that guards V1). This is rule 12's first use.

Because a partial index cannot be `DEFERRABLE`, a move must close the source row and flush before
inserting the target row - `TripAssignmentService.close` calls `saveAndFlush` for exactly that
reason. V10 met the same Hibernate flush-ordering trap and solved it by deferring the constraint;
here the fix is the statement order.

### 14.5 Capacity is live while draft and frozen at confirmation

`trip.snapshot_max_weight_kg`/`_volume_m3`/`_pallets` and `capacity_snapshot_at` are `NULL` while
the trip is a draft (capacity resolves live from the vehicle, so a fleet correction is picked up
immediately) and are written at confirmation (so a later fleet edit cannot rewrite what a confirmed
plan was validated against). Two CHECK constraints make the two states unambiguous from the row
alone: `ck_trip_confirmed_is_complete` (a confirmed trip has a vehicle, a departure and all three
values) and `ck_trip_draft_has_no_snapshot` (a draft has none of them). Migration V25 restated both
over the four *committed* states, since `READY_FOR_DISPATCH`, `IN_TRANSIT` and `COMPLETED` carry
the same frozen snapshot `CONFIRMED` does. Full reasoning, including the null-versus-zero limit
semantics, in `docs/domain/CAPACITY_MODEL.md`.

### 14.5.1 The trip lifecycle has six states, and only one of them is editable

`ck_trip_status` (V25) accepts `DRAFT`, `CONFIRMED`, `READY_FOR_DISPATCH`, `IN_TRANSIT`,
`COMPLETED` and `CANCELLED`. The *legal moves between them* are not in the database: they live in
`planning.domain.TripStatus`, are checked by `TripExecutionService` with caller-facing messages and
asserted again by the `Trip` aggregate. What the schema does enforce is what each state
*guarantees* - `ck_trip_ready_requires_timestamp`, `ck_trip_in_transit_requires_departure`,
`ck_trip_completed_requires_completion`, the three actor pairs, and
`ck_trip_execution_times_ordered` - so a row claiming a departure it has no timestamp for is
impossible even from raw SQL. `confirmed_at` is no longer a biconditional with
`status = 'CONFIRMED'`: every committed state has one (`ck_trip_committed_requires_confirmed_at`)
and a cancelled trip may or may not, because cancellation is now reachable from `CONFIRMED` and
`READY_FOR_DISPATCH` as well as from `DRAFT`. See `docs/domain/TRIP_EXECUTION_V1.md`.

### 14.6 `trip_stop` is a planning-instance stop, not a master route stop

`tms.trip_stop` and `tms.route_stop` share a shape and nothing else: a route stop belongs to a
reusable corridor and carries no date, a trip stop belongs to one dated trip and carries the
service-window envelope of the orders assigned there. Neither references the other, and a V1 trip
is not required to follow any master route. Stops are maintained by the backend from the trip's
active assignments (append a new destination, drop one whose last order left, preserve the
planner's ordering), which is why `uq_trip_stop_trip_sequence` *and*
`uq_trip_stop_trip_destination` are both `DEFERRABLE INITIALLY DEFERRED` - rule 8's reorder case
plus V10's delete-and-recreate flush-ordering case.

### 14.7 `tms.vehicle` gained the composite-FK target it never needed before

V9 gave `carrier` and `vehicle_type` their `UNIQUE (id, company_id)` because vehicles referenced
them. Nothing referenced `tms.vehicle` until now, so V11 adds `uq_vehicle_id_company` in an
additive `ALTER TABLE` - applied migrations stay immutable (rule: never edit V9).

### 14.8 Indexes added by V11

| Index | Purpose |
|---|---|
| `uq_planning_run_number` | `plan_number` is globally unique, section 12.1's documented exception to rule 9 (nobody types or guesses a sequence value) |
| `uq_planning_run_open_scope` (partial) | one *open draft* run per company/origin/planning date; confirming or cancelling frees the scope for a re-plan |
| `ix_planning_run_company`, `ix_planning_run_company_date`, `ix_planning_run_company_status` | the board's list filters, always composed with the company scope |
| `ix_planning_run_origin` | "runs from this depot" |
| `uq_planning_run_id_company`, `uq_trip_id_company` | composite-FK targets for the tables below (rule 6) |
| `uq_trip_run_number` | one trip number per run; not deferrable, because a trip number is not reorderable |
| `ix_trip_company`, `ix_trip_planning_run` | the board: "the trips of this run" |
| `ix_trip_vehicle`, `ix_trip_carrier` (partial) | "where is this vehicle/carrier planned?", skipping the rows with none |
| `uq_trip_stop_trip_sequence`, `uq_trip_stop_trip_destination` (both `DEFERRABLE`) | one position and one visit per destination per trip - see 14.6 |
| `ix_trip_stop_trip`, `ix_trip_stop_destination` | "the stops of this trip", "where is this destination served?" |
| `uq_trip_order_assignment_open_whole_order` (partial) | the concurrency invariant - see 14.4 |
| `ix_trip_order_assignment_trip_active` (partial) | the hot path: what is currently on this trip. Partial, so history never grows what capacity scans |
| `ix_trip_order_assignment_order` (full) | the audit path: everywhere this order has been planned, closed rows included |
| `ix_trip_order_assignment_company` | company-scoped reads |
| `uq_vehicle_id_company` (on `tms.vehicle`) | see 14.7 |

## 15. Location service calendar: `tms.location_frequency` (job 03 overnight-v3, migration V15)

Section 8.3 deliberately did not add a destination-frequency association table in V7: "when
Orders/Planning defines what 'which frequency serves this destination' needs to look like, add
the table then." Job 03 of the overnight-v3 pack is that concrete requirement - a location/store
must be able to answer "can I be serviced/dispatched on this date?" independently of whether it
also happens to be a stop on some `tms.route`, because `route.frequency_id` (V8) only ever
describes the route's own planning cadence, not any one stop's.

```mermaid
erDiagram
    LOCATION  ||--o{ LOCATION_FREQUENCY : "has a calendar of"
    FREQUENCY ||--o{ LOCATION_FREQUENCY : "governs"

    LOCATION_FREQUENCY {
        uuid id PK
        uuid company_id FK
        uuid location_id FK "composite FK guarantees same company as location"
        uuid frequency_id FK "composite FK guarantees same company as frequency"
        date effective_from "nullable - no start boundary"
        date effective_to "nullable - no end boundary, effective_to >= effective_from when both present"
        bool active "pauses the association without losing its date range"
        timestamptz created_at
        timestamptz updated_at
        uuid created_by FK
        uuid updated_by FK
    }
```

### 15.1 Associates `tms.location`, not `tms.destination`

`tms.location` (V14) is the forward-looking canonical master, and every location with a SHIP_TO
role already has a `location_id`-linked `tms.destination` projection
(`docs/architecture/ADR_LOCATION_MODEL.md`), so attaching the calendar to `tms.location` loses no
reach today and needs no rework when the legacy projections eventually retire (ADR-006 debt D-1).

### 15.2 Deliberately independent of `tms.route.frequency_id`

A route may reference a frequency (V8) for its own planning cadence; a location may independently
reference one or more frequencies for its own service calendar. Neither implies the other - the
job brief is explicit: "do not hard-wire frequency solely to a route if that prevents a store from
having its own calendar." A location's eligibility is evaluated purely from its own
`location_frequency` associations, never through any route it might also be a stop on.

### 15.3 One or multiple schedules per location, each independently pausable and time-boxed

A location may hold several associations (a store served by two independent schedules is eligible
if *either* one runs on the evaluated date - `LocationEligibilityEvaluator`). `effective_from`/
`effective_to` are both optional, matching a seasonal or contract-bound calendar without forcing
every association to declare boundaries it does not need. `active` is separate from the date
range for the same reason `frequency_weekly_rule.enabled` is separate from row presence (V7): an
operator can pause an association without losing its configured dates.

The one thing V1 deliberately does not model: two *active* associations between the same location
and the same frequency (`LocationFrequencyService` rejects the second with a 409) - an operator
who wants to reassign a period edits the existing association's dates rather than creating a
second row that means the same thing.

### 15.4 Carries its own `company_id`, unlike `frequency_weekly_rule`

Like `tms.route_stop` (section 9.1) and unlike `tms.frequency_weekly_rule` (a pure child with no
`company_id` of its own), `location_frequency` cross-references two independently company-scoped
masters (`tms.location` and `tms.frequency`), so both composite tenant FKs need a `company_id` on
this row to be checked against. The RLS policy is the direct `company_id = current_company_id()`
form (section 3 of the RLS migration), not the EXISTS-through-parent form, for the same reason.

### 15.5 The eligibility question is answered in Java, not SQL

`LocationEligibilityEvaluator` (pure, no repository access, unit-tested without a database) takes
already-resolved candidates - each a `location_frequency` row, its `Frequency` and, if one exists,
the `FrequencyException` for the evaluated date - and decides in the same precedence
`FrequencyCalendar.runsOn` already gives `FrequencyService`: an exception always overrides the
weekly rule, and a day with no configured row or a disabled row both mean "does not run". This
matches `CLAUDE.md`'s "Java owns business rules" and the job brief's "do not yet build an
optimizer" - the service makes one yes/no decision for one date, nothing more.

### 15.6 Indexes added by V15

| Index | Purpose |
|---|---|
| `ix_location_frequency_company` | company-scoped reads (ADR-003) |
| `ix_location_frequency_location` | "this location's calendar" - the eligibility service's own lookup |
| `ix_location_frequency_frequency` | "which locations use this frequency" - a future Frequency detail screen |

## 16. Fleet hardening: external references and vehicle double-booking (job 04 overnight-v3, migration V16)

Two independent, additive changes to the V9 fleet masters and the V11 trip table - no existing
column, constraint or index from either migration is touched.

### 16.1 `carrier.external_reference` / `vehicle.external_reference`

Both are optional free text, unindexed and unnormalized, the same shape as
`transport_order.external_reference` (section 12) minus its `external_source` sibling: a fleet
master is not (yet) written through an inbound idempotency flow, so there is no "who sent this" to
pair it with - just "what does the external fleet/ERP system call this row." Integration matching
on the value is the connector's job, not a database constraint.

### 16.2 The vehicle double-booking invariant: one active trip per vehicle per planning date

`tms.trip` gained `planning_date date NOT NULL`, denormalized from `tms.planning_run.planning_date`
at trip creation (rule 7 - the same idiom `trip.company_id` already uses, for the same reason: the
partial index below needs the value on `trip` itself, not reachable only through a join). The copy
can never drift because nothing on `PlanningRun` mutates `planningDate` after construction.

```sql
CREATE UNIQUE INDEX uq_trip_vehicle_active_planning_date
    ON tms.trip (company_id, vehicle_id, planning_date)
    WHERE status <> 'CANCELLED' AND vehicle_id IS NOT NULL;
```

The step brief allows either an interval reservation (if planned start/end times are reliable) or a
documented one-trip-per-vehicle-per-day rule (if the domain only has a planning date). `tms.trip`
has `planned_departure_at` but no planned arrival/end - Step 10 built manual planning around a
single planning date per run, not a per-trip time window - so an interval is not a fact the schema
holds today. The per-day rule is therefore the simplest invariant the current data actually
supports; revisit if/when trips gain a reliable planned duration.

The index is partial in both directions, mirroring `uq_trip_order_assignment_open_whole_order`
(section 14.4): `status <> 'CANCELLED'` so a cancelled trip releases its vehicle for the same day,
and `vehicle_id IS NOT NULL` so a still-unassigned draft trip reserves nothing. Both `DRAFT` and
`CONFIRMED` count as "active" - a draft already represents a planner's intent to run that vehicle
that day.

`TripService.requireVehicleNotDoubleBooked` runs the same check in Java first, with a
caller-facing message, before every write that sets a trip's vehicle (`create`, `updateVehicle`);
the index is the concurrency backstop for two planners racing to book the same vehicle on the same
day, translated back to a 409 by `TripService.saveWithDoubleBookingBackstop` - the same
pre-check-then-index-backstop relationship section 14.4 documents for order assignment.


## 17. Planning/Shipment V2: shipment number and the route suggestion (job 07 overnight-v3, migration V19)

Two columns on `tms.trip`, and a deliberately short list. The design record is
[`docs/domain/SHIPMENT_V2.md`](../domain/SHIPMENT_V2.md); migration V19's header enumerates every
field of the shipment header that is *not* stored and why.

### 17.1 `trip.shipment_number`: the identity a trip has outside its planning board

`trip_number` is unique inside one `planning_run` ("trip 2 of PL-00000017") and useless outside it:
two runs on the same day both have a trip 2. An outbound integration, a printed manifest or a
support call needs a stable, installation-wide handle, so V19 adds one.

```sql
CREATE SEQUENCE tms.shipment_number_seq AS bigint INCREMENT BY 1 START WITH 1 NO CYCLE;
ALTER TABLE tms.trip ADD COLUMN shipment_number text;   -- backfilled, then NOT NULL
ALTER TABLE tms.trip ALTER COLUMN shipment_number
    SET DEFAULT 'SH-' || lpad(nextval('tms.shipment_number_seq')::text, 8, '0');
ALTER TABLE tms.trip ADD CONSTRAINT uq_trip_shipment_number UNIQUE (shipment_number);
```

The uniqueness is global rather than company-scoped, which section 12.1 flags as normally a
cross-tenant enumeration risk. The exception is the same one `plan_number` (V11) and `order_number`
(V10) take, for the same reason: this is a system-generated opaque number nobody types, guesses or
quotes from another tenant's document.

The `DEFAULT` is what makes "every trip has a shipment number" a property of the *table* rather
than of the one service that writes it. `TripService.generateShipmentNumber()` still assigns the
value explicitly - Hibernate always names the column, so the default never fires from the
application - but a raw `INSERT` (a data fix, a test fixture, a future SQL bulk import) now draws a
collision-free number instead of failing `NOT NULL`. Same sequence, same format, one source of
truth.

### 17.2 `trip.route_id`: a suggestion, not a constraint

```sql
ALTER TABLE tms.trip ADD COLUMN route_id uuid;
ALTER TABLE tms.trip ADD CONSTRAINT fk_trip_route FOREIGN KEY (route_id)
    REFERENCES tms.route (id) ON DELETE RESTRICT;
ALTER TABLE tms.trip ADD CONSTRAINT fk_trip_route_company FOREIGN KEY (route_id, company_id)
    REFERENCES tms.route (id, company_id);
CREATE INDEX ix_trip_route ON tms.trip (route_id) WHERE route_id IS NOT NULL;
```

`MATCH SIMPLE` (the default) so the reference is unchecked while null and enforced otherwise - the
same idiom `trip.vehicle_id` (V11) and `destination.zone_id` (V7) use. The composite half is rule
6's tenant guarantee: a shipment of company A can never point at a route of company B, whatever the
service layer does.

Section 9 introduced `tms.route` as "a named, reusable sequence a planner can point a Trip at
later". This is that pointer, and it stays weak on purpose: the shipment's stops are not required
to equal the route's, are not re-synchronised when the master is edited, and are never *created*
from it - `tms.trip_stop` always follows the trip's own active assignments (section 14.6). The
strongest thing applying a route may do is reorder the stops the shipment already has. See
`SHIPMENT_V2.md`, "Route master interaction", for why a materialised copy was rejected.

### 17.3 What V19 deliberately does not add

- **No per-stop planned arrival/service time.** `tms.trip` has one optional `planned_departure_at`
  and no travel-time model; a stored ETA would be inventing the routing that `CLAUDE.md` defers by
  decision. Section 16.2 documents the same limitation for the double-booking rule.
- **No stored used-weight/volume/pallet totals on `tms.trip`.** They are one grouped `SUM` over
  active `trip_order_assignment` rows; a stored copy is a second source of truth a concurrent
  assignment can leave stale (section 14.2's reasoning, applied to a trip).
- **No coordinate copy on `tms.trip_stop`.** Read live from `tms.destination`: a corrected store
  coordinate must reach an open plan immediately, and a frozen wrong one would be undetectable.
- **No contiguity trigger on `trip_stop.sequence`.** Section 14's migration rules out triggers
  carrying planning logic, and `uq_trip_stop_trip_sequence` already covers uniqueness. Contiguity
  and stop/assignment coverage stay Java invariants (`Trip.assertStopSequenceIntegrity`,
  `TripAssignmentService.requireStopsCoverAssignments`), both asserted on every mutation.

### 17.4 Indexes and constraints added by V19

| Object | Purpose |
|---|---|
| `tms.shipment_number_seq` | feeds `trip.shipment_number`; global, like the plan and order sequences |
| `uq_trip_shipment_number` | one shipment number per installation |
| `ck_trip_shipment_number_not_blank` | the usual blank-text guard |
| `fk_trip_route` / `fk_trip_route_company` | the route reference and rule 6's tenant guarantee |
| `ix_trip_route` | partial (`route_id IS NOT NULL`): "which shipments used this corridor" |


## 18. Location unification (migration V23)

**Read this before sections 7, 8, 9, 12 and 14.** Those sections describe `tms.origin` and
`tms.destination` as live masters that other tables reference, which is how the schema was built
and is no longer how it works. They are kept as the record of what each migration did; this
section is what is true now.

### 18.1 What changed

`tms.location` (V14, section 15's neighbour) is the only physical-place master. V23:

1. reduced `tms.location_role.role` to `ORIGIN` and `DESTINATION` - `SHIP_TO` was renamed, and
   `STORE`, `DC`, `PLANT`, `HUB` and `OTHER` were deleted because each names a value
   `tms.location.location_type` already carries;
2. repointed all six references at `tms.location`, simple and composite tenant key alike;
3. revoked `INSERT`, `UPDATE` and `DELETE` on `tms.origin` and `tms.destination` from `tms_app`.

| Table | Column | Referenced before V23 | References now |
|---|---|---|---|
| `tms.route` | `origin_id` | `tms.origin` | `tms.location` |
| `tms.route_stop` | `destination_id` | `tms.destination` | `tms.location` |
| `tms.transport_order` | `origin_id` | `tms.origin` | `tms.location` |
| `tms.transport_order` | `destination_id` | `tms.destination` | `tms.location` |
| `tms.planning_run` | `origin_id` | `tms.origin` | `tms.location` |
| `tms.trip_stop` | `destination_id` | `tms.destination` | `tms.location` |

The column names did not change. They name the two ends of a movement, and renaming them would
rename the JSON fields of the inbound integration contract v1 for a synonym; `COMMENT ON COLUMN`
carries the new target instead.

### 18.2 Why the repoint could not break a unique constraint

`uq_origin_location` and `uq_destination_location` (V14) allow at most one legacy row per
location, so the mapping through `location_id` is injective: two distinct references cannot
collapse onto one id. That is what guarantees `uq_route_stop_route_destination`,
`uq_trip_stop_trip_destination` and `uq_planning_run_company_origin_date` survive the change.

Rule 6 (section 13) is unaffected and still holds: every one of the six columns kept its
composite `(reference_id, company_id)` foreign key, now targeting `uq_location_id_company`.

### 18.3 What an order with one location at both ends means

`transport_order.origin_id` and `destination_id` may now hold the same value. No constraint
forbids it and none should: a distribution centre that both ships and receives used to require
two rows in two tables, and the whole point of the change is that it is one place. The same
applies to the coordinates read live by `tms.trip_stop` - the source is `tms.location`, not
`tms.destination`, and section 14.4's reasoning about not copying them is unchanged.

### 18.4 The frozen tables

`tms.origin` and `tms.destination` still exist, still hold their rows and their V14
`location_id` link, and are referenced by nothing. `SELECT` is deliberately retained: they are
the recovery path if V14's merge-on-code united two places that were genuinely different.
Dropping them is a later migration, after V23 has been executed against a real database.

Their constraints are still asserted by `MasterDataConstraintIntegrationTest` and
`MasterDataDestinationFrequencyConstraintIntegrationTest`, which now cover frozen tables rather
than live ones - kept, because a migration that accidentally altered them should still fail
loudly.

## 19. Per-instance overrides on two masters (job 01 overnight-v4, migration V24)

Two nullable columns, each with one stated resolution rule and no new machinery. Both say the
same thing structurally: the general value lives where an operator expects to find it, and one
instance may disagree with it without moving it.

| Column | Overrides | Resolution rule |
|---|---|---|
| `tms.frequency_exception.cutoff_time_override` | `frequency_weekly_rule.cutoff_time` for one date | `FrequencyCalendar.effectiveCutoff` |
| `tms.route_stop.service_time_override_minutes` | `location.service_time_minutes` on one route | `RouteStop.effectiveServiceTimeMinutes` |

### 19.1 A blackout date cannot carry a cutoff

`ck_frequency_exception_cutoff_requires_service` allows `cutoff_time_override` only when
`service_override` is true. Nothing is dispatched on a closed date, so "the last moment to
order" is not a question that has an answer there, and a stored value would survive a later
toggle back to open as a time nobody chose. The rule is enforced three times over, each for a
different reader: `FrequencyService` returns a 400 (so an operator sees a message, not a
stack trace), `FrequencyException`'s constructor refuses (so every other Java write path is
covered, and the rule is provable without Docker in `FrequencyExceptionTest`), and the CHECK
holds against direct SQL - the same defense-in-depth shape section 8.1 describes for
`destination.zone_id`.

### 19.2 `NULL` means inherit, and `0` does not

Both columns distinguish "no opinion" from "zero". A `route_stop` with
`service_time_override_minutes = 0` is a drop-and-go stop that really costs no service time; one
with `NULL` reads its location's value. `RouteStop.effectiveServiceTimeMinutes` is the only
place that resolves it, and it returns `NULL` - unknown, not zero - when a stop has no override
and its destination could not be resolved, which section 9.5's deliberately-laxer render path
makes reachable.

`tms.location` remains the source of truth for service time: nothing here writes back to it, and
`RouteDetailView.RouteStopView` returns the override, the inherited value and the effective value
separately rather than making the client re-derive the third from the first two.

### 19.3 What V24 deliberately does not touch

No new index: `frequency_exception` is only ever read by `(frequency_id, exception_date)` and
`route_stop` by `route_id`, both already covered (sections 8.4 and 9.6). No new grant: V13 grants
`tms_app` table-level DML, which covers columns added later. No RLS change: a new column is not a
new row.

Automatic planning does not yet read the effective service time - it has no ETA or
service-window arithmetic to spend it on (`docs/domain/ROUTES.md` section 2). The column is
recorded and resolved; wiring it into a scheduling calculation is that calculation's step, not
this one.

## 20. Drivers and trip driver assignment (job 03 overnight-v4, migration V26)

`tms.driver` is the fourth fleet master and follows V9's shape unchanged: `company_id NOT NULL`
with an FK to `tms.company` and an index leading with it, actor columns, RLS enabled in the same
migration, normalized-code CHECKs, and `uq_driver_id_company` as the composite-FK target (rule 6)
for `tms.trip.driver_id`. The full rule set lives in `docs/domain/DRIVERS_V1.md`; what follows is
only what is specific to the schema.

| Uniqueness | Scope | Why |
|---|---|---|
| `uq_driver_company_code` | company | every master's code, ADR-003 |
| `uq_driver_company_document` | company | one person is registered once per company |
| `uq_driver_company_license_number` | company | one licence belongs to one person |

All three are company-scoped rather than installation-wide, for the reason section 10.2 sets out
at length for `vehicle.license_plate`: a global constraint would let company A learn whether
company B has registered a given document number merely by triggering a conflict.

### 20.1 `license_expires_on` is nullable, and that nullability is a rule

A company migrating a spreadsheet of drivers rarely has the expiry for all of them, and refusing
to store the driver until it does would push the data back into the spreadsheet this master
exists to replace. What follows from `NULL` is stated once, in
`shared.reference.DriverLicenseStatus`: it is `UNRECORDED`, which never blocks an assignment,
because "we do not know" is not the same claim as "it has expired". Only `EXPIRED` refuses
anything.

The status itself is derived and never stored - a status column would be wrong every morning at
midnight and would need a job to keep it true. It lives in `shared.reference` rather than in
`fleet.domain` because both `fleet` (screen and filter) and `planning` (assignment and dispatch)
have to answer it identically, and `ModuleBoundaryTest` forbids either from importing the other.

### 20.2 The driver's carrier is checked in Java, not by a foreign key

`fk_driver_carrier_company` guarantees the tenant (a driver's carrier belongs to the driver's own
company). What it cannot express is the rule that actually matters at assignment time: *when both
the trip and the driver name a carrier, the two must be the same one*. The trip's carrier is
copied from its vehicle, and a composite FK cannot state "equal when both are non-null", so
`TripService` enforces it - from both directions, since swapping the vehicle can break a pairing
the driver assignment established.

Only-both-known is deliberate. A driver with no carrier is the company's own staff, and lending
them a subcontracted truck for a day is a real arrangement TMS has no business refusing.

### 20.3 `uq_trip_driver_active_planning_date` is V16's rule, applied to a person

One non-cancelled trip per driver per company per planning date, partial on the same two
conditions as `uq_trip_vehicle_active_planning_date` and with the same relationship to its
service-level pre-check (caller-facing message first, index as the concurrency backstop). If
anything it is the harder physical constraint of the two: a truck could be re-crewed and sent out
twice in a day; a person cannot be in two cabs at once.

`ix_trip_company_driver_planning_date` is a *different* access path and not a duplicate of it: the
unique index excludes cancelled trips because that is what "already booked" means, while a
dispatcher asking "where was Ana on the 20th" wants the cancelled ones too.

### 20.4 `tms.trip.driver_id` is nullable in every state

It is deliberately absent from `ck_trip_confirmed_is_complete` (V25). Companies differ on when the
driver is known - some assign one with the vehicle days ahead, others hand the manifest to whoever
is at the gate - and requiring it would encode the first practice as the only legal one. The rule
V1 ships is the weaker true one: *if* a driver is named, they must be one this company may send
out, re-checked at ready and at dispatch (`TripExecutionService`).

The assignment window is also wider than the vehicle's: `DRAFT`, `CONFIRMED` and
`READY_FOR_DISPATCH`, closing at departure. Swapping a vehicle changes what the plan was validated
against (frozen capacity, section 14.5); swapping a driver changes nothing a shipment was proved
against, and a driver calling in sick at 05:00 on a trip confirmed last night is the ordinary case
rather than a re-plan.

### 20.5 No driver snapshot on the trip

"Who drove SH-00000142" is answered exactly by `driver_id`: a driver is deactivated and never
deleted, `ON DELETE RESTRICT` makes deletion impossible while any trip points at them, and their
row keeps their name. A name/document snapshot would preserve only the *spelling* as of the day
against a later correction - which is a change every other master in this schema lets flow through
to history (section 3.5) - at the price of three copied columns that can drift. Capacity is
snapshotted (section 14.5) because it is a validation input: the plan was proved to fit those
numbers. A driver's surname proves nothing.

### 20.6 Indexes added by V26

| Index | Table | Purpose |
|---|---|---|
| `ix_driver_company` | `driver` | the tenant predicate every query leads with |
| `ix_driver_carrier` | `driver` | partial on `carrier_id IS NOT NULL`; the carrier filter |
| `ix_driver_company_license_expiry` | `driver` | partial on active + recorded expiry; the licence-status filter and the expiry sweep |
| `uq_trip_driver_active_planning_date` | `trip` | the double-booking invariant (20.3) |
| `ix_trip_company_driver_planning_date` | `trip` | "where was this driver that day", cancelled included |

## 21. Stop execution, transport events and exceptions (job 04 overnight-v4, migration V27)

Three additions that turn a stop from a line on a plan into something a dispatcher works. The full
rule set is in `docs/domain/TRIP_EXECUTION_V1.md`; what follows is only what is specific to the
schema.

### 21.1 `tms.trip_stop` grows an execution half

`execution_status` plus three actual timestamps and a free-text note. Everything above them stays
the *plan* - sequence and service window, written only while the trip is a draft (section 14.6) -
and the two never overwrite each other: the planned window stays exactly as planned however late
the vehicle arrived, because the gap between them is the number a report wants.

The declarative half of the transition table is six CHECKs, the same shape V25 wrote for the trip:

| CHECK | Says |
|---|---|
| `ck_trip_stop_arrival_required` | `ARRIVED`/`IN_SERVICE`/`COMPLETED` carry an arrival |
| `ck_trip_stop_service_start_required` | `IN_SERVICE` carries a service start |
| `ck_trip_stop_departure_required` | `COMPLETED` carries a departure |
| `ck_trip_stop_pending_has_no_times` | a stop nobody touched has no times at all |
| `ck_trip_stop_skipped_has_no_times` | a stop never attempted has none either - this is what makes `SKIPPED` and `FAILED` distinguishable from the row alone |
| `ck_trip_stop_execution_times_ordered` | arrival ≤ service start ≤ departure, null-tolerant on both sides of every pair |

`uq_trip_stop_id_company` is added here as the composite-FK target (rule 6) for the two new tables.
`trip_stop` never needed one before because nothing pointed at a stop.

### 21.2 No actor columns on `trip_stop`, and why that is not an inconsistency

V25 paired every trip-level actual time with the `app_user` who reported it, because at the time
there was nowhere else to put them. `tms.transport_event` is now that place: append-only,
company-scoped, actor-stamped, written in the same transaction as every stop transition. Five more
actor columns plus five more pair CHECKs would be a second copy of a fact the log already holds,
and V25's own argument against a second source of truth applies to the actor as much as to the
time.

### 21.3 `tms.transport_event` is append-only in the database, not by convention

The second table in the schema to withhold `UPDATE`/`DELETE` from `tms_app`, after `audit_event`
(section 3.5's rule taken one step further), with the same split `FOR SELECT` / `FOR INSERT`
policies rather than one `FOR ALL`: there is no update grant left for a `FOR ALL` policy to guard.

It carries the same three-column actor shape as `audit_event` - `actor_app_user_id`,
`actor_email`, `actor_machine_label`, with an XOR between the first and the third - so a machine
gets no invented `app_user` row and a timeline can name who acted without `planning` reaching into
the identity module. The email is a snapshot: an operator who later changes their address keeps the
one they acted under, which is what a log should say.

`ck_transport_event_stop_scope` is the one CHECK worth reading twice: stop-family events must name
a stop, trip-family events must not, and the exception pair may do either. Without it, "the trip
arrived" would be a storable sentence.

Both FKs to `trip` and `trip_stop` are `ON DELETE RESTRICT`, unlike `fk_trip_stop_trip`'s CASCADE
(V11): an execution log a delete could silently take with it is not a log. The stop-level one can
never fire in practice - stops are added and removed only while a trip is `DRAFT`, and a stop only
has events once its trip is `IN_TRANSIT`.

`latitude`/`longitude` are present and always null. Nothing reports a position and telematics is
deferred by decision; the columns exist so that the day a feed does, recording it is an application
change and not a migration - the bet V20 made with its `event_type` CHECK and won in V25.

### 21.4 `tms.trip_exception` is the only mutable one, and `OPEN`/`RESOLVED` is why

An append-only log cannot answer "what went wrong today that nobody has closed out": a reported row
and a resolved row are two rows, and asking whether the second exists for every first is a
self-join a supervisor screen would run on every refresh. That question is the entire justification
for the table, and the workflow stops there - two states, no assignment, no severity, no escalation
ladder, no SLA clock, because no rule in TMS reads one.

`ck_trip_exception_stop_scope` requires a stop for the four delivery-shaped types;
`ck_trip_exception_other_requires_notes` makes `OTHER` cost a sentence, which is the only pressure
that keeps a small catalogue from collapsing into it. `reported_by` is `NOT NULL` unlike
`transport_event.actor_app_user_id`: V1 has no unattended source of exceptions, and relaxing a
column later is a one-line migration while reclaiming one is not.

### 21.5 Two rules that live in services because no CHECK can express them

- **A skipped or failed stop has an exception.** `TripStopExecutionService` opens one in the same
  transaction; a CHECK cannot see another table's rows.
- **A trip cannot be completed while a stop is unresolved.** `TripExecutionService.complete`
  refuses, naming the stops, and `Trip.complete` asserts it again. Deliberately *not* a CHECK even
  in spirit: trips that were already `COMPLETED` before V27 keep stops at `PENDING`, which is the
  truth about them, and a constraint would have made those rows illegal retroactively.

### 21.6 Indexes added by V27

| Index | Table | Purpose |
|---|---|---|
| `ix_trip_stop_company_unresolved` | `trip_stop` | partial on the three unresolved outcomes; "what is still outstanding across the board". Not the path `complete` uses - that one asks the same question of an aggregate already in memory |
| `ix_transport_event_company_trip_time` | `transport_event` | one shipment's day in order, by `event_time` (what the fleet lived) rather than `recorded_at` |
| `ix_transport_event_company_time` | `transport_event` | "what happened across the fleet this afternoon" |
| `ix_trip_exception_company_open` | `trip_exception` | partial on `OPEN`; the supervisor's standing question |
| `ix_trip_exception_trip` | `trip_exception` | the trip workspace's own list |
| `ix_trip_exception_trip_stop` | `trip_exception` | partial on `trip_stop_id IS NOT NULL`; the per-stop open count |

## 22. Delivery result and proof of delivery (job 05 overnight-v4, migration V28)

Two tables, and the full reasoning lives in
[`../domain/PROOF_OF_DELIVERY_V1.md`](../domain/PROOF_OF_DELIVERY_V1.md).

```
tms.trip_stop 1 ──── * tms.order_delivery * ──── 1 tms.transport_order
                              │
                              └──── * tms.delivery_evidence   (metadata only; bytes live outside)
```

### 22.1 `tms.order_delivery` is beside `trip_stop.execution_status`, never inside it

V27's `execution_status` says what the **vehicle** did at a destination. This says what the
**goods** of one order did there. A stop serving three orders can be `COMPLETED` with one of them
`REJECTED`, and both statements are true — which is precisely the case a single status could not
represent, and the whole justification for a second table.

Keyed on `(trip_stop_id, order_id)` and not on `(trip_id, order_id)`: an order is delivered
*somewhere*, and the stop is where. That key makes "this stop's outcomes" a single-column lookup
and makes it structurally impossible to record a delivery without saying at which destination it
happened. `trip_id` rides along - every read so far is "one trip's deliveries", and it is what lets
the composite tenant FK point at `tms.trip` directly (rule 6).

Five results (`DELIVERED`, `PARTIAL`, `REJECTED`, `FAILED`, `NOT_ATTEMPTED`) and deliberately no
`PENDING`: an order with no row here has not been recorded, which is the same statement. Three
`CHECK` constraints carry the field rules - a result claiming a handover names its time,
`NOT_ATTEMPTED` carries none, a receiver only appears where somebody was present, and anything
short of a clean delivery carries a note.

### 22.2 One row per delivery, corrected in place

`uq_order_delivery_stop_order` allows exactly one. A correction overwrites it and loses nothing:
every recording also appends a `DELIVERY_RECORDED` row to `tms.transport_event`, which is
append-only, so what was claimed and when is on file there. Two rows here would make "was this
delivered" a question with two answers and an ordering rule to choose between them.

Three verbs are granted, not four. There is no `DELETE`: "this delivery never happened" is itself a
result (`NOT_ATTEMPTED`), not the absence of one.

### 22.3 `tms.delivery_evidence` holds no bytes and no URL

Metadata only - type, media type, size, SHA-256, an opaque server-generated key. The bytes live in
a private object store behind `shared.storage.EvidenceStoragePort`; `tms.storage.evidence.mode`
decides which, and the default is `DISABLED`, which refuses uploads clearly rather than inventing
somewhere to put a customer's signed delivery note.

No column holds content, because a photo of a pallet in a row is that photo in every backup, every
replica and the WAL. No column holds a link, because a permanent public address for a signed
delivery note is a leak with a stable URL: bytes are served only by an authenticated,
company-scoped, permission-checked endpoint.

Append-only in the database, like `audit_event` (V22) and `transport_event` (V27), and for a
sharper reason: evidence a party can quietly edit or remove is not evidence. `UPDATE` and `DELETE`
are revoked from `tms_app`.

`storage_key` is never derived from a caller-supplied file name. That is what makes path traversal
not a class of bug here rather than a filter that has to be right - and `LocalFilesystemEvidenceStorage`
still re-checks the key's shape and that the resolved path stays under the root.

### 22.4 Three widened CHECKs, and why each was worth a migration

| Constraint | Added value | Why |
|---|---|---|
| `ck_transport_event_type` | `DELIVERY_RECORDED` | a new kind of operational fact; stop-scoped, so `ck_transport_event_stop_scope` was widened with it |
| `ck_shipment_outbox_event_type` | `DELIVERY_RESULT_RECORDED` | the first publishable event that is not a trip-state change - the reason that column is an event type and not a status |
| `ck_audit_event_action` | `DELIVERY_RESULT_RECORDED` | V27 argued against auditing *stop* transitions and this is the exception it named: a delivery result is what a dispute or a credit note is argued from |

### 22.5 What V28 deliberately does not touch

`tms.transport_order.status` still stops at `PLANNED`. The orders module owns that lifecycle (V25,
V27), and a `DELIVERED` order here is still `PLANNED` there - two owners for one column is how a
status ends up meaning different things in two screens. No quantities either: `PARTIAL` requires a
note instead, because quantities need a unit model, a totals rule and a dispute rule that nobody
has decided.

### 22.6 Indexes added by V28

| Index | Table | Purpose |
|---|---|---|
| `ix_order_delivery_company_trip` | `order_delivery` | one trip's deliveries - the single query behind a trip detail |
| `ix_order_delivery_order` | `order_delivery` | "has this order ever been delivered, and on which shipment" - the customer-service lookup, and the outbound payload's path |
| `ix_order_delivery_company_shortfall` | `order_delivery` | partial on `result <> 'DELIVERED'`; "what did not arrive today" across the fleet |
| `ix_delivery_evidence_delivery` | `delivery_evidence` | a delivery's artefacts, in one read for a whole trip |

---

## 23. Tracking positions (job 06 overnight-v4, migration V29)

Design: `docs/domain/TRACKING_V1.md`. Decision: `docs/architecture/ADR-007-tracking-provider-port.md`.

```
tms.trip 1 ──── * tms.tracking_position     (a provider-agnostic position feed)
```

One table and one widened CHECK (`ck_integration_client_scope_value`, for the new
`integration.tracking:write` scope). No new permission: `monitoring.transport:read` has existed
since V3 and been granted to the seeded roles since V5, and this is the endpoint it always meant.

### 23.1 Why this is not `tms.transport_event`'s latitude/longitude

V27 added those two columns unused, for "the day a driver app or a telematics integration does
report a position". This is that day, and they are still the wrong home:

| | `transport_event` | `tracking_position` |
|---|---|---|
| Volume per trip-day | ~12 | ~500 at one point a minute |
| Actor | required (`ck_transport_event_actor_xor`) | none - nobody typed it |
| Lifetime | forever | purged; hence the `DELETE` grant |

Folding a feed into the log would make the timeline query - the one a dispatcher runs constantly -
scan a table that is 98% pings, and would force a fake machine actor onto every measurement. Those
two columns keep their original meaning: the optional position of a *reported fact*.

### 23.2 The unique index is the idempotency contract

`uq_tracking_position_feed_instant (company_id, trip_id, provider, occurred_at)` is the business
identity of a ping - one feed, one shipment, one instant - and it plays the role
`(external_source, external_reference)` plays for an order. A partner whose at-least-once queue
replays an hour of traffic therefore needs no cursor and no de-duplication of its own.

It is also the reason the sampling rule is safe to enforce in the application: intake reads the
newest stored instant per (trip, feed), decides in memory, and the index is the concurrency
backstop. A lost race fails one delivery with a 500 that the sender's retry resolves as duplicates -
the same self-healing answer `IntegrationInboxService.record` documents for its own table.

### 23.3 `UPDATE` revoked, `DELETE` granted - the opposite of every log in this schema

`tms.audit_event` (V22), `tms.transport_event` and `tms.delivery_evidence` (V28) all revoke both.
This one revokes only `UPDATE`, and the asymmetry is the point: a measurement is never corrected,
only superseded by the next one, but it **is** disposable. Retention is what distinguishes a feed
from a log, and a table with no supported way to trim it would be the largest one in the schema
growing forever. The sweep itself is an operational task in V1 - TMS has no job scheduler, and
introducing one is a bigger decision than this table justifies (ADR-007).

### 23.4 `numeric(9,6)` and not PostGIS

Every question V1 asks of this table is "the newest row for this trip", which is a b-tree lookup.
A spatial index earns its cost when something asks "which vehicles are within 2 km of here", and
nothing does - geofencing is not a feature TMS has, and V29 records why it deliberately is not one.
The columns match `tms.transport_event`'s and `tms.location`'s existing shape, so adding a
`geography(Point)` later is a generated column rather than a rewrite.

### 23.5 What V29 deliberately does not touch

No trip status, no stop execution status, no order lifecycle, no timeline entry. Nothing in TMS
reads this table except the screen that draws it, which is what makes a broken, hostile or absent
feed unable to corrupt a delivery record. No driver column either - the trip already names its
driver (V26), and copying that onto half a million rows would turn a fleet feed into a per-employee
movement record with a different legal weight and no operational gain.

### 23.6 Indexes added by V29

| Index | Table | Purpose |
|---|---|---|
| `uq_tracking_position_feed_instant` | `tracking_position` | the idempotency contract - see 23.2 |
| `ix_tracking_position_trip_recent` | `tracking_position` | "where is this shipment now", and the bounded trail behind it, from one index |
| `ix_tracking_position_occurred_at` | `tracking_position` | the retention sweep's path; without it, purging scans the largest table in the schema |

## 24. Rates and trip costing (job 07 overnight-v4, migration V30)

Design: `docs/domain/RATES_COSTING_V1.md`.

```
tms.carrier 1 ──── * tms.rate_card             (an agreement, valid between two dates)
tms.trip    1 ──── 0..1 tms.trip_cost          (the estimate and the actual, side by side)
tms.trip_cost 1 ── * tms.trip_cost_component   (the lines that explain the estimate)
```

Three tables, two widened audit CHECKs and four new permissions (`rates.rate_card:*`,
`rates.trip_cost:*`). `VIEWER` is granted none of them - see 24.6.

### 24.1 Two typed scope columns instead of one polymorphic `scope_id`

A card's scope is `CARRIER`, `ORIGIN` or `ROUTE`, and the target lives in `origin_id` or `route_id`
rather than in a single `scope_id`. One column would have to give up its foreign key - it would
point at two tables - and with it the composite tenant guarantee of rule 6. Two nullable columns
keep `fk_rate_card_origin_company` and `fk_rate_card_route_company`, and
`ck_rate_card_scope_target` makes exactly one of them present, decided by `scope`.

Zone is absent from the enum on purpose: a zone belongs to a destination and a trip serves many, so
the question has no true answer at trip granularity. `RATES_COSTING_V1.md` section 2.1 states what
would have to exist first.

### 24.2 `uq_rate_card_active_agreement` is a backstop, not the rule

The rule - no two active cards for the same carrier, scope, target and vehicle type with
overlapping validity - lives in `RateCardService`, because the useful part of that refusal is
*which card it collides with*, and no constraint can say that.

The unique index catches only the narrow race of two concurrent inserts of the identical agreement.
Its `coalesce(..., '00000000-...'::uuid)` expressions are not decoration: NULLs are distinct in a
unique index, so without them two `CARRIER`-scoped rows for any vehicle type - exactly the pair the
constraint exists to refuse - would both be admitted.

A full `EXCLUDE USING gist` over a `daterange` is the right general tool and was deliberately not
used: it needs `btree_gist`, which V1's baseline does not create. Adding a database extension to
enforce a rule the service already enforces is a bigger change than the rule deserves, and an
overlap that slipped through cannot make costing ambiguous anyway - `RateCardSelector`'s ranking is
total.

### 24.3 `tms.trip_cost` keeps two figures that never overwrite each other

`estimated_amount` and `actual_amount` are independent columns, not one field with a status. The
number a company is managed by is the difference between them, and a model where recording the
invoice replaced the quote would destroy the only figure worth reporting on.
`ck_trip_cost_estimate_complete` and `ck_trip_cost_actual_complete` make each half all-or-nothing,
and `ck_trip_cost_not_empty` refuses a row carrying neither.

`rate_card_id` is a live foreign key with `ON DELETE RESTRICT`, *and* `rate_card_code`/`_name`/
`_scope` are snapshots of what it said at the time - the same reasoning `tms.trip.snapshot_max_*`
follows (V11): a figure a decision was made against must not be rewritten by a later edit to the
master.

### 24.4 `tms.trip_cost_component` is a table because a component can be impossible

Five more columns on `trip_cost` would have held the amounts. What they could not have held is
"the card charges 0.85 per km and this shipment has no known distance", which has to be recordable
or the estimate silently understates itself by the whole line haul.

So a line carries `status` (`APPLIED` / `NOT_CALCULABLE`), a `reason` when it is the latter, and -
for a measured component - the `rate`, the `quantity`, the `unit` and the `quantity_source` it came
from. `ck_trip_cost_component_status_consistent` and `ck_trip_cost_component_shape` tie those fields
together so a line can never be half-formed.

The table has no `updated_at` and no trigger: a line is written once and deleted once. A re-estimate
replaces the whole set, which is also why `TripCostService` clears and **flushes** before writing the
new lines - Hibernate orders every INSERT of a flush before its DELETEs, and one unit of work would
break `uq_trip_cost_component_cost_component`.

### 24.5 `quantity_source` records where a number came from

`ROUTE_REFERENCE` is `tms.route.reference_distance_km` - a planner-entered hint (V8) and the only
distance this product has. `ORDER_DECLARED_TOTALS` is the same sum every capacity check uses. Both
are on the line rather than in a developer's head, because the first thing anyone disputing an
estimate asks is where the 39.5 km came from.

### 24.6 Four permissions, and why `VIEWER` gets none

Reading a tariff and reading what one shipment cost are different disclosures, so
`rates.rate_card:read` and `rates.trip_cost:read` are separate: a dispatcher may legitimately need
the second without being shown the commercial agreement behind it.

Every other catalogue in this schema is readable by `VIEWER` because operational data is what that
role exists to watch. A tariff is not operational data - it is what one company negotiated with
another - and the default for commercially sensitive figures is that somebody has to be given them
on purpose. An installation that disagrees grants the permission; one that never thought about it
does not leak its rates to every read-only account.

### 24.7 What V30 deliberately does not touch

No trip column, no order column, no lifecycle. Costing reads a shipment and writes beside it, so a
missing tariff can never block a dispatch - which is exactly what `TripCostEstimationPort`'s
best-effort contract at confirmation depends on. No cost per order either: allocating a trip's cost
back to the orders on it needs an allocation basis that is a commercial decision per company, and
picking one here would bake somebody's accounting policy into the schema.

### 24.8 Indexes added by V30

| Index | Table | Purpose |
|---|---|---|
| `uq_rate_card_company_code` | `rate_card` | the user-facing identity of a card within its company |
| `uq_rate_card_active_agreement` | `rate_card` | the duplicate-agreement backstop - see 24.2 |
| `ix_rate_card_company_carrier_validity` | `rate_card` | the selection path: one carrier's live agreements |
| `ix_rate_card_origin` / `_route` / `_vehicle_type` | `rate_card` | the referential paths a master's deactivation check follows |
| `uq_trip_cost_trip` | `trip_cost` | one cost per shipment, and the per-trip lookup |
| `ix_trip_cost_company_rate_card` | `trip_cost` | "what has this card priced" |
| `ix_trip_cost_company_open` | `trip_cost` | "what is still not settled" - the cost review's opening question |
| `uq_trip_cost_component_cost_component` | `trip_cost_component` | one line per component; a second `BASE` row would read as a surcharge |
| `ix_trip_cost_component_cost` | `trip_cost_component` | the lines of one estimate |

## 25. Carrier tendering (job 08 overnight-v4, migration V31)

Design: `docs/domain/CARRIER_TENDERING_V1.md`.

```
tms.trip    1 ──── * tms.trip_tender          (one attempt to place the shipment)
tms.carrier 1 ──── * tms.trip_tender          (who it was offered to)
tms.integration_client * ──── 0..1 tms.carrier  (the new carrier_id: whose tenders this key answers)
```

One table, one nullable column on `tms.integration_client`, three widened CHECKs (outbox event
type, transport event type, audit action), one new integration scope and two new permissions
(`planning.tender:*`). `VIEWER` is granted neither, for the reason 24.6 gives about rates: an offer
carries a price.

### 25.1 Two partial unique indexes carry the whole model

| Index | Rule |
|---|---|
| `uq_trip_tender_live` | at most one `DRAFT`/`SENT` tender per trip |
| `uq_trip_tender_accepted` | at most one `ACCEPTED` tender per trip, ever |

The second is the invariant the feature exists to guarantee - **exactly one carrier has agreed to
run this shipment** - and it is enforced by the database rather than by the service alone because it
is the one rule whose violation would be unrecoverable: two carriers both believing they have the
load. `TripTenderService` refuses both first with a sentence a planner can read; the indexes are the
backstop for what the trip's row lock cannot cover, which is a planner and a carrier served by two
application instances at the same instant.

### 25.2 The carrier is snapshotted and is always the trip's own

`trip_tender.carrier_id` is `updatable = false` and the service refuses any carrier other than
`trip.carrier_id`. That is not a limitation of the table: a trip's carrier comes from the vehicle
planned on it (V11) and the vehicle may only be swapped while the trip is a `DRAFT` (V11/V25), so
from the moment a shipment is offerable there is exactly one carrier it could go to. A tender naming
a second one would produce a shipment whose accepted tender and whose `carrier_id` disagree.

The consequence - a rejected shipment cannot be re-offered to a *different* carrier without
cancelling and replanning it - is stated in the domain doc, section 3.1, together with what widening
it would cost.

### 25.3 `expired_at` is not `expires_at`

Two columns because they are two facts: `expires_at` is when the offer was due, `expired_at` is when
TMS resolved that it had lapsed. This installation runs no scheduler, so the second is later - the
lapse is materialised by the next write that touches the tender and succeeds.

**A report reading `status` directly must apply the same rule the application does**:
`status = 'SENT' AND expires_at < now()` means expired. That is why
`ix_trip_tender_company_outstanding` indexes `expires_at`, and it is the one place in this schema
where a stored value can lag what every API returns. Migration V31 section 1b and the domain doc
section 6 state the cost in full.

### 25.4 The response actor is one of two columns, never one polymorphic one

`responded_by` (an `app_user`) and `responded_by_client` (an `integration_client`) with
`response_source` deciding which is present - `ck_trip_tender_response_actor` enforces the triple.
One `answered_by` column would have to give up its foreign key, and with it the composite tenant
guarantee of rule 6; it would also lose the distinction the pair exists for, which is *evidentiary*:
an acceptance typed in by the shipper's own clerk and one signed by the carrier's credential are
worth different things when the load does not turn up.

### 25.5 `integration_client.carrier_id`

Nullable, and meaningful only together with the `integration.tender:respond` scope. Every credential
that exists today - an ERP, a WMS, a telematics feed - has none and gains nothing from it. A
credential *with* one is a carrier's own key: it can see the tenders addressed to that carrier and
answer them, and nothing else.

Nullable rather than a second table because this is one attribute of a credential, not a
relationship with a life of its own. Mutable, unlike `company_id`, because an administrator who
bound a key to the wrong haulier has to be able to fix it without re-issuing a secret the partner
has already deployed. `fk_integration_client_carrier_company` keeps rule 6.

### 25.6 What V31 deliberately does not touch

No column on `tms.trip`, no lifecycle change and no gate on dispatch. Nothing in the trip lifecycle
asks whether a tender was accepted, because an installation that never tenders must still be able to
send a truck. The two hooks that do exist run in the other direction - cancelling or dispatching a
shipment withdraws any live offer on it - and both are load-bearing: without the first a carrier
could accept a shipment that is not happening.

No link to `tms.trip_cost` either. `offered_amount` is what somebody offered on the day and
`estimated_amount` is what the tariff says the shipment should cost; letting either overwrite the
other would destroy the comparison that makes both worth having.

### 25.7 Indexes added by V31

| Index | Table | Purpose |
|---|---|---|
| `uq_trip_tender_trip_attempt` | `trip_tender` | the attempt number is the user-facing identity within a shipment |
| `uq_trip_tender_live` | `trip_tender` | partial; one open offer per shipment - see 25.1 |
| `uq_trip_tender_accepted` | `trip_tender` | partial; one acceptance per shipment, ever - see 25.1 |
| `ix_trip_tender_trip` | `trip_tender` | the shipment's own history, newest attempt first |
| `ix_trip_tender_company_outstanding` | `trip_tender` | partial on `SENT`, ordered by deadline; "what have we offered that nobody has answered" |
| `ix_trip_tender_company_carrier` | `trip_tender` | the carrier's own inbox, read by the M2M endpoint |
| `ix_integration_client_carrier` | `integration_client` | partial; the referential path a carrier's deactivation check follows |

## 26. Operational alerts (job 10 overnight-v4, migration V32)

Design: `docs/domain/ALERTS_NOTIFICATIONS_V1.md`.

```
tms.company  1 ──── * tms.notification     (every alert is one tenant's)
tms.app_user 1 ──── * tms.notification     (read_by: who acknowledged it for the company)
```

One table, no new permission, no widened CHECK anywhere else. That last part is the interesting
one: V32 is the first feature migration since V22 that adds nothing to `ck_audit_event_action` or to
the outbox event type, because raising an alert is not a business fact - it is a by-product of one
that is already recorded three other ways.

### 26.1 No `title` and no `message` column

`type` selects the sentence in the frontend's `notifications` bundle and `message_args` carries its
placeholders. A sentence stored server-side is stored in one language and one wording: a tenant that
switches to English would read its own history in Spanish, and a reworded alert would leave every
older row saying the old thing.

It also settles the "no HTML in the database" question by construction rather than by convention -
nothing stored here is markup, so nothing stored here can be injected into a panel.

### 26.2 `entity_type` + `entity_id` is polymorphic, and says so

The two kinds an alert can be about live in two modules' tables (`tms.trip`, `tms.driver`), so no
foreign key is expressible. The consequence is stated rather than hidden: an alert can outlive the
row it points at. `entity_label` is snapshotted for exactly that case - and, more usefully, so the
bell renders six rows without joining two other modules' tables, which is the coupling
`NotificationPublisher` exists to prevent.

`ck_notification_entity_type` keeps the set at the two the frontend has a route for. An alert that
leads nowhere is worse than no alert.

### 26.3 `uq_notification_company_dedupe` is why the insert is native

`NotificationRecorder` writes with `INSERT ... ON CONFLICT DO NOTHING`, not with a read followed by
a save. The difference is not tidiness. A check-then-insert races between two application instances,
and the loser takes a unique violation **inside the business transaction that raised the alert** -
which in PostgreSQL aborts the whole transaction. That shape would let a duplicate bell entry fail a
driver assignment.

`DO NOTHING` and not `DO UPDATE`: re-raising must not un-read an alert somebody has already
acknowledged, nor move its `occurred_at` to the second time somebody happened to touch the row.

What the key is composed of, per type, is the substance of the feature and lives in the domain doc,
section 7.

### 26.4 `read_at` is the company's, `resolved_at` is the condition's

`read_at`/`read_by` mean *somebody here has seen this* - there is no row per recipient. The cost
(two dispatchers share one badge) and what changing it would take (a `tms.notification_read` join
table, nothing here moving) are stated in V32 section 3 and in the domain doc section 5.

`resolved_at` is set by the module that raised the alert when the condition behind it closes: a trip
exception resolved, a shortfall delivery corrected to a full one. There is no `resolved_by` - who
closed it is already on `tms.trip_exception.resolved_by` and on `tms.order_delivery`, and a second
copy could only disagree. `ck_notification_read_pair` enforces the read pair; nothing enforces a
resolution pair, because there is nothing to pair.

### 26.5 `UPDATE` granted, `DELETE` withheld

The same grant shape as `tms.order_delivery`: an alert is acknowledged and resolved in place, and
never removed by the application. An alert history the application can silently erase stops being
evidence of what the operation was told. Purging old rows is a maintenance task for the schema
owner. Retention is deferred, not forgotten - see V32 section 6.

The tenant policy is therefore a single `FOR ALL`, not the split select/insert pair the append-only
logs carry: there is an `UPDATE` here for a `FOR ALL` policy to guard.

### 26.6 What V32 deliberately does not touch

- No new permission. Which account may be told about which alert type is decided in
  `NotificationType.requiredPermission` against permissions that already exist
  (`monitoring.transport:read`, `planning.tender:read`, `fleet.driver:read`). V32 section 4.
- No scheduler, and therefore no column for one. Every type is raised by a business transaction that
  was going to happen anyway. The two alerts that would need a sweep are named in V32 section 6 and
  left out.
- No email/webhook delivery table. External delivery is an adapter reading this one, not a column
  added to it.
- No severity column that anybody configures. Severity is a property of the type; the column stores
  what the board said when the alert was raised, so a later reclassification does not rewrite
  history.

### 26.7 Indexes added by V32

| Index | Table | Why |
|---|---|---|
| `uq_notification_company_dedupe` | `notification` | one alert per fact per tenant; the `ON CONFLICT` target - see 26.3 |
| `ix_notification_company_occurred` | `notification` | the feed: newest business fact first, whole company |
| `ix_notification_company_unread` | `notification` | partial on `read_at IS NULL`; the badge, which is the query that runs most often |

## 27. The operating day a cost belongs to (job 11 overnight-v4, migration V33)

The smallest migration in the schema: one column and one index, added so the KPI report
(`docs/domain/KPIS_REPORTING_V1.md`) can total what a range of days cost.

### 27.1 The column

`tms.trip_cost.planning_date date NOT NULL`, copied from `tms.trip.planning_date` when the row is
created and never updated afterwards - the source is itself `updatable = false` and its own source
(`tms.planning_run.planning_date`) has no mutator, so the copy cannot go stale.

Backfilled from `tms.trip` in the same migration. The backfill is total: `trip_id` is `NOT NULL` and
carries a foreign key, so every existing row has exactly one trip to take the date from.

### 27.2 Why a column and not a join

Without it, "what did March's shipments cost" is

```sql
SELECT sum(...) FROM tms.trip_cost c JOIN tms.trip t ON t.id = c.trip_id
 WHERE t.planning_date BETWEEN ...
```

which is `com.ebim.tms.rates` reading `com.ebim.tms.planning`'s table. `ModuleBoundaryTest` forbids
the Java form of that sentence, and a SQL statement doing what the ArchUnit rule refuses is the
boundary being broken quietly rather than not being broken.

The alternative - the port passing a set of trip ids from planning to rates - is tens of thousands
of UUIDs at the stated scale, to produce two numbers. So the day is denormalized onto the cost,
exactly as rule 7 denormalized it onto `tms.trip` itself, and for the same class of reason.

### 27.3 What V33 deliberately does not touch

- **No `carrier_id` beside it.** It would answer "what did we pay this carrier" for the same price,
  and it is not added because nothing asks yet: the V1 report has no per-carrier cut. A column added
  ahead of the screen that reads it is a column nobody maintains.
- **No new permission.** The report is behind `monitoring.transport:read`, and its cost section is
  behind `rates.trip_cost:read` - both of which already exist and already mean those things.
- **No grants and no policy change.** A new column on an existing table inherits the table's V30
  policy and grants.
- **No rollup table, no materialized view.** Every figure the report shows is an aggregate over at
  most a quarter of one company's shipments. A pre-aggregated copy would buy nothing measurable and
  would introduce the one failure mode a KPI screen cannot afford - a number that disagrees with the
  rows it claims to summarize.

### 27.4 Indexes added by V33

| Index | Table | Why |
|---|---|---|
| `ix_trip_cost_company_planning_date` | `trip_cost` | the report's only access path into this table: one company, one range, summed by currency |

## 28. Company settings (job 12 overnight-v4, migration V34)

One table, three columns, and a rule about what may join them. Full reasoning in
`docs/domain/SAAS_ADMINISTRATION_V1.md`; the schema half is here.

### 28.1 The table

```
company_settings (company_id PK/FK -> company)
  default_country          text NOT NULL DEFAULT 'PE'    -- ISO 3166-1 alpha-2
  order_number_prefix      text NOT NULL DEFAULT 'TO-'
  shipment_number_prefix   text NOT NULL DEFAULT 'SH-'
  created_at / updated_at / created_by / updated_by
```

The company **is** the primary key. No surrogate id, because there is exactly one settings row per
company and a separate id would make "two settings rows for one company" expressible.

Backfilled with one defaulted row per existing company. The defaults are exactly the literals the
code held before the migration, so nothing an installation has already issued changes shape.

### 28.2 Why a table beside `tms.company` and not four more columns on it

`tms.company` is on the authentication hot path: `JdbcIdentityRepository.COMPANY_PERMISSIONS_SQL`
and `JdbcCompanyScopeLoader.COMPANY_SQL` read it on *every* authenticated request to decide which
companies the caller may select. Widening that row widens both reads for values neither uses.

The settings are read at three moments - creating an order, creating a shipment, previewing a
location import - and written from one screen. A separate row keeps the hot read narrow and makes
the 1:1 cardinality a database guarantee rather than a convention.

`time_zone` is deliberately **not** duplicated here. It already lives on `tms.company`,
`CompanyScope.today()` is the whole product's definition of "what day is it for this tenant", and a
second copy could only disagree with it.

### 28.3 Every column has a consumer

| Column | Read by | Was |
|---|---|---|
| `default_country` | `LocationImportValidator`, for a row that leaves `country` blank | the literal `"PE"` in Java |
| `order_number_prefix` | `OrderNumbers.format`, both callers (manual API and bulk import) | the constant `"TO-"` |
| `shipment_number_prefix` | `TripService.generateShipmentNumber` | the literal `"SH-"` |

A fourth column, `default_locale`, was written and taken back out before the migration was
committed. Its only honest consumer is the browser choosing a bundle *before* any company screen is
open, which means the value has to travel on `GET /api/v1/me` - which means a join in the one query
every authenticated request runs. It becomes right the day the language is a property of the user
(`app_user.preferred_locale`, already on that query's SELECT list) rather than of the tenant.

### 28.4 The prefixes are safe to change

`tms.transport_order_number_seq` and `tms.shipment_number_seq` are installation-wide. The digits
after the prefix therefore come from one counter for the whole installation: two companies that both
pick `TO-` cannot collide, and a company that switches prefix mid-year produces no duplicate either,
because the part that makes the value unique was never the prefix.

Per-company sequences are **not** introduced. They would make each tenant's numbering start at 1 -
which some customers do ask for - at the cost of a sequence created per company at onboarding, and
uniqueness of `transport_order.order_number` would then depend on the prefixes actually differing.
That is a feature with a design, not a side effect of adding a settings row.

### 28.5 A real tenant policy, unlike the identity tables

`company_settings` gets `p_tenant_company_scope` keyed on `tms.current_company_id()`, not the
`USING (true)` policy V13 section 5 gives `app_user`, `membership` and friends. Those are read
*before* a company is chosen and so cannot carry a tenant predicate; this one is only ever touched
inside an already company-scoped transaction.

That has one consequence worth stating, because it looks like a bug otherwise: **the settings row is
not created when a company is created.** An `ORGANIZATION_ADMIN` creating company B does so inside a
transaction scoped to company A, so `tms.current_company_id()` is A and an insert carrying
`company_id = B` is refused by `WITH CHECK`. The right answer is not to weaken the policy for one
insert; it is for the read to have a defined answer when the row is absent. So every read goes
through `CompanySettingsPort`, which resolves a missing row to `CompanySettings.defaults()` - the
same values the backfill applies - and the row is written the first time that company's settings
screen is saved.

No `DELETE` grant. The row is 1:1 with a company that is itself never deleted, so a delete could
only ever lose a tenant's numbering configuration.

### 28.6 Audit vocabulary widened

`ck_audit_event_aggregate_type` gains `COMPANY`, `APP_USER` and `MEMBERSHIP`;
`ck_audit_event_action` gains `ROLES_CHANGED`.

Three aggregates and not one, because administering a tenant produces changes asked about
separately: "who changed our shipment prefix", "who let this person in", "who took their planning
rights away". `MEMBERSHIP` rather than a generic `USER_ACCESS` because the row that actually changes
is `tms.membership`, which keeps `aggregate_id` resolvable; `APP_USER` covers the global profile,
which is a different change with a different blast radius.

`ROLES_CHANGED` rather than a plain `UPDATE`, for the reason V26 gives about `DRIVER_CHANGE`. Its
metadata carries the role codes before *and* after, so the answer is in the event rather than
reconstructed from history.

`AuditVocabularyMigrationTest` now asserts that `AuditAction` and `AuditAggregateType` name exactly
what the latest definition of each CHECK allows. It parses the migration files off the test classpath
and needs no database, deliberately: the drift it catches has already happened once (V25 section on
`AUTO_PLAN`) and went unnoticed for a release precisely because the only tests that reach a database
need Docker.

### 28.7 Indexes added by V34

None. The only access path is the primary key.

