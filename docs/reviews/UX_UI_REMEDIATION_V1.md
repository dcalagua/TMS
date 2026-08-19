# TMS by EBIM - UX/UI remediation V1

Scope: `frontend/tms-web`. No backend contract was changed, no migration was added and no
security or tenancy rule was relaxed. Every fix is in the frontend.

Status date: 2026-08-19. Branch `main`, five local commits, nothing pushed.

---

## 1. Root cause: the first sign-in closed the session

**Observed:** correct credentials, Supabase accepted them, the app reached the authenticated
area for an instant, one backend call answered 401, `AuthContext` called `signOut()`, and the
user was returned to the login form. The second attempt always worked.

**Cause, in two parts.**

*The token arrived later than the status it belonged to.* `httpClient` obtained the bearer
token by calling `await supabase.auth.getSession()` on every request. That is an asynchronous
round trip through supabase-js, which serialises session access behind a `navigator.locks`
acquisition. `AuthContext` meanwhile flipped `status` to `signedIn` from the
`onAuthStateChange` callback. React committed that render - and `CompanyProvider` fired
`GET /api/v1/me` - without any ordering guarantee that the token provider would resolve to the
session that had just been created. When it did not, the request went out with **no
`Authorization` header at all**, which is precisely the case the backend answers with a bare
401 (`TmsAuthenticationEntryPoint` sets `WWW-Authenticate: Bearer`, not `error="invalid_token"`).
This also explains why the second attempt worked: by then the session was already in storage,
so the read resolved immediately.

*Any 401 destroyed the session.* The response-error handler was, in effect,
`any 401 -> signOut()`. There was no attempt to recover, so a single transient refusal - for
any reason, including the one above - tore down a session that was perfectly valid.

**Fix.**

- The session is mirrored into a ref inside `AuthProvider`, updated from the initial
  `getSession`, from the result `signInWithPassword` itself returns, and from
  `onAuthStateChange`. `httpClient` reads the access token from that ref **synchronously**.
  `status` and the token it corresponds to are therefore published in the same commit, and the
  lock/await ordering is gone rather than papered over. No timeout was added anywhere.
- An authentication failure now gets exactly one controlled recovery: a **single-flight**
  refresh (N concurrent 401s produce one refresh, not N) followed by **at most one** replay of
  the request. Only if that fails is the error reported to the auth layer, which is what signs
  the user out. This removes the `401 -> signOut -> 401` loop, and a `signingOutRef` guard plus
  a "no session held" check stop a sign-out from cascading.

**Guard:** `e2e/auth.spec.ts` scripts the exact scenario - the first authenticated request is
answered 401 - and was verified to **fail against the pre-fix `AuthContext`/`httpClient`** and
pass after them. `FIRST_LOGIN_REQUIRES_SECOND_ATTEMPT` cannot return unnoticed.

## 2. Root cause: the menu links did not navigate

Sidebar `NavLink`s carried `data-bs-dismiss="offcanvas"` alongside `data-bs-target`. Bootstrap
attaches a delegated dismiss handler on `document` that calls `preventDefault()` on any anchor
carrying that attribute, and it resolves component instances against a registry belonging to
whichever copy of Bootstrap's JS loaded first - `main.tsx` loads the UMD bundle, so an
`import { Offcanvas } from 'bootstrap'` elsewhere would not even see the same instances.
Neither behaviour composes with React Router's client-side navigation.

**Fix:** the drawer is React state. Links do exactly one thing - navigate - and the route
change itself closes the drawer, which covers clicks, browser Back/Forward and programmatic
navigation with one rule. Tapping the entry for the screen already open closes it too, since
there is no route change there to react to. At `lg` and above the class being toggled is inert,
so desktop is unaffected. The same reasoning removed Bootstrap's dropdown data API from the
company switcher, the user menu and the new row action menus.

**Guard:** `src/app/navigation.test.tsx` drives the application's **real** route table
(`appRoutes` is now exported for exactly this) and asserts, for all thirteen menu entries, that
a click changes `location.pathname` *and* mounts that route's screen. `e2e/navigation.spec.ts`
repeats it in a real browser on desktop and mobile. The previous suite only asserted that a
`NavLink` existed, which is why it stayed green while the links were broken.

## 3. Root cause: layout overflow

`AppLayout` used `min-w-0`, which is not a Bootstrap utility and had no CSS behind it, so the
main flex column still sized against its content. Replaced with a real `.tms-min-w-0` rule and
applied to every flex item that wraps scrollable content. Tables scroll inside their own
container, never on the page.

---

## 4. Defects found by the browser suite

Two were found by the Playwright screenshots and would not have shown up in jsdom:

- **Sidebar contrast.** At `lg` and above, Bootstrap's own `.offcanvas-lg` rule declares
  `background-color: transparent !important`. That stripped the navy off the desktop column and
  left light slate text on a light background - a WCAG contrast failure, not a cosmetic one.
- **Sign-in headline contrast.** The brand panel's `h2` inherited `--bs-heading-color`, the dark
  body colour, on a navy panel.

One was found by the overflow assertions:

- **14px horizontal overflow at 320/360px.** The top bar's right cluster could not shrink, so a
  long company name pushed the language switch off screen. Fixed by letting that flex item
  shrink and the name truncate, rather than by hiding anything.

---

## 5. Design system

`src/styles/tokens.css` is the single source of colour, radius, elevation and layout metrics.
No component declares a hex value. Bootstrap is **not** replaced: the palette is applied by
remapping Bootstrap's own `--bs-*` custom properties, so buttons, alerts, badges, tables and
form controls inherit the product's identity instead of being overridden selector by selector.
`src/styles/app.css` adds only what Bootstrap utilities cannot express - the shell, operational
table density, the status vocabulary, and the modal/drawer chrome.

Tokens include `--tms-primary`, `--tms-sidebar-bg`, `--tms-surface`, `--tms-border`,
`--tms-text`, `--tms-muted`, `--tms-success`, `--tms-warning`, `--tms-danger`,
`--tms-radius-*` and `--tms-shadow-*`. `bootstrap-icons` is used for navigation, primary
actions, states and empty states.

### New shared components

Added because a screen needed them, not to fill a catalogue:

| Component | Purpose |
|---|---|
| `TmsModal` | The accessible dialog: focus trap, initial focus, Escape, backdrop, scroll lock, focus restoration, ARIA wiring, full-screen below `sm` |
| `Drawer` | Side panel sharing `useDialogBehaviour` with `TmsModal` |
| `useDialogBehaviour` | The dialog behaviour itself, in one place and testable once |
| `useMenu` | Popup-menu keyboard behaviour: arrows, Home/End, Escape, outside click |
| `ActionMenu` | The `...` row menu for secondary actions |
| `AppCard`, `SectionHeader`, `Toolbar`, `SearchInput`, `IconButton`, `Skeleton` | Panel, grouping, filter strip, search, icon-only control, loading placeholder |
| `ActiveBadge` | The active/inactive status, translated in one place |

Strengthened: `PageHeader` (meta slot), `DataTable` (numeric/action columns, own scroll
container, skeleton loading), `StatusBadge` (dot **plus** label - status is never colour alone),
`EmptyState` (icon), `Pagination`, `ErrorState`, `LoadingState`.

---

## 6. Internationalisation

- i18next + react-i18next. Resources split across **14 domain namespaces**, not one file per
  language.
- Default `es`, fallback `en`. The browser's `Accept-Language` is deliberately **not** consulted
  for the first choice, so an operator on an English machine still lands on the Spanish UI.
- The choice persists in `localStorage` (`tms.language`) and is switchable from the top bar.
- **Keys are type-checked.** `CustomTypeOptions` binds `t()` to the Spanish bundle, so a typo or
  a deleted key fails `npm run typecheck` rather than rendering `items.origins` to an operator.
  Configuration that carries a key instead of text - the navigation tree, the placeholder
  screens - is typed the same way.
- API error copy still branches on `problem.code`, never on `detail`. Each documented code maps
  to a key. `detail` is still shown verbatim only where the backend writes it for a planner to
  read (`describePlanningError`).
- Regional formatting through `Intl` (`es-PE`, `en-US`) for dates, decimals, kg, m³ and
  percentages. `Intl` has no sanctioned `cubic-meter` unit, so volumes localise the number and
  append the SI symbol.
- URLs are never translated (`/masters/origins` stays, the heading says "Orígenes").

Two tests keep the bundles honest: ES/EN **key parity**, and **no empty translation** anywhere.

---

## 7. Responsive strategy

- One markup for the sidebar (`.offcanvas-lg`): a static column at `lg`, a drawer below it.
- The desktop column collapses to an icon rail; the preference persists.
- Wide content scrolls **inside its own container**. The rule asserted in the browser is
  `document.documentElement.scrollWidth <= window.innerWidth`, on every screen, at
  **320, 360, 390, 768, 1024, 1366, 1440 and 1920** px.
- Dialogs fill the viewport below `sm` rather than floating inside a scrolling page.

## 8. Accessibility

Done: visible `:focus-visible` rings including on dark chrome; landmarks (`header`, `nav`,
`main`); one `h1` per screen; `aria-current` on the active menu entry; honest `aria-expanded`
on every toggle; `aria-label` on every icon-only control; `role="menu"`/`menuitem` with full
keyboard support; focus trap, focus restoration and scroll lock for `TmsModal`/`Drawer`; status
conveyed by dot **and** label, never by hue alone; the two contrast failures in section 4.

## 9. Tests

| Suite | Count | Command |
|---|---|---|
| Frontend unit/integration (Vitest) | **301** (from 219) | `npm test` |
| End-to-end (Playwright, Chromium) | **40** | `npm run e2e` |

End-to-end runs against a real browser with **Supabase Auth and the TMS API intercepted**
(`e2e/support/app.ts`): no project credentials, no running backend, no seeded database, and a
test can script the exact backend behaviour it is about. Console output is asserted clean on
every screen. Review screenshots are written to `frontend/tms-web/artifacts/ui-review/`, which
is git-ignored - they are a regenerated review aid, not a source artefact.

---

## 10. Module coverage

| Module | Shell + i18n | List redesign | Form redesign |
|---|---|---|---|
| Sign-in | done | n/a | done |
| Dashboard | done | done | n/a |
| Origins, Destinations, Zones, Frequencies, Routes | done | done | **pending** |
| Carriers, Vehicle types, Vehicles | done | done | **pending** |
| Orders | titles and actions only | **pending** | **pending** |
| Planning runs / board | titles and actions only | **pending** | **pending** |
| Trips, Security | placeholder screens, translated | n/a | n/a |

---

## 11. Remaining debt

All P2. Nothing here blocks the operation; every item is visual or per-module polish.

1. **Form dialogs still build Bootstrap modal markup by hand** (12 files). `TmsModal` exists and
   is tested, but the forms have not been moved onto it, so they still lack a focus trap, scroll
   lock and focus restoration. This is the largest single accessibility gap left.
2. **Form field labels, placeholders and validation messages are still English** in those 12
   dialogs. The `validations` namespace exists and is unused.
3. **Field grouping** into semantic fieldsets (Identification / Address / Location / Operation /
   Status; Identification / Capacities / Dimensions / Restrictions) is not applied.
4. **Orders and Planning are not redesigned.** Orders still lacks the result-count header and the
   dense operational table; the planning board is not a split panel, `TripCard` and `CapacityBar`
   have not been reworked, and the mobile tab/segmented layout is not built.
5. **Enum labels transported by the API** (`ORIGIN_TYPE_LABELS`, `ORDER_STATUS_LABELS`,
   `VEHICLE_AVAILABILITY_STATUS_LABELS`) are still English constants in the api modules.
6. **Route-level lazy loading** is not applied; the bundle is ~956 kB (260 kB gzipped).
7. **Async autocomplete** for large master lookups is not built.
8. Mobile collapsible filters exist as a `Toolbar` component but the list screens still use
   `FilterBar`.

---

## 12. Results

```
AUTH_FIRST_LOGIN=PASS
SESSION_PERSISTENCE=PASS
SIDEBAR_NAVIGATION=PASS
MOBILE_NAVIGATION=PASS
DEFAULT_LANGUAGE_ES=PASS
LANGUAGE_SWITCH=PASS
LOGIN_UI=PASS
APP_SHELL_UI=PASS
MASTER_DATA_UI=PARTIAL   (lists done, form dialogs pending)
FLEET_UI=PARTIAL         (lists done, form dialogs pending)
ORDERS_UI=FAIL           (not started)
PLANNING_UI=FAIL         (not started)
RESPONSIVE=PASS
ACCESSIBILITY=PARTIAL    (shell and shared components done, form dialogs pending)
FRONTEND_TESTS=301
E2E_TESTS=40
TYPECHECK=PASS
BUILD=PASS
CONSOLE_ERRORS=0
P0=0
P1=0
P2=8
```

**Not closed.** The functional and security-relevant work is complete and guarded by tests that
were verified to fail against the previous code. The remaining items in section 11 are UI debt
in Orders, Planning and the form dialogs.

Nothing was pushed.
