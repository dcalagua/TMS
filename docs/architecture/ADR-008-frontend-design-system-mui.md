# ADR-008 - MUI is the frontend design system of record

**Status:** Accepted - 2026-08-28
**Supersedes:** the "Frontend style" rule of `CLAUDE.md` (Bootstrap + SweetAlert2, avoid MUI)

## Context

`CLAUDE.md`, `README.md` and `docs/architecture/TMS_ARCHITECTURE_V1.md` all stated that the web
client is built on Bootstrap 5 with SweetAlert2 for confirmations, and that MUI must be avoided as
the primary library. That was true, and the step reports under `docs/overnight/` record the work
that made it so.

It is no longer true. Measured against the tree at commit `0757afb`:

- `frontend/tms-web/package.json` declares `@mui/material` 9 and `@mui/icons-material` 9, with
  `@emotion/react` and `@emotion/styled`. It declares **neither `bootstrap` nor `sweetalert2`**.
- 91 files under `src/` import from `@mui/`.
- The only occurrence of the word "bootstrap" in `src/` is the Google Maps *bootstrap loader*, an
  unrelated use of the word.
- The only occurrence of "SweetAlert2" is a comment in `src/lib/ui.tsx` explaining that
  `confirmDialog` **replaced** it. That function is a native MUI dialog.
- `src/theme.ts` holds a MUI theme.

`TMS_RUNTIME_DIAGNOSIS.md` (2026-08-25) had already flagged the contradiction and left the choice
open: write an ADR for MUI, or revert to Bootstrap.

## Decision

**MUI is the design system of record.** The instruction to prefer Bootstrap and avoid MUI is
withdrawn.

Reverting was the alternative, and it was rejected. It would mean rewriting 91 files and every
screen in the product to reach a UI that behaves the same, paid for out of the same nights that
could add appointment scheduling, freight settlement or ETA. The stated goal of the rule was
"reusable enterprise components and responsive dense screens", and that goal is met - the
component library under `src/shared/ui/components`, the drawer convention and the dense list
screens all survived the migration intact. The rule named a means; the means changed and the end
did not.

## Consequences

- New screens use MUI. No Bootstrap, no Tailwind, no Ant.
- Destructive and irreversible actions keep going through `confirmDialog` / `confirmWithReason` in
  `src/lib/ui.tsx`. Screens do not build their own confirmation dialogs, exactly as they never
  called `Swal` directly.
- The drawer-over-modal convention, the loading / empty / error / unauthorized state discipline and
  ES-first i18n are unchanged. They were never Bootstrap's; they are the product's.
- Historical reports that describe a Bootstrap frontend stay as written. They are dated records,
  and this ADR - not an edit to them - is what carries the change forward.
- The authoritative documents (`CLAUDE.md`, `README.md`, `TMS_ARCHITECTURE_V1.md`,
  `ARCHITECTURE_OVERVIEW.md`, `SELLABLE_CAPABILITIES.md`) were corrected when this ADR was accepted.

## What this ADR does not decide

It does not license a redesign. The visual identity, the palette and the information density are
the product's own and are out of scope here; this ADR records which library renders them.
