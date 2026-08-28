# Accessibility

**JOB 26 · debt D9 remains OPEN, downgraded from "nothing exists" to PARTIAL.**

This document exists so that nobody reads a passing test suite as a claim of accessibility.

---

## 1. What exists now

| | |
|---|---|
| `axe-core` component sweep | `src/a11y/components.a11y.test.tsx` — 7 checks over the panels and the shared components every screen uses |
| `@axe-core/playwright` page sweep | `e2e/accessibility.spec.ts` — login and 404, WCAG 2.0/2.1 A and AA |
| Result | **Both green**, after two real failures were found and fixed |

## 2. The two failures found, because they are instructive

### The primary button failed AA everywhere it appeared

White text on `#5AA97F` measures **2.83:1**. AA requires 4.5:1.

That colour is `primary.main` for the `forest` theme, so **every contained button in the
application** was below the threshold — the login button, every "Save", every "Confirm".

The instructive part: **`theme.ts` already documented the rule it was breaking.** Four lines above
the theme definition:

```
greenBrand: "#5AA97F",  // identidad EBIM · 2.45:1 vs blanco → SOLO fondos/decoración
green:      "#2F8159",  // ★ acción · 4.74:1 vs blanco (AA)
```

The palette knew. The theme used the decorative green as the action colour anyway. **A rule written
in a comment is not a rule that holds.**

Fixed by pointing `forest.light.p` at the action green. `#5AA97F` remains the brand identity in
gradients and decorative backgrounds, which is what it was always for.

### The wordmark measured 1.72:1

`text.disabled` inside a container at `opacity: 0.6`. The two multiplied.

Instructive for a different reason: **opacity does not change any declared colour.** No review of the
palette, no inspection of the theme, and no component test can find this. It is only visible when
something measures the rendered pixels — and `text.disabled` is a colour for switched-off controls,
not for text anybody is meant to read.

## 3. What these tests cannot see

**This is the section that matters.**

### axe automates roughly a third of WCAG

It finds a button with no accessible name, an input with no label, a skipped heading level and
insufficient contrast. It does **not** find:

- a tab order that jumps around the screen
- a validation error that is displayed but never announced
- focus lost when a drawer closes
- a table whose meaning depends on colour alone
- a label that is present and wrong
- an interaction that needs a mouse

### jsdom cannot evaluate contrast at all

The component tests run in jsdom, which has no rendering engine. **Every colour-contrast rule is
silently skipped there** — the runs even print `HTMLCanvasElement's getContext() not implemented`.

Both failures in §2 were found by the **Playwright** sweep, in Chromium. A component-level pass is
not evidence about colour.

### Only two pages are checked end to end

Login and 404 — **the only screens reachable without a session**, because the repository contains no
credentials. Every operational screen in the application is unchecked by the page-level sweep:
control tower, planning, orders, trips, settlement, work assignments, costing.

### Nothing has been tested by a person

No screen reader. No keyboard-only pass. No zoom to 200%. No user with a disability has been near
this software.

## 4. Why D9 stays OPEN

Closing D9 would mean claiming TMS is accessible. What is true is narrower:

> Two automated tools run in CI over a fraction of the surface, and the failures they can detect on
> that fraction are fixed.

**That is a foundation, not accessibility.** Automated coverage of one third of the criteria on two
of twenty-odd screens does not become a claim about the product by being green.

## 5. What would close it

In the order that would find the most:

1. **A keyboard-only pass over the five screens an operator uses daily.** Costs an afternoon and will
   find more than every automated tool here combined.
2. **Extend the page-level sweep to authenticated screens** — needs the E2E credentials that already
   block the 7 skipped specs, so it comes free with that.
3. **A screen reader pass over the drawers**, which are where focus management goes wrong.
4. **Contrast audit of the status chips**, where colour carries meaning and the palette has many
   pairs no test has measured.
5. **Then** a statement about conformance, with a level and a date.
