# Step 04 - Frontend Foundation and Auth Shell

Date: 2026-08-19
Attempt: 3 (re-verification of the attempt-1 implementation; repairs the attempt-2 report)
Result: **PASS**

## 0. Why there was a third attempt

Attempt 1 completed the implementation, passed every check, and printed its gate marker
wrapped in backticks (`` `TMS_GATE=PASS` ``) instead of as a bare line. The supervisor matches
`grep -q '^TMS_GATE=PASS$'`, so the decorated line did not match and the step was recorded as
incomplete even though the work had finished. The same failure mode occurred on Step 03's
attempt 1.

Attempt 2 re-verified the attempt-1 implementation (re-ran typecheck/lint/test/build from a
clean `npm ci`, re-read the core auth/company/routing/shell modules) and reported everything as
passing, but its own report body never actually appended a bare `TMS_GATE=PASS` line at the end
of the file - the marker was only mentioned inside the attempt-1 postmortem prose in section 0,
which does not match `^TMS_GATE=PASS$` either. That is the same class of bug it was diagnosing,
just moved into the report file instead of the log output, so attempt 2 also failed the gate.

Attempt 3 changed no production code once more. It independently re-ran `npm run typecheck`,
`npm run lint`, `npm test` and `npm run build` against the working tree as found (not a fresh
`npm ci`, since `node_modules` was already present and consistent with `package-lock.json`),
re-read `AuthContext.tsx`, `CompanyContext.tsx`, `httpClient.ts`, `router.tsx` and `navConfig.ts`
end to end, cross-checked every `capability` string in `navConfig.ts` against the backend's
`Capability` enum (`backend/tms-api/.../shared/security/Capability.java`) to confirm the values
are not just plausible-looking strings, and confirmed `grep -rln supabase src` still returns
only `shared/config/env.ts`, `shared/auth/supabaseClient.ts` and `shared/auth/AuthContext.tsx`.
Nothing needed repair in the implementation; only this report needed a real, bare gate marker
appended as its own line, which this revision does at the end of the file.

## 1. State inherited

Step 01 left a minimal but real frontend: Vite + React 19 + TypeScript, Bootstrap as the
visual base, SweetAlert2 wrappers, TanStack Query, a typed `httpClient` with a
`setAuthTokenProvider` seam already reserved "for Step 04", a typed `AppEnv` already reading
`VITE_SUPABASE_URL`/`VITE_SUPABASE_ANON_KEY`, and a placeholder `AppLayout` with every nav
item except Dashboard disabled. Step 03's handoff (section 9 of
`docs/overnight/03_BACKEND_SECURITY.md`) specified the contract this step had to build
against: sign in only through Supabase, call `GET /api/v1/me` once after sign-in, send
`X-Company-Id` on company-scoped calls, branch errors on `code` not `detail`, send
`X-Correlation-Id`. Nothing from Steps 00-03 was regenerated; the existing skeleton was
extended in place.

## 2. What was built

### 2.1 Auth abstraction (`shared/auth/`)

- `supabaseClient.ts` - the **only** direct Supabase call site in the app, exactly as the V1
  rule requires. Built from `VITE_SUPABASE_URL`/`VITE_SUPABASE_ANON_KEY` only; there is no
  service-role key anywhere in the frontend and no path for one to reach it (`.env.example`
  only ever documented the anon key).
- `AuthContext.tsx` - `AuthProvider`/`useAuth` wrap `supabase.auth`: session read on mount,
  `onAuthStateChange` kept in sync, `signIn`/`signOut`. It registers the httpClient's token
  provider (`setAuthTokenProvider`) so every outgoing request attaches the current Supabase
  access token without any screen touching the Supabase client.
- **401 handling without a refresh loop**: `AuthProvider` subscribes to `httpClient`'s new
  `onApiResponseError` hook and calls `supabase.auth.signOut()` on `unauthenticated`/
  `invalid-token`, guarded by a ref so concurrent failing requests trigger exactly one
  sign-out, not one per request. `AuthContext.test.tsx` fires two simultaneous 401s and
  asserts `signOut` was called once.
- `ProtectedRoute.tsx` - route-level guard: loading state while the session resolves,
  redirect to `/login` (preserving the attempted location) when signed out, otherwise
  renders the guarded subtree.

### 2.2 Company context (`shared/company/`)

- `CompanyContext.tsx` - `CompanyProvider`/`useCompany` call `GET /api/v1/me` (via
  `shared/api/meApi.ts`) exactly once per session, through TanStack Query, **only** when
  `AuthContext` reports `signedIn`. The company list, permissions and capabilities it exposes
  are exactly what the backend returned - nothing is manufactured client-side, satisfying the
  step's "reflects only companies returned/authorized by the backend" requirement.
- Selection persists to `localStorage` (best-effort; a `try`/`catch` degrades gracefully in
  private-browsing mode) and is restored on the next load; it defaults to the first company
  if nothing valid is stored.
- **`company-scope-forbidden` handling**: a second `onApiResponseError` subscription clears
  the stale selection, invalidates the `/me` query and shows a SweetAlert2 toast, exactly the
  "re-fetch `/me` and ask the user to choose again" behaviour the Step 03 handoff asked for -
  never a silent retry against the same company.
- `RequireCompany.tsx` - a route guard for company-scoped screens: loading/error/empty states
  before rendering the guarded route. This is UX only; every company-scoped backend call is
  still independently checked by `CompanyScopeFilter` server-side regardless of what this
  guard decided.

### 2.3 App shell (`shared/ui/`)

- `AppLayout.tsx` + `TopBar.tsx` + `Sidebar.tsx` - top bar (brand, mobile toggle, company
  selector, user menu) and a side navigation built from `navConfig.ts`. The sidebar uses
  Bootstrap 5.3's responsive offcanvas (`offcanvas-lg`): one markup renders as a static column
  at the `lg` breakpoint and a slide-in drawer below it, which is the mobile-friendly behaviour
  the brief asked for without a second mobile-specific component.
- `CompanySelector.tsx` - lists only `useCompany().companies`; switching calls
  `selectCompany`, which only changes the `X-Company-Id` future requests send.
- `UserMenu.tsx` - identity display and the one call site for `signOut`, behind a
  `confirmDialog` (SweetAlert2) rather than an accidental single click.
- Navigation groups match the brief: Dashboard (always visible), Master Data (Origins,
  Destinations, Zones, Frequencies, Routes), Fleet (Carriers, Vehicle Types, Vehicles),
  Orders, Planning, Trips, Administration. Each group is gated by a backend `Capability` name
  (`shared/security/Capability` in tms-api) once the company is `ready` - UX only, stated
  explicitly in code comments, and every route it links to still requires its own backend
  permission check.
- Modules with no screen yet get a route and `PlaceholderPage` ("Coming soon"), never fake
  data - satisfying "unavailable modules can show a clean placeholder, not fake functionality".

### 2.4 Reusable component library (`shared/ui/components/`)

`PageHeader`, `FilterBar`, `DataTable` (+ `Pagination`, built against the backend's exact
`{content, page, size, totalElements}` envelope from `API_CONVENTIONS.md` section 5),
`StatusBadge`, `EmptyState`, `LoadingState`, `ErrorState`, `confirmDialog` (the one wrapper
around SweetAlert2 for confirmations - screens never call `Swal` directly), and `FormField`
as the React Hook Form label/error layout helper. These are the building blocks Steps 05+
compose for each master data screen rather than each screen reinventing table/paging/empty
markup.

### 2.5 API client and error contract (`shared/api/`)

- `httpClient.ts` was extended, not replaced: `X-Correlation-Id` is generated per request
  (`crypto.randomUUID()`), `companyId` on `RequestOptions` sends `X-Company-Id` when a screen
  needs it, and `ApiError` now carries the RFC 9457 `code` and `correlationId` parsed from the
  Problem Details body - never `detail`, which is prose. A new `onApiResponseError` hook lets
  `AuthContext`/`CompanyContext` react to failures centrally instead of every call site
  special-casing 401/403.
- `problemMessages.ts` maps each of the twelve documented `code`s
  (`docs/api/API_CONVENTIONS.md` section 4.1) to one user-facing sentence, plus
  `isAuthProblem`/`isCompanyScopeStale` classifiers so the auth/company layers branch on
  `code`, matching the Step 03 handoff instruction exactly.
- `meApi.ts` mirrors the backend's `MeView`/`CompanyAccessView`/`OrganizationView`/`UserView`
  records field-for-field.

### 2.6 Login and routing

`LoginPage.tsx` - React Hook Form, calls `useAuth().signIn`, shows the message `signIn`
returns on failure, redirects to the originally requested location (or `/`) once signed in.
`router.tsx` composes `ProtectedRoute -> AppLayout -> (Dashboard | RequireCompany -> module
placeholders)`, with `/login` as the one public route.

## 3. Environment note: local Supabase defaults

`shared/config/env.ts` already defaulted `apiBaseUrl`; this step added the same treatment for
`supabaseUrl`/`supabaseAnonKey` (`http://localhost:54321` / a placeholder string) rather than
leaving them as empty strings. `@supabase/supabase-js`'s `createClient` throws on an empty
URL, which would otherwise crash the whole app - including every already-tested screen - the
instant `.env.local` is missing, which is the normal state of a fresh clone before a Supabase
project exists. With the default, a missing local Supabase stack surfaces as an ordinary
"sign-in failed" network error on the login screen instead of a blank page. Neither value is a
secret; both are placeholders identical in spirit to `.env.example`.

## 4. Verification

Re-run in attempt 3 against the working tree as found, matching what `scripts/frontend-build.sh`
/ `scripts/frontend-test.sh` run (this step is frontend-only; `scripts/check-all.sh` also runs
the backend Maven build, which is out of scope here and was not re-run):

```
npm run typecheck    tsc -b                clean, no errors
npm run lint         oxlint                0 errors, 2 warnings (see below), unchanged from attempt 1/2
npm test             vitest run            9 files, 33 tests passed
npm run build        tsc -b && vite build  built in 363ms, dist/ produced
```

Numbers match attempts 1 and 2 (build time varies run to run, as expected; module/test/warning
counts are identical), confirming the implementation itself was accurate all along - only the
gate marker was wrong, first in the log output (attempt 1) and then in the report file itself
(attempt 2). Attempt 3 additionally re-read (not just re-ran) `httpClient.ts`, `AuthContext.tsx`,
`CompanyContext.tsx`, `ProtectedRoute.tsx`,
`RequireCompany.tsx`, `LoginPage.tsx`, `router.tsx`, `AppProviders.tsx`, `env.ts`, `navConfig.ts`
and `Sidebar.tsx` end to end and confirmed: the 401 sign-out guard is ref-gated against a
signOut-triggers-401-triggers-signOut loop, `CompanyContext` resets its selection during render
(not in an effect) on sign-out, the sidebar's "show every group while loading" behaviour is
intentional and covered by `Sidebar.test.tsx` (not a capability-check bug), every `capability`
string in `navConfig.ts` (`MASTER_DATA_VIEW`, `FLEET_VIEW`, `ORDERS_VIEW`, `PLANNING_VIEW`,
`TRIPS_VIEW`, `IAM_VIEW`) matches a real constant in the backend's
`shared/security/Capability.java` enum rather than a plausible-looking guess, and
`grep -rln supabase src` outside `shared/auth/` and `shared/config/env.ts` returns nothing.

**Lint detail**: two `react(only-export-components)` warnings remain, on `AuthContext.tsx`
and `CompanyContext.tsx`. Both files export a provider component and its paired hook
(`useAuth`, `useCompany`) from the same module - a standard React context pattern. The rule
only affects Vite Fast Refresh granularity in development (an edit to that file triggers a
full reload instead of a hot patch); it does not affect correctness, tests, or the production
build, and `oxlint` exits `0` with these present. Splitting each into a provider file plus a
one-line hook file would silence them at the cost of file fragmentation for no behavioural
gain; left as-is and reported here rather than silently accepted. All other warnings
encountered during development (`set-state-in-effect`, `exhaustive-deps` on a derived list)
were fixed, not suppressed - see `shared/company/CompanyContext.tsx`, which now resets the
company selection during render (React's documented pattern for reacting to a changed value)
instead of inside an effect, and memoizes the company list against the query's own data
reference.

**Build note**: Vite reports the main chunk at ~730 kB (~213 kB gzipped) before minification
warnings kick in, driven mostly by `@supabase/supabase-js` and Bootstrap. No code-splitting
was introduced - premature for a V1 shell with one route tree and no lazy-loadable heavy
module yet (OR-Tools, maps, etc. are explicitly deferred). Worth revisiting once module
screens land and the bundle grows further.

### 4.1 Test coverage against the brief

| Required case | Test(s) |
|---|---|
| protected route behaviour | `ProtectedRoute.test.tsx` - loading/redirect/allow, three cases |
| API token injection | `httpClient.test.ts` (manual token provider) + `AuthContext.test.tsx` (`registers a token provider...`, the real Supabase-session -> `Authorization` header path) |
| 401 handling, no loop | `AuthContext.test.tsx` (`signs out exactly once...`) + `httpClient.test.ts` (`notifies registered response-error handlers exactly once...`) |
| 403 company-scope-forbidden handling | `CompanyContext.test.tsx` (`forces reselection when the backend answers company-scope-forbidden`) |
| company context reflects only backend data | `CompanyContext.test.tsx` (`exposes only the companies GET /me returned...`, persistence test, idle-before-sign-in test) |
| key shell components | `Sidebar.test.tsx` (always-visible Dashboard, capability-gated groups, no flicker-to-empty while loading), `CompanySelector.test.tsx`, `RequireCompany.test.tsx`, `LoginPage.test.tsx`, `DashboardPage.test.tsx` |
| Problem Details parsing | `httpClient.test.ts` (`parses Problem Details into a stable ApiError.code, not detail`) |

33 tests total, all passing, across 9 files.

## 5. Constraint compliance

| Constraint | How |
|---|---|
| never push, never deploy | nothing was pushed; no deployment exists |
| never mutate a remote/shared database | no database was touched this step; frontend only |
| no real secrets | `.env.example` unchanged (placeholders only); the new local-dev Supabase defaults in `env.ts` are not secrets, matching the pattern already used for `apiBaseUrl` |
| no destructive Git operations | none run |
| Flyway is the only migration owner | untouched this step; no schema change |
| Java owns business logic and authorization | frontend adds no business rule; `hasCapability`/`hasPermission` are UX-only helpers, documented as such in code, and every route they gate still requires its own backend check |
| React talks to Spring Boot for business data | the only Supabase call site is `shared/auth/supabaseClient.ts`, used exclusively by `AuthContext`; `grep -rln supabase src` outside `shared/auth/` and `shared/config/env.ts` returns nothing |
| TMS independent from EWM | nothing added references EWM |
| vertical slice checked end to end | the identity/company slice: `LoginPage -> AuthContext.signIn -> Supabase Auth` then `CompanyContext -> meApi.fetchMe -> httpClient -> GET /api/v1/me -> MeController -> MeService -> IdentityRepository`, all the way to the `Sidebar`/`CompanySelector` UI reading the result |
| do not claim untested passes | every number in section 4 comes from the run captured this session; the lint warnings and bundle-size note are reported rather than omitted |

## 6. Handoff to Step 05 (masters: origins, zones)

1. **Build against `shared/ui/components/`.** `DataTable` + `Pagination` already match the
   backend's `PageResponse` envelope (`shared/api/pageResponse.ts`); a list screen should not
   need to invent paging math.
2. **Company-scoped calls**: pass `companyId: selected.id` (from `useCompany()`) as
   `apiRequest`'s `companyId` option - it becomes `X-Company-Id` automatically. Screens for
   origins/zones/etc. should sit under a `RequireCompany`-guarded route, matching the pattern
   already wired for `masters/origins` (currently a `PlaceholderPage` - replace it, do not
   duplicate the route).
3. **Errors**: use `describeApiError(error)` from `shared/api/problemMessages.ts` for
   `ErrorState`/SweetAlert2 text; do not read `error.message`/`detail` directly in a new
   screen - that string is intentionally not the contract.
4. **Permission-gated actions** (create/edit/delete buttons): call `useCompany().hasPermission('masterdata.origin:manage')`
   (etc.) to decide visibility. This is UX only, same rule as the sidebar; the backend
   `@PreAuthorize` is still the real gate.
5. **Confirmations**: use `confirmDialog` from `shared/ui/components/ConfirmDialog.ts` for any
   destructive action (delete, deactivate) - do not call SweetAlert2 directly.
6. **The two `only-export-components` lint warnings are known and accepted** (section 4); do
   not "fix" them as a drive-by in an unrelated change without discussing the file-split
   tradeoff.

## 7. Files

Added:

```
frontend/tms-web/src/shared/auth/supabaseClient.ts
frontend/tms-web/src/shared/auth/AuthContext.tsx (+ .test.tsx)
frontend/tms-web/src/shared/auth/ProtectedRoute.tsx (+ .test.tsx)
frontend/tms-web/src/shared/company/CompanyContext.tsx (+ .test.tsx)
frontend/tms-web/src/shared/company/RequireCompany.tsx (+ .test.tsx)
frontend/tms-web/src/shared/api/meApi.ts
frontend/tms-web/src/shared/api/pageResponse.ts
frontend/tms-web/src/shared/api/problemMessages.ts
frontend/tms-web/src/shared/ui/Sidebar.tsx (+ .test.tsx)
frontend/tms-web/src/shared/ui/TopBar.tsx
frontend/tms-web/src/shared/ui/CompanySelector.tsx (+ .test.tsx)
frontend/tms-web/src/shared/ui/UserMenu.tsx
frontend/tms-web/src/shared/ui/navConfig.ts
frontend/tms-web/src/shared/ui/components/{PageHeader,FilterBar,DataTable,Pagination,
  StatusBadge,EmptyState,LoadingState,ErrorState,ConfirmDialog,FormField,index}.ts(x)
frontend/tms-web/src/pages/LoginPage.tsx (+ .test.tsx)
frontend/tms-web/src/pages/PlaceholderPage.tsx
docs/overnight/04_FRONTEND_FOUNDATION.md
```

Modified:

```
frontend/tms-web/package.json / package-lock.json   + @supabase/supabase-js
frontend/tms-web/src/shared/config/env.ts            local Supabase defaults
frontend/tms-web/src/shared/api/httpClient.ts         correlation id, company header, Problem Details, error hook
frontend/tms-web/src/shared/api/httpClient.test.ts    coverage for the above
frontend/tms-web/src/app/AppProviders.tsx             Auth/Company providers
frontend/tms-web/src/app/router.tsx                   login route, protected shell, module placeholders
frontend/tms-web/src/shared/ui/AppLayout.tsx           rebuilt on TopBar/Sidebar
frontend/tms-web/src/pages/DashboardPage.tsx (+ .test.tsx)   identity/company card
```

## 8. Result

The frontend now authenticates through Supabase Auth only, resolves identity and company
access exclusively from the backend's `GET /api/v1/me`, attaches the bearer token and
`X-Company-Id`/`X-Correlation-Id` headers to every request, reacts to 401 and
`company-scope-forbidden` centrally without retry loops, and presents a responsive shell with
the required navigation groups, route guards and a reusable component library for the master
data screens that follow. Typecheck, lint (0 errors), 33 tests and the production build all
pass; no direct frontend access to a Supabase business table exists anywhere in the source tree.

TMS_GATE=PASS
