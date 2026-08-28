# Incident runbook

**Scope: failures this system can actually have.** Every entry below is traceable to a real
constraint, guard or port in the code. Nothing here describes a procedure nobody has performed —
where that is the case, it says so instead.

---

## 0. Before anything

1. **Get the correlation id** from the user's error screen. Every request has one and it is in every
   log line for that request.
2. **`/actuator/health`** — is it the process, or is it one operation?
3. **Do not restart first.** Almost nothing here is fixed by a restart, and a restart destroys the
   in-flight evidence.

---

## 1. "Everything is 500" / the application will not start

**Most likely: an invalid JPQL query.** Spring validates every repository method at context startup,
so one bad query takes down the whole application rather than one endpoint.

This has happened **twice** in development (JOB 13, JOB 23) and both times `mvnw compile` passed
first. The signature in the log:

```
Could not resolve attribute 'x' of 'com.ebim.tms.…'
Error creating bean with name 'someRepository'
```

**Fix:** the query, not the entity. **Never** relax the entity mapping to make a query compile.

**Prevention that already exists:** `./mvnw clean test` catches it. An incremental compile does not.

## 2. "The database rejected something and the user saw a 500"

TMS deliberately pushes invariants down to constraints, so a service bug surfaces as a constraint
violation rather than as bad data. That is working as intended, but a 500 means a service failed to
give the readable refusal first.

| Constraint in the message | What it means | Where the readable refusal should be |
|---|---|---|
| `ex_appointment_*` | Two bookings for one door | `AppointmentService` |
| `ex_own_fleet_profile_*` | Overlapping cost profiles (V48) | `OwnFleetCostProfileService.save` |
| `uq_work_assignment_*` | Two dispatchers built one resource's day (V47) | `WorkAssignmentService` |
| `uq_payable_export_invoice` | Double export (V46) | `SettlementService.export`, which is idempotent |
| `ck_*` | A three-layer invariant reached the last layer | The service that should have refused |

**Fix:** add the readable refusal. **Never drop the constraint** — it is the thing that stopped the
bad data.

## 3. "A user in company A can see company B's data"

**Treat as a security incident.** Stop, capture the correlation id and the exact request, do not
"fix and move on".

Defence in depth means three things must have failed together:

1. A service took a `CompanyScope` and ignored it.
2. A repository finder was not company-scoped — `TenantScopedRepositoryTest` exists to make this
   impossible to introduce, and caught a real one in JOB 22.
3. RLS did not filter (ADR-005) — check the connection is running as `tms_app` and not as the owner.

Point 3 is the one worth checking first: **if the application connects as the schema owner, RLS does
not apply to it at all** and layers 1 and 2 are the only defence left.

## 4. "Integrations look broken"

`GET /api/v1/integration/health` (JOB 13) reports **age, not count** — deliberately, because a
partner who sent nothing all morning has a bigger problem than one with three failures.

- Check `tms.integration.requests` by `provider` tag.
- A single provider stale = their problem, usually credentials.
- All providers stale = ours.

**This does not affect `/actuator/health`, on purpose.** A stale partner feed must not take TMS out
of a load balancer.

## 5. "Planning says it cannot price a plan"

Not a fault. Read the reason:

| Reason | Meaning | Fix |
|---|---|---|
| `NO_AGREEMENT_FOR_SOME_TRIP` | A carrier has no rate card | Configure the tariff |
| `OWN_FLEET_NOT_COSTABLE` | Our own truck, and no profile or no measurable input (V48) | Configure the cost profile, or geocode the stops |
| `MIXED_CURRENCIES` | Two currencies in one plan | Nothing to fix — TMS will not invent an FX rate |
| `NO_TRIPS` | The engine placed nothing | Look at the unplanned reasons |

**A missing total is the system refusing to publish a number it cannot stand behind.** Do not
"fix" it by configuring a placeholder tariff of zero: a zero makes that option unbeatable in every
comparison, which is a worse failure than no number.

## 6. "The control tower is showing alarming counts"

Three separate counts, and they are not interchangeable:

- **`blockedShipments`** — trucks that cannot depart. **Act now.**
- **`openExceptions`** — a person reported a problem (V27). Somebody already knows.
- **`openAdvisories`** — worth knowing, stops nothing (JOB 23). Often a rounding difference.

**Never sum them.** They answer different questions, which is why they are three panels.

## 7. Migrations

**Applied migrations are immutable.** If Flyway reports a checksum mismatch, somebody edited a
migration that had already run.

- **Do not run `flyway repair` to make the error go away.** That hides the divergence; it does not
  resolve it.
- **Do not `db reset` anything shared.**
- The fix is a **new** migration that reconciles the difference, plus finding out how the old file
  came to be edited.

## 8. What this runbook cannot tell you

- **No deployment has been verified.** See `DEPLOYMENT.md` — the procedures there are read from
  configuration, not from a performed deploy.
- **There are no alerts**, so every incident here starts with a human noticing.
- **There is no performance baseline**, so "it feels slow" cannot currently be answered with a
  number (JOB 25).
- **There is no rollback procedure that anybody has executed.**
