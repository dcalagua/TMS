# TMS by EBIM - UX/UI remediation V1

Scope: `frontend/tms-web`. No backend contract was changed, no migration was added and no
security or tenancy rule was relaxed. Every fix is in the frontend.

Status date: 2026-08-19. Branch `main`, seven local commits, nothing pushed.

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

---

## 9. Form dialogs (phase 4A)

All **twelve** form dialogs were migrated from hand-built Bootstrap modal markup onto the shared
`TmsModal`, one file at a time. `grep -r 'modal d-block' src/pages` now returns nothing. Submit,
create/edit, loading, error handling, permissions, callbacks, React Hook Form and the TanStack
Query invalidation are unchanged, and no API contract moved.

| File | Module | Complexity |
|---|---|---|
| `planning/CreateTripModal.tsx` | Planning | low |
| `planning/TripVehicleModal.tsx` | Planning | low |
| `planning/PlanningRunFormModal.tsx` | Planning | low |
| `masters/ZoneFormModal.tsx` | Master data | low |
| `masters/FrequencyFormModal.tsx` | Master data | medium |
| `masters/OriginFormModal.tsx` | Master data | medium |
| `masters/DestinationFormModal.tsx` | Master data | high |
| `masters/RouteFormModal.tsx` | Master data | high |
| `fleet/CarrierFormModal.tsx` | Fleet | medium |
| `fleet/VehicleFormModal.tsx` | Fleet | medium |
| `fleet/VehicleTypeFormModal.tsx` | Fleet | high |
| `orders/OrderFormModal.tsx` | Orders | high |

A thirteenth dialog turned up during phase 5: `TripDetailDrawer` was still an `.offcanvas` whose
Escape handler only fired when focus happened to be inside it. It now uses the shared `Drawer`.

**Field grouping.** Fields sit in semantic fieldsets with real `<legend>`s - Identification /
Address / Geographic location / Operation for a destination; Identification / Capacities /
Dimensions / Restrictions for a vehicle type - built from plain Bootstrap rows and columns,
without nesting cards or losing density.

**Copy.** Every label, placeholder, help line, title, button and validation message in those
dialogs is translated. Validation still branches on the backend's `code`, never on its prose.

**Enum labels.** The eight English `*_LABELS` maps are gone from `shared/api`. Presentation now
goes through `useEnumLabels`, reading the `statuses` bundle; `enums.test.ts` fails if any value
the API can send lacks a label in either language. `IN_MAINTENANCE` reads "En mantenimiento".

**Duplication removed.** `applyApiFieldErrors` replaces the field-error mapping that had been
written out in nine dialogs: known fields go inline, fields the form does not render surface at
form level, everything else is described from its stable `code`.

## 10. Orders and planning (phase 5)

**Orders.** The header states how many orders the query found; three tiles give the weight,
volume and pallets of the rows on screen, labelled as *this page's* totals because the backend
paginates and anything beyond it is not known here. Nothing else is invented - there is still no
KPI endpoint. Origin and destination are separate columns, the three amounts are right-aligned
numeric columns through the regional formatters, and the row's three competing buttons became
one `...` menu that follows the order's own lifecycle. Filters are in the shared toolbar, which
collapses them behind a toggle below `md`. Pagination stays server-side.

**Planning run.** The board header states plan number, status, trip count, origin and date.

**`CapacityBar`** was rebuilt to show what a planner needs at a glance: the label, `used / limit`
in the dimension's own unit, and the percentage - for example `Peso  8,850 kg / 10,000 kg
(88.5%)` - above the bar itself.

Over-capacity and near-limit are reported in words *and* an icon, never by colour alone, and the
bar exposes `aria-valuenow` to assistive technology. It still refuses to derive any verdict the
backend did not give: `exceeded` comes from the API, and the three states the backend documents
as different - unlimited, a real zero limit, a normal limit - render differently.

**`TripCard`** carries number, status, vehicle, carrier, departure, order and destination counts
and all three capacity dimensions.

**Responsive planning.** Below `lg` the two panels become tabs rather than two unusable narrow
columns; trip detail opens in the shared drawer.

**Three defects found while doing it:**

- Taking an order off a trip did not return it to the eligible pool until the page was reloaded:
  the board invalidated only its own query, never the eligible one.
- `PageHeader` refused to shrink its action row, so three buttons pushed a 320px screen into a
  horizontal scrollbar.
- The eligible-orders table scrolled sideways inside a third-width column and hid the order
  number and destination - the two things a planner scans for. It is now a compact list carrying
  number, destination, customer, priority and all three amounts on two dense lines.

**Not done on purpose:** drag and drop. The classic interaction had to be solid first, and
pulling in a drag library for appearance alone was explicitly out of scope.

## 11. Tests

| Suite | Count | Command |
|---|---|---|
| Frontend unit/integration (Vitest) | **367** (from 219 at the start) | `npm test` |
| End-to-end (Playwright, Chromium) | **57** | `npm run e2e` |

End-to-end runs against a real browser with Supabase Auth and the TMS API intercepted
(`e2e/support/app.ts`): no project credentials, no running backend, no seeded database. The
planning suite adds a **stateful** stub (`e2e/support/planning.ts`) that keeps the run in memory
and recomputes capacity, so it exercises the real sequence - create a trip, assign an order,
watch the bars move, take it back off - rather than fixed payloads.

Console output is asserted clean on every screen. Review screenshots are written to
`frontend/tms-web/artifacts/ui-review/` (git-ignored, regenerated by `npm run e2e`):
`login-desktop`, `login-mobile`, `dashboard-desktop`, the eight master/fleet screens,
`orders-desktop`, `orders-mobile`, `planning-desktop`, `planning-mobile`,
`planning-board-desktop`, `planning-board-mobile`, `form-destination-desktop`,
`form-destination-mobile`, `navigation-drawer-mobile`.

## 12. Module coverage

| Module | Shell + i18n | List | Form |
|---|---|---|---|
| Sign-in, Dashboard | done | done | done |
| Origins, Destinations, Zones, Frequencies, Routes | done | done | done |
| Carriers, Vehicle types, Vehicles | done | done | done |
| Orders | done | done | done |
| Planning runs / board / trip drawer | done | done | done |
| Trips, Security | placeholder screens, translated | n/a | n/a |

## 13. Remaining debt

All P2.

1. **Route-level lazy loading** is not applied; the bundle is ~1 MB (about 270 kB gzipped).
2. **Async autocomplete** for large master lookups is not built - the form selects still fetch
   up to 200 rows eagerly.
3. **Master and fleet list screens still use `FilterBar`**, not the collapsible `Toolbar` that
   Orders now uses, so their filters do not collapse on a phone.
4. **Drag and drop** on the planning board, deferred by decision.
5. `planning.eligible.title` is unused now that the board owns the column heading.

## 14. Results

```
FORMS_INVENTORIED=12
FORMS_MIGRATED=12/12
MANUAL_MODAL_MARKUP_REMAINING=0
FORM_I18N_ES=PASS
FORM_I18N_EN=PASS
FORM_RESPONSIVE=PASS
MODAL_ACCESSIBILITY=PASS

ORDERS_UI=PASS
ORDERS_RESPONSIVE=PASS
PLANNING_UI=PASS
PLANNING_RESPONSIVE=PASS
TRIP_CARD=PASS
CAPACITY_BAR=PASS
PLANNING_OPERATIONS=PASS
ES_EN=PASS

AUTH_FIRST_LOGIN=PASS
SESSION_PERSISTENCE=PASS
SIDEBAR_NAVIGATION=PASS
MOBILE_NAVIGATION=PASS
DEFAULT_LANGUAGE_ES=PASS
LANGUAGE_SWITCH=PASS
LOGIN_UI=PASS
APP_SHELL_UI=PASS
MASTER_DATA_UI=PASS
FLEET_UI=PASS
RESPONSIVE=PASS
ACCESSIBILITY=PASS

CONSOLE_ERRORS=0
FRONTEND_TESTS=367
E2E_TESTS=57
TYPECHECK=PASS
BUILD=PASS
P0=0
P1=0
P2=5
```

`PLANNING_OPERATIONS=PASS` covers create trip, assign vehicle, add order, remove order, move
order, confirm and cancel, capacity recalculation and the backend's own capacity/concurrency
refusals shown verbatim. Drag and drop is out of scope by decision.

Nothing was pushed.

---

## 15. Right-side drawers and the monochrome design system

### 15.1 The UX rule, stated once

TMS now has one answer to "where does this open?", and it is the same answer in every module:

| Intent | Surface |
| --- | --- |
| Create, edit, view detail, configure | Right-side drawer (`TmsDrawer`) |
| Confirm, destroy, discard, report something important | SweetAlert2 |
| Complex workspace: planning board, dashboard, list screens | Full page |

SweetAlert2 is deliberately *not* used for extensive CRUD forms, and the centred modal is gone:
once every consumer had moved, `TmsModal` was deleted rather than left as a second way to open a
dialog. `TmsDrawer` is the only dialog surface the product renders itself. The reasoning is
operational rather than aesthetic. A planner editing an
origin is reading the list of origins; a centred modal replaces that list with a floating card,
while a panel anchored to the right keeps the context they were working in on screen.

### 15.2 `TmsDrawer`

One component, `src/shared/ui/components/TmsDrawer.tsx`, is the CRUD surface for the whole
product. There is no per-module offcanvas.

- Open/closed state is React state throughout. Bootstrap's `data-bs-dismiss`,
  `data-bs-toggle` and `data-bs-target` data API is not used anywhere in a drawer - the drawer
  contract suite asserts their absence. That data API is what once stopped the sidebar links
  navigating, because its delegated handler calls `preventDefault()` on anchors.
- Structure: sticky header (title, optional subtitle, close), a body that is the only thing
  that scrolls (`overflow-y: auto; overscroll-behavior: contain`), sticky footer holding
  Cancel and Save.
- Widths come from tokens: `sm` 420px, `md` 520px, `lg` 660px, `xl` 820px. Below `sm` the panel
  is pinned to both edges and takes the whole screen; its footer buttons go full width.
- Focus trap, initial focus, Escape, focus restoration, body scroll lock, `aria-labelledby`,
  `aria-describedby`, backdrop and keyboard cycling come from `useDialogBehaviour`, so that
  behaviour is implemented and tested in one place.
- Motion: 220ms right-to-left in, easing `cubic-bezier(0.32, 0.72, 0, 1)`, suppressed entirely
  under `prefers-reduced-motion`.
- Unsaved work is never discarded silently. When the form reports `isDirty`, dismissing by X,
  Escape or backdrop asks through SweetAlert2 first; a clean form closes immediately, because a
  confirmation nobody needs is friction.

Two defects were found and fixed while building it, both proved by a test that fails against the
previous code:

1. `useDialogBehaviour` keyed its setup effect on the identity of `onClose`. The drawer's dismiss
   handler changes identity the moment the form turns dirty, so the effect tore down and re-ran
   on the first keystroke, restoring focus to the trigger and then pushing it to the first field.
   Every field in the app would have swallowed all but the first character typed into it. The
   callback is now read through a ref and the effect runs once per open.
2. Bootstrap's grid tiers are measured against the viewport, not against the panel the columns
   live in, so a `col-sm-3` that is comfortable across a full-width page collapsed to a sliver in
   a 520px drawer - narrow enough to clip "Cliente" to "Clien". Inside anything but an `xl`
   drawer, thirds through fifths are now halves, above the `sm` breakpoint only.

### 15.3 Monochrome design system

`src/styles/tokens.css` is the single source of colour; `src/styles/app.css` contains no
hardcoded colour at all (`grep -cE '#[0-9a-fA-F]{3,6}'` returns 0). Bootstrap 5 remains the
infrastructure and is re-themed through `--bs-*` custom properties rather than fought:
`--bs-primary` is ink `#16181d`, so primary buttons are near-black, and `--bs-link-color` is the
body text colour. Semantic colours are desaturated - success, warning, danger, info and neutral
each with a `-subtle` fill and a `-border` - and exist only to carry meaning in badges and
alerts. One accent, `--tms-accent`, is used for focus rings and nothing else.

No Tailwind, MUI or Ant was introduced. SweetAlert2 keeps its Bootstrap button classes.

### 15.4 Forms migrated

Twelve CRUD forms, migrated and verified one file at a time, in the order given:

| # | File | Module | Size |
| --- | --- | --- | --- |
| 1 | `masters/OriginFormDrawer.tsx` | Maestros | lg |
| 2 | `masters/DestinationFormDrawer.tsx` | Maestros | lg |
| 3 | `masters/ZoneFormDrawer.tsx` | Maestros | md |
| 4 | `masters/FrequencyFormDrawer.tsx` | Maestros | lg |
| 5 | `masters/RouteFormDrawer.tsx` | Maestros | xl |
| 6 | `fleet/CarrierFormDrawer.tsx` | Flota | md |
| 7 | `fleet/VehicleTypeFormDrawer.tsx` | Flota | lg |
| 8 | `fleet/VehicleFormDrawer.tsx` | Flota | lg |
| 9 | `orders/OrderFormDrawer.tsx` | Pedidos | xl |
| 10 | `planning/PlanningRunFormDrawer.tsx` | Planificación | md |
| 11 | `planning/CreateTripDrawer.tsx` | Planificación | md |
| 12 | `planning/TripVehicleDrawer.tsx` | Planificación | md |

`planning/TripDetailDrawer.tsx` moved from the earlier minimal `Drawer` to `TmsDrawer`; both
that component and `TmsModal` were removed rather than left as second ways to do the same thing. Its
`dirty` signal is the reordered-but-unsaved stop list.

Each form keeps one component for create and edit, its React Hook Form validation, its TanStack
Query mutations and invalidations, its permission checks and its API contract. Enum values sent
to and received from the API are untouched; only their visual representation is translated.
Saving closes the drawer and refreshes the table through query invalidation - `drawers.spec.ts`
asserts that a marker set on `window` survives the save, so a `window.location.reload()` would
fail the suite.

### 15.5 List pattern

Orígenes is the reference list screen; the pattern reached the other seven master and fleet
lists plus Pedidos and Planificación through the shared components rather than by copying
markup: `PageHeader` gained an identity tile (`icon`), and `DataTable` gained a persistent
result count (`total`) that, unlike the pager, does not disappear when everything fits on one
page.

### 15.6 Gates

```
DESIGN_SYSTEM_MONOCHROME=PASS
BOOTSTRAP_BASE_KEPT=PASS
NO_TAILWIND_MUI_ANT=PASS
HARDCODED_COLOURS_IN_CSS=0

DRAWER_COMPONENT=PASS
DRAWER_STRUCTURE=PASS
DRAWER_SIZES=PASS
DRAWER_ANIMATION=PASS
DRAWER_ACCESSIBILITY=PASS
DRAWER_DIRTY_GUARD=PASS
DRAWER_STATE_NOT_DATA_API=PASS

CRUD_FORMS_INVENTORIED=12
CRUD_FORMS_MIGRATED=12/12
MANUAL_CRUD_MODALS_REMAINING=0
SWEETALERT_SCOPE=PASS
NO_PAGE_RELOAD_ON_SAVE=PASS
ENUM_VALUES_UNCHANGED=PASS

LIST_PATTERN_REFERENCE=PASS
LIST_PATTERN_APPLIED=8/8
LOGIN_UI=PASS
APP_SHELL_UI=PASS

AUTH_FIRST_LOGIN=PASS
SESSION_PERSISTENCE=PASS
DESKTOP_NAVIGATION=PASS
MOBILE_NAVIGATION=PASS
I18N_ES=PASS
I18N_EN=PASS

RESPONSIVE_320_1920=PASS
CONSOLE_ERRORS=0
FRONTEND_TESTS=387
E2E_TESTS=63
TYPECHECK=PASS
LINT=PASS
BUILD=PASS
P0=0
P1=0
```

Responsive coverage is 320, 360, 390, 768, 1024, 1366, 1440 and 1920; no viewport scrolls the
document sideways, and a drawer never measures wider than the layout viewport.

Nothing was pushed.
