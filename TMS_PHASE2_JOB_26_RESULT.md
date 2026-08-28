# JOB 26 — Accessibility Foundation

**RESULT = PASS** · **MIGRATION = none** · **D9 = OPEN (PARTIAL)** — as instructed

---

## 1. Gates

| Gate | Result |
|---|---|
| Frontend unit | **136 pass** (129 → 136, **+7**) |
| E2E | **38 pass · 7 skipped** (36 → 38, **+2**) |
| Typecheck / lint / `vite build` | clean · exit 0 |
| Backend | untouched |

## 2. Two real failures found and fixed

### The primary button failed AA in every screen it appeared on

White text on `#5AA97F` measures **2.83:1**; AA requires **4.5:1**. That colour was `primary.main`
for the `forest` theme, so **every contained button in the application** was below the threshold.

**The palette already documented the rule the theme was breaking.** Four lines above the theme
definition in the same file:

```
greenBrand: "#5AA97F",  // identidad EBIM · 2.45:1 vs blanco → SOLO fondos/decoración
green:      "#2F8159",  // ★ acción · 4.74:1 vs blanco (AA)
```

**A rule written in a comment is not a rule that holds.** Fixed by pointing `forest.light.p` at the
action green that was already there; `#5AA97F` stays the brand identity in gradients and decorative
backgrounds, which is what it was always for.

### The wordmark measured 1.72:1

`text.disabled` inside a container at `opacity: 0.6` — the two multiplied.

Instructive for a different reason: **opacity changes no declared colour.** No palette review, no
theme inspection and no component test can find this. Only measuring rendered pixels does.

## 3. Why the browser sweep was necessary

**jsdom cannot evaluate colour contrast at all.** It has no rendering engine; every contrast rule is
silently skipped, and the runs print `HTMLCanvasElement's getContext() not implemented` while doing
it.

**Both failures above were invisible to the component tests and were found by Playwright, in
Chromium.** A green component-level a11y suite is not evidence about colour — which is precisely the
kind of false assurance this job could have shipped instead.

## 4. D9 stays OPEN, deliberately

Closing it would mean claiming TMS is accessible. What is true is narrower:

> Two automated tools run over a fraction of the surface, and the failures they can detect on that
> fraction are fixed.

- **axe automates roughly a third of WCAG.** It cannot see a tab order that jumps, an error nobody
  announces, focus lost when a drawer closes, or meaning carried by colour alone.
- **Only two pages are swept end to end** — login and 404, the only screens reachable without a
  session, because the repository holds no credentials. Control tower, planning, orders, trips,
  settlement, work assignments and costing are **all unchecked** at page level.
- **Nothing has been tested by a person.** No screen reader, no keyboard-only pass, no zoom to 200%.

`docs/frontend/ACCESSIBILITY.md` §3 is written to be read before anybody quotes the green suite, and
§5 lists what would close D9, in the order that would find the most — starting with a keyboard-only
pass over the five daily screens, which would find more than every automated tool here combined.

## 5. One defect of my own

`tsc -p tsconfig.app.json` passed and **`npm run build` failed** on the matcher's missing type
declaration.

**Exactly JOB 19's lesson, and the reason the real build is a mandatory gate**: `tsconfig.app.json`
does not cover test sources.

## 6. Scope note

The theme change alters the shade of green on every primary button — a **visible design change**,
made to fix a real WCAG AA failure. The brand green is untouched wherever it was legitimately used.
Flagged because it is a brand-adjacent decision, not a silent refactor.
