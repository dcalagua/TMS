# TMS by EBIM — Job 14: global UX/product polish audit

- Date: 2026-08-21
- Job: `14` — global UX/product polish
- Scope: `frontend/tms-web`. No backend contract changed, no migration added, no security or
  tenancy rule touched.
- Verdict: **PARTIAL** — one P1 found and fixed, two P2s found and fixed; no quality gate could
  be executed in this session (see §5).

> **How to read this.** Everything below was re-derived from the working tree in this run. The
> brief for this job lists a set of design rules and asks for a redesign only where one is
> warranted. Most of those rules were **already satisfied** before this job started, and §2 says
> so explicitly rather than quietly claiming the work — the previous remediation
> (`UX_UI_REMEDIATION_V1.md`, 2026-08-19) and jobs 01–13 did it. Re-doing any of it would have
> been churn against a working product.

---

## 1. What was audited

Every screen the brief names, plus the shell: sidebar, top bar, Locations, Zones, Frequencies,
Routes, Carriers, Vehicle Types, Vehicles, Drivers, Orders, Planning (runs + board), Trips (list
+ workspace), Control Tower, Reports, Settings (Company, Users) and the Integration Hub
(Inbound/Outbound/webhooks), plus the shared design system under `src/shared/ui`.

---

## 2. Rules that were already met — verified, not assumed

These are recorded so the next job does not spend its budget re-checking them.

| Brief rule | State | Evidence |
|---|---|---|
| Monochrome premium, no accidental Bootstrap blue | **Met** | `styles/tokens.css` §3 remaps every `--bs-*` onto `--tms-*`. `btn-primary` is graphite (neutral theme) or the EBIM green, never Bootstrap `#0d6efd`. No component declares a hex. |
| Bootstrap as infrastructure, not identity | **Met** | Palette applied by remapping Bootstrap's own custom properties; `app.css` adds only what utilities cannot express. |
| `TmsDrawer` for CRUD, no classic modals for big forms | **Met** | Every create/edit surface in the tree is a `TmsDrawer`. Confirmations go to SweetAlert2. |
| Custom `Select` where the component exists | **Met** | **Zero** native `<select>` in production code — every hit of `<select` in the tree is a comment or a test explaining that `Select` is a button + listbox. |
| Action menus without clipping | **Met** | `ActionMenu` portals its panel to `document.body`, with the reasoning (two nested `overflow` containers) written into the component. |
| Empty / loading / error states | **Met on every list screen** | `DataTable` owns all three centrally; all 17 screens that mount it pass `error` **and** `onRetry`. Gaps were in the newer non-table cards — see §3. |
| ES default + EN complete | **Met** | `i18n.test.tsx` enforces exact key parity across all 20 namespaces and fails on any empty value. No hardcoded user-facing prose in production TSX — the only literal strings are format examples (`PEN`, `America/Lima`, `TO-`). |
| Accessibility: labels, focus, keyboard, aria | **Met** | `TmsDrawer` does focus trap, initial focus, Escape, scroll lock, focus restoration, `aria-modal`/`labelledby`/`describedby` and a dirty-close guard. `IconButton` makes `label` a **required** prop, so an unnamed icon button is a compile error; every raw `tms-icon-btn` in the tree carries `aria-label`. |
| Navigation grouping | **Met, and better than the brief's sketch** | The brief proposes an "Operación" group. `navConfig.ts` instead keeps Control Tower and Reports as leaves *above* the module groups, with the rationale written down: neither owns anything, both are a way of looking at the day the modules produced. Left alone deliberately — the brief says to adapt to the real structure when it is better. |

Only one route still resolves to `PlaceholderPage`: `/account`. That is a real absence, not a
polish defect, and is out of this job's scope.

---

## 3. What was actually wrong — the newer screens

The 2026-08-19 remediation predates the trip execution workspace, tendering, costing and
tracking (migrations V25–V35). Those screens are not table screens, so they never inherited
`DataTable`'s state handling, and each grew its own. Three of them reported a **failed request as
a grey caption** — visually identical to the informational captions beside them.

### 3.1 P1 — the trip timeline reported a failed read as an empty day

`TripTimeline` was declared as `{ events, loading }` and nothing else, so `TripWorkspacePage`
had no way to pass a failure and passed `eventsQuery.data ?? []`. A failed request therefore
arrived as an empty array and rendered `workspace.timeline.empty` — *"Todavía no se ha registrado
nada en este viaje."*

This is not cosmetic. The transport event log is **append-only on the server**, so "nothing has
been recorded" is a claim about the *trip*, not about the screen. A supervisor checking whether a
driver had reported an arrival would have read a broken fetch as a definitive "they did not", with
no error, no retry and nothing on screen to suggest otherwise.

**Fix.** `TripTimeline` takes `failed` and `onRetry`; a failure renders `ErrorState` (role
`alert`) with a retry, and the empty copy is unreachable in that state. `TripWorkspacePage` passes
`failed={eventsQuery.isError}` and refetches that one query rather than making the operator reload
the workspace. New key `trips.workspace.timeline.failed`, worded — like the tracking card's
`failed` — to say the log itself is intact, so one broken card does not leave an operator
doubting the cards beside it.

**Guard.** `TripTimeline.test.tsx` (new; the component had no test file) asserts that the failure
state shows the alert *and* that the empty sentence is absent, that retry is wired, that a genuine
empty read still says so, and that loading is announced via `role="status"`.

### 3.2 P2 — trip cost and tender cards had no recoverable error state

`TripCostCard` and `TripTenderCard` rendered `describeApiError(...)` into
`<p className="text-secondary small">` — the same styling as their factual captions ("Este envío
todavía no tiene costo estimado.", "Sin ofertas"), and with no way to retry short of reloading
the page. For tendering the conflation is worse than cosmetic: "no offers" and "we could not tell
you" are different commercial statements.

**Fix.** Both now render `ErrorState` with `onRetry` bound to their own query's `refetch()`. Both
loading captions gained `role="status"`.

### 3.3 Checked and deliberately left alone

`TripTrackingCard` renders its failure as a soft caption too, but that one is **correct**: its
copy names the failure explicitly and reassures about the rest of the trip, ADR-007 says a lost
position costs a map and no business fact, and the distinction between its five states is already
covered by `TripTrackingCard.test.tsx`. Escalating it to a red alert would overstate a provider
outage. No change.

After these fixes, **no `isError` branch anywhere in the tree renders a failure as muted text.**

---

## 4. Contract change

`TripTimeline`'s props gained two optional members:

```ts
failed?: boolean     // the events request failed — distinct from an empty `events`
onRetry?: () => void
```

Both optional, so every existing call site keeps compiling. No API, DTO or database contract was
touched.

---

## 5. Gates — not executed

No quality gate ran in this session, and none is reported as passing.

The cause is already documented as **P1-3** in `docs/overnight-sellable-v4/00_BASELINE_AUDIT.md`
and reproduced exactly here: the runner launches each job with `-p --permission-mode acceptEdits`,
which auto-approves *file writes only*. Command execution is declined with no human present to
approve it. Confirmed empirically this session — `node --version` (v24.18.0) and `python
--version` (3.14.6) return, while `npm run typecheck`, `tsc -b` and running the locale generator
were all refused before execution.

Consequences for this job, stated plainly:

- `npm run typecheck`, `npm test`, `npm run lint` and `npx playwright test`: **not run.**
- The new and modified TSX is therefore **unverified by compiler or test.** It was written to be
  type-correct by construction (both new props optional; `ErrorState` already exported from the
  components barrel and imported in all three files; `refetch()` is standard TanStack Query v5).
- `scripts/i18n/upsert_locale_keys_job14.py` was authored per the established per-job convention
  but **could not be executed**. The single key it adds was applied to `es/trips.json` and
  `en/trips.json` by hand in exactly the format the generator emits (2-space indent, `sort_keys`
  ordering — `failed` falls between `empty` and `loading` in both files), so re-running the script
  when a shell is available is a no-op rather than a diff. Parity is preserved and
  `i18n.test.tsx` would enforce it.
- Docker/Testcontainers remains irrelevant here: this job changed no backend code and no schema.
  `DB_CERTIFICATION=NOT_APPLICABLE`.

---

## 6. Not committed, and why

`COMMIT=none`. This is a deliberate refusal, not an omission.

`frontend/tms-web/src/pages/trips/` is **untracked in its entirety** — it is jobs 08–13's
uncommitted work, as are ~430 other entries in the working tree. Staging the three files this job
edited is impossible without `git add`-ing that whole directory, which would sweep several other
jobs' unreviewed work into a commit labelled "job 14 UX polish" and misattribute it.

Combined with CLAUDE.md's standing **"Do not stage the overnight pack"** and the fact that nothing
here could be typechecked or tested, committing was the wrong call. The changes are complete and
sit in the working tree for whoever commits the pack as a whole.

---

## 7. Carried forward

| # | Item | Severity |
|---|---|---|
| 1 | `/account` still resolves to `PlaceholderPage` | P2 |
| 2 | No quality gate is executable under the pack runner (pack-level P1-3) | P1, pack-level |
| 3 | The job-14 changes are unverified by compiler or test until a shell is available | P2 |
