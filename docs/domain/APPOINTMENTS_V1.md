# TMS by EBIM - dock and appointment scheduling (V1)

Owner: `com.ebim.tms.appointments`. Schema: `V41__dock_appointment_scheduling.sql`.

## 1. The problem

A shipment knows where it is going and roughly how long it takes to serve. It could not say **when
the door is free**. Every operation running more than a few trucks a day solves that with a booking
sheet outside the system, and the cost of it being outside is the whole point: two trucks arrive at
one door at 09:00, one waits two hours, and the TMS that planned both had no way to know.

## 2. One vehicle per door, guaranteed by the database

    ALTER TABLE tms.appointment ADD CONSTRAINT ex_appointment_no_double_booking
        EXCLUDE USING gist (
            resource_id WITH =,
            tstzrange(window_start, window_end) WITH &&
        ) WHERE (status NOT IN ('CANCELLED', 'NO_SHOW'));

**An `EXCLUDE` and not a unique index**, because what is being refused is an *overlap*, which no
unique key can express. It needs `btree_gist` for the `=` half: gist alone cannot compare uuids.

`AppointmentService.requireFree` exists to produce a **readable refusal** naming the booking in the
way — it is *not* the guarantee. Two dispatchers booking 09:00 on the same door in the same instant
both see a free door in their own snapshot and both pass it. The constraint is the one place they
cannot both get past, and the `DataIntegrityViolationException` branch turns the loser's violation
into a sentence about the dock board rather than a 500.

`AppointmentServiceIntegrationTest.twoSimultaneousBookingsOneWins` runs exactly that race against
real PostgreSQL and asserts one winner.

### Why a door takes one vehicle and not N

A site with six doors has **six rows**. That is not a simplification to relax later: PostgreSQL can
refuse two overlapping ranges on one key and **cannot** refuse "more than N overlapping". A capacity
column would move the invariant back into application code — which is precisely where the booking
sheet already fails.

### Which statuses hold the door

`CANCELLED` and `NO_SHOW` release it, and **only** those two: nobody used the door. Everything else
did or will, `COMPLETED` included — two trucks recorded as having used one door at the same time is
a history that cannot be true.

`REQUESTED` holds it too. An unconfirmed request that did not block the slot would let two trucks be
promised one door, which is the failure this feature exists to prevent.

`AppointmentStatusTest.occupancySetMatchesTheDatabase` asserts the Java set and the constraint's
`WHERE` clause say the same thing. If they drifted, the application would believe a door was free
that the database would then refuse.

## 3. Time, and a bug worth remembering

**Windows are absolute instants.** The location's zone is used for exactly two things: reading the
door's local opening hours, and displaying a booking. Never the server's — a dock in Arequipa opens
at 07:00 in Arequipa whatever the application runs on.

**Opening hours are stored as minutes since local midnight, not as `time`.** The first version used
a `time` column and the integration test caught what happened: this application sets
`hibernate.jdbc.time_zone: UTC`, which normalises temporal values on write, so "opens at 00:00" was
stored as `05:00+00` and "closes at 23:59" as `04:59+00` — the close now *before* the open, and the
CHECK constraint refused the row.

Had the constraint not caught it, every site's opening hours would have silently shifted by its own
UTC offset. **An integer cannot be zone-shifted by any configuration**, and a local opening time is
genuinely a quantity of minutes into the site's day rather than an instant. `LocalTime` remains the
type every caller sees.

**No overnight windows.** A door open 22:00–06:00 is two rows on two days, which is what a reader
means anyway. Allowing `closesAt < opensAt` would put a wrap-around branch in every containment
check, and that branch is the one nobody tests.

## 4. The lifecycle

    REQUESTED ─▶ CONFIRMED ─▶ ARRIVED ─▶ COMPLETED
         │            │  ▲         │
         │            ▼  │         ▼
         └────▶ RESCHEDULED    CANCELLED
                      │
                      ▼
                   NO_SHOW

- **`RESCHEDULED` is a live state, not a historical one.** The alternative — close the old booking,
  open a new one — loses the fact that this is the *same* commitment moved, and a site that has
  agreed to a slot twice has a different relationship with a carrier than one asked twice. The
  original window is kept on the row.
- **A vehicle that arrived can never be a no-show.** Somebody was there.
  `ck_appointment_no_show_never_arrived` says the same in the database.
- **A no-show keeps its record** and releases the slot. It is what a demurrage or missed-slot
  conversation is argued from; deleting it destroys the site's only evidence. V41 withholds the
  `DELETE` grant on `tms.appointment` to make that structural.

## 5. Opening hours, closures and out-of-service doors

| Rule | Behaviour |
|---|---|
| A door with **no calendar** | **Open.** A company that configured nothing has not said the door is shut, and refusing every booking until somebody fills a form makes the feature unusable on day one |
| Outside the local hours | Refused, naming them |
| A day with no entry | Refused, naming the day |
| A **closure** (`resource_blocked_slot`) | Refused, naming the reason |
| A **deactivated** door | New bookings refused; **existing ones untouched** — a truck already on the road for yesterday's slot must not silently lose it |

## 6. Tenancy

Four tables, all company-scoped with the tenant policy (ADR-005), and the composite foreign keys
make it structural: `fk_appointment_resource_company` means a company's booking cannot name another
company's door as a *database* fact, not only a service check. The dock code is unique **per
location**, so two companies may each have a `DOCK-1` — which is how sites actually label them.

## 7. The WMS / EWM boundary

This module owns the **transport** side of a dock appointment: which shipment is coming, when, to
which door. A warehouse's own dock schedule — labour, equipment, put-away — is a different system's
record.

**V41 creates no WMS or EWM table, column, foreign key or view.** Integration with a warehouse
system happens through a port, in `integration`, when somebody asks for it — never by sharing a
table. Mixing the two databases is the shortcut that makes both systems impossible to upgrade
separately, and it is the exact thing `CLAUDE.md`'s first rule forbids between TMS and EWM.

## 8. Permissions

| Permission | Held by |
|---|---|
| `appointments.appointment:read` | ORG_ADMIN, COMPANY_ADMIN, PLANNER, **VIEWER** |
| `appointments.appointment:manage` | ORG_ADMIN, COMPANY_ADMIN, PLANNER |
| `appointments.resource:manage` | ORG_ADMIN, COMPANY_ADMIN |

VIEWER holds the read, unlike tenders: a booking carries **no price**, and the yard, the gate and
the warehouse all need the board to do their jobs. Configuring doors is an administrator's authority
— adding one changes what the whole site can promise.

## 9. Not here

- **No capacity column.** See §2.
- **No recurring appointments.** A standing 09:00 Tuesday slot is a template feature; nothing has
  asked for one.
- **No automatic booking from planning.** Which door a shipment uses is a site's decision, often the
  customer's. Planning V2 proposing appointments needs a dock-capacity model in the engine and its
  own brief.
- **No calendar/grid UI yet.** The board is a day at a site as a table, which is what a gate reads.
  A time-grid view is a presentation change on the same data.
