# TMS by EBIM - Ownership Matrix

Status: accepted baseline (Step 00)
Date: 2026-08-19

"Primary owner" means: the decision is made and enforced there. Other columns may
display, hint at, or reinforce it, but they never replace the primary owner.

## 1. Layer ownership

| Concern | React (tms-web) | Java/Spring Boot (tms-api) | Supabase/PostgreSQL |
|---|---|---|---|
| Login UX | Yes | No | Supabase Auth |
| JWT issuance | No | No | Supabase Auth (primary owner) |
| JWT validation | No | Primary owner | Issuer / JWKS source |
| Session refresh | Yes (Supabase client) | No | Supabase Auth |
| Business authorization | UI hints only | Primary owner | RLS defense in depth |
| Tenant / company context | UI selection only | Primary owner | FKs, constraints, RLS |
| App user / membership resolution | No | Primary owner | Stores `app_user`, `membership` |
| Master data CRUD | UI | Primary owner | Persistence + invariants |
| Orders | UI | Primary owner | Persistence + invariants |
| Planning / trips | UI | Primary owner | Persistence + invariants |
| Capacity checks | Display only | Primary owner | Deterministic checks only |
| Rates and trip costing | Display + data entry | Primary owner (selection, calculation, snapshot) | Persistence + invariants |
| Carrier tendering | Display + data entry | Primary owner (lifecycle, expiry, one-acceptance rule) | Persistence + the two partial unique indexes |
| Concurrency on assignment | No | Primary owner | Locking / unique constraints |
| Pagination, filtering, sorting | Requests it | Primary owner (server-side) | Indexes |
| Validation | UX validation | Primary owner | Hard invariants |
| OR-Tools (future) | No | Primary owner | Inputs/results persisted |
| Background jobs | No | Primary owner | Optional queue primitives |
| Files / documents (future) | UI | Authorization + signing | Storage |
| Realtime (future) | Consumer | Authorization + contracts | Optional Realtime |
| Schema migrations | No | Flyway (primary owner) | Executes the SQL |
| Extensions (PostGIS, pgcrypto) | No | Declared in Flyway | Provides capability |
| RLS policies | No | Authored in Flyway | Enforces at runtime |
| Audit | Display / search | Primary owner | Append-only storage |
| Operational alerts | Bell + panel; renders the sentence from a type and its arguments | Primary owner (what is raised, what makes it one fact, who may be told) | Persistence + the dedupe unique index the `ON CONFLICT` insert targets |
| Observability | UI errors | Backend metrics/logs/traces | Platform metrics |
| Secrets | Never | Runtime env only | Platform-managed keys |

## 2. Database ownership rules

1. Flyway under `backend/tms-api` is canonical for application DDL: tables, columns,
   constraints, indexes, extensions required by application tables, RLS policies,
   database views and application-depended functions.
2. Application DDL is never duplicated in `supabase/migrations`.
3. Supabase-managed `auth` and `storage` schemas are never recreated or altered by Flyway.
4. Business tables may live in `public` for Supabase compatibility, but direct `anon`
   access is denied and RLS is enabled where the table is reachable by Supabase roles.
5. The frontend does not query business tables directly in V1.
6. Spring Boot performs strong authorization even if its database role bypasses RLS.
7. Every business aggregate is scoped through one clear owner - normally `company_id`.
   Do not spray both `organization_id` and `company_id` into every table without a
   documented reason.
8. Applied migrations are immutable; later changes are new versioned migrations.

## 3. Domain ownership (initial)

| Aggregate | Scope owner | Notes |
|---|---|---|
| `organization` | platform | Top-level tenant grouping |
| `company` | `organization_id` | Operational tenant; the scope for business data |
| `app_user` | platform | Business profile mapped from `auth.users`; not `auth.users` itself |
| `membership` | `app_user` + org/company + role | Source of truth for a user's effective scope |
| `origin` | `company_id` | Optional external reference to an EWM warehouse code, never an FK |
| `zone` | `company_id` | |
| `destination` | `company_id` | |
| `frequency` | `company_id` | Header + weekly rules + exceptions |
| `route` | `company_id` | Master route with ordered stops; not a calculated trip route |
| `carrier` | `company_id` | |
| `vehicle_type` | `company_id` | Default capacities |
| `vehicle` | `company_id` | May override vehicle-type capacities |
| `order` | `company_id` | Header + lines |
| `planning_run` | `company_id` | |
| `trip` | `company_id` | Own stops/snapshots |
| `trip_order_assignment` | `company_id` (via trip) | Explicit assignment aggregate + line allocations |
| `rate_card` | `company_id` | A commercial agreement with one carrier, valid between two dates (V30) |
| `trip_cost` | `company_id` (via trip) | Estimate and actual side by side, plus the lines behind the estimate |
| `audit_*` | `company_id` where applicable | Append-only |
| `notification` | `company_id` | One operational alert (V32). Acknowledged by the company, not per user - see `docs/domain/ALERTS_NOTIFICATIONS_V1.md` section 5 |

Default rule: **Company owns operational business masters and transactions** unless an
explicit, documented reason places an entity at organization or platform level.

## 4. Cross-product ownership (TMS vs EWM)

| Item | Owner | Rule |
|---|---|---|
| TMS internal tables | TMS | Never shared with EWM |
| EWM internal tables | EWM | Never read directly by TMS |
| Warehouse identity | EWM | TMS stores an external reference code only, no FK |
| Integration contract | Shared, versioned | API/event contract, explicitly versioned |
| Migration history | Per product | No shared Flyway history |

## 5. Escalation rule

If an implementation step needs to cross one of these boundaries, it must add a new ADR
under `docs/architecture/` explaining the exception, its blast radius and its rollback,
before the code is written.
