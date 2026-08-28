# Morning report - 2026-08-28

**Chain complete: JOBS 01-16, all PASS.** Nothing was pushed, deployed, or written to any shared
environment. 31 commits are waiting on your local `dev` branch for you to review.

---

## Read this first

Three things, if you read nothing else.

**1. Two long-standing debts are closed, and one is deliberately not.**
D2 (an accepted tender could contradict the vehicle on the shipment) and D1 (a proposed plan had no
price) are both closed with tests. **D3 - delivered quantity - is still open on purpose**, and JOB 10
wrote a formal evaluation explaining why: it is a missing capability rather than a defect, it must
not be inferred from ordered/allocated/planned quantities because all three are wrong in exactly the
`PARTIAL` case, and it does **not** block Settlement. Building Settlement in JOB 11 confirmed that.

**2. Nine defects were found and fixed, two of them mine.**
The most consequential were silent ones. A `time` column shifted every dock's opening hours by its
own UTC offset. A delete resolved a block by bare id, so a vehicle clerk could have removed a
driver's medical absence - undoing a permission split V26 made on purpose. And invalid JPQL that
`mvnw compile` accepted took down 323 tests the moment Spring validated it: **the second time this
chain was saved by running `clean test` instead of trusting a compile.**

**3. Five new debts were opened.** That is the right direction, not a failure. D5 to D9 were already
true last night and simply undocumented - no work assignment model, no own-fleet cost model, no tests
on Control Tower V1, no enum/`CHECK` coverage guard, and **no accessibility testing anywhere in the
project**. Each says what it would take to close.

---

## Where the code stands, measured after the last commit

| | |
|---|---|
| Backend | **1674 pass · 0 fail · 0 skipped** (`./mvnw clean test`) |
| Frontend unit | **97 pass** |
| E2E | **34 pass · 7 skipped** |
| Typecheck / lint / build | clean |
| Flyway | **V1-V43**, contiguous, each written once |
| Working tree | clean · **31 commits local, none pushed** |

Backend tests went **1585 → 1674** across this session. The one apparent drop - backend `skipped`
going 7 → 0 at JOB 09 - is Docker being up for the whole run rather than part of it. **No failing
test was ever converted into a skip.**

---

## What was built, in one line each

| JOB | | |
|---|---|---|
| **08** | V41 | Dock scheduling. No double booking is a database fact (`EXCLUDE USING gist`), proven by two real threads racing for one door |
| **09** | V42 | Fleet availability, and **D2 closed**: a shipment agreed with one carrier and carrying another's truck cannot depart |
| **10** | V43 | Stop ETA - and **ADR-011**, because this needed permission from `CLAUDE.md`, not just code |
| **11** | - | Proposal pricing, **D1 closed**. Most of it is what it refuses to report |
| **12** | - | Control tower: what will stop a truck today, before it stops it |
| **13** | - | Integration health: age, not count |
| **15** | - | Four static guards over the whole codebase; one unscoped finder removed |
| **14** | - | Fifteen component tests over the sentences that make the new screens honest |
| **16** | - | Certification, re-run from a clean tree |

---

## Three decisions I'd want you to check

These are the places I exercised judgement rather than following the brief mechanically. If you
disagree with any, they are cheap to revisit now and expensive later.

### 1. I wrote an ADR to un-defer stop ETA (JOB 10)

`CLAUDE.md` defers "ETA calculation, geofencing and automatic arrival detection". JOB 10's brief
asks for two of them. Rather than diverge silently, I wrote **ADR-011** and moved **exactly one**.

ETA moved because V27's objection to it was about *inputs* - "there is nothing to put in them" - and
V38, V14 and V11 have since supplied every term. **Automatic arrival detection did not move**, and
ADR-007's rule is untouched: a position informs a person and never moves a lifecycle. The geofence
is a circle on a location and writes to nothing. `CLAUDE.md` now says all of this instead of
contradicting the code.

**If you'd rather ETA had stayed deferred, ADR-011 is the single thing to reverse.**

### 2. Four jobs shipped no migration

JOBs 11, 12, 13, 14 and 15 added no schema. For D1 that is structural - planning KPIs are computed
per proposal and never stored, so it was a correctness debt and not a schema gap. The others are read
paths, guards and tests. Adding a migration to make the night look busier would have been exactly the
empty scaffolding the brief forbids.

### 3. I kept Control Tower V2 to one panel

Adding appointment and ETA tiles would have made the job look bigger and the screen worse. Every row
on the blockers panel is a **hard stop** - a state that makes `dispatch` refuse - and mixing those
with advisory warnings is how a panel stops being read. Both alternatives are named in the doc as
not-built, with the reason.

---

## Where to start when you sit down

1. **`TMS_OVERNIGHT_16_RESULT.md`** - the certification, including a plain list of what is *not*
   certified.
2. **`docs/architecture/ADR-011-stop-eta-and-geofence-observation.md`** - the one decision that needed
   your architecture's permission.
3. **`docs/domain/DELIVERED_QUANTITY_EVALUATION.md`** - the D3 answer, which is the input to whatever
   you decide about invoicing.
4. **`docs/security/STATIC_GUARDS.md`** - every guard the build now enforces, old and new, and what
   each refuses.
5. `git log --oneline -31` - the commits, newest first.

---

## What is explicitly not done

Stated plainly, because a report that claims more than it did is worth less than none.

* **Nothing was deployed and nothing ran against a real environment.** All verification is local, on
  Testcontainers and a local build. **The deploy remains unverified**, exactly as it was last night.
* **The 7 authenticated E2E specs did not execute here** - they need a real environment. Whatever
  they would prove is unproven by this run.
* **No load or performance testing.** The 10,000-orders/day target is honoured in the schema and the
  query patterns; it has not been measured.
* **No accessibility verification exists at all** (D9), and **Control Tower V1 is still untested**
  (D7).
* **Nothing was pushed.** 31 commits sit on `dev`, waiting for you.
