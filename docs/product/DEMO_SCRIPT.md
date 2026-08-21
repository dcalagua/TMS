# TMS by EBIM — demo script

A rehearsable walkthrough of the whole product, in the order an operation actually happens.

- **Full run:** about 55 minutes. **Short run:** §6, about 20 minutes.
- Everything below runs against a **local, disposable** database. Nothing in this document touches
  a shared or production environment, and no seed here is ever applied to a remote project.
- Read [`SELLABLE_CAPABILITIES.md`](SELLABLE_CAPABILITIES.md) first if you have not, and
  [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md) before you take questions.

---

## 1. Prepare the environment (the day before, not the morning of)

### 1.1 Bring the stack up

```bash
# 1. Platform. Needs Docker Desktop running.
supabase start

# 2. Env templates, once.
cp backend/tms-api/.env.example backend/tms-api/.env
cp frontend/tms-web/.env.example frontend/tms-web/.env.local
```

Open `backend/tms-api/.env` and check **one line before anything else**:

```
TMS_DB_URL=jdbc:postgresql://localhost:54322/postgres
```

> **Stop if it names a `supabase.co` host or a pooler.** Starting the backend with
> `TMS_FLYWAY_ENABLED=true` against a remote project runs twelve never-executed migrations against
> it. Point it at the local stack, or set `TMS_FLYWAY_ENABLED=false`, before you start anything.

Then:

```bash
# 3. Backend. Runs Flyway on startup; watch V1..V35 apply and succeed.
./scripts/dev-backend.sh

# 4. Frontend.
./scripts/dev-frontend.sh          # http://localhost:5173
```

### 1.2 Seed identity

```bash
psql "postgresql://postgres:postgres@localhost:54322/postgres" \
     -f supabase/seeds/local_dev_seed.sql

psql "postgresql://postgres:postgres@localhost:54322/postgres" \
     -v demo_password='<choose one>' \
     -f supabase/seeds/demo_auth_users.sql
```

That gives you three accounts in the organization `DEMO`:

| Account | Role | Scope |
|---|---|---|
| `admin@demo.local` | `ORGANIZATION_ADMIN` | organization-wide |
| `planner.lima@demo.local` | `PLANNER` | `DEMO-LIMA` |
| `viewer@demo.local` | `VIEWER` | `DEMO-LIMA` |

Sign in as the **administrator** for the whole demo, and keep the planner account for §5.9.

### 1.3 Optional but worth it

| Setting | Why | Where |
|---|---|---|
| `VITE_GOOGLE_MAPS_API_KEY` | Maps draw the stops and the location picker. Without a key the screens degrade to manual lat/long — honest, but less impressive | `frontend/tms-web/.env.local` |
| `TMS_EVIDENCE_STORAGE_MODE=LOCAL` + `TMS_EVIDENCE_STORAGE_ROOT=<absolute path>` | Turns on proof-of-delivery attachments. **Off by default** — without it you can still record delivery results, just not attach a signature | `backend/tms-api/.env` |
| `TMS_WEBHOOK_SECRET_KEY=<32+ random chars>` | Turns on outbound webhooks for §5.8 | `backend/tms-api/.env` |

### 1.4 Load the demo data

Follow [`demo-data/README.md`](demo-data/README.md), including the **service-date search and
replace**. Do this the day before and then *plan nothing* — the demo starts from an operation with
masters, a fleet and a backlog of unplanned orders, which is exactly where a prospect's own day
starts.

### 1.5 Final checklist, five minutes before

- [ ] Backend up, frontend up, signed in as `admin@demo.local`, company `DEMO-LIMA` selected.
- [ ] `Pedidos` shows 8 orders; 5 `Listo para planificar`, 3 `No listo`.
- [ ] `Planificación` shows **no runs**. If it shows one from a rehearsal, rebuild (§7).
- [ ] Browser language is Spanish (or switch to English deliberately, as a talking point).
- [ ] Second browser tab open on `/control-tower` — you will come back to it.
- [ ] Terminal ready with the credential from §5.3 exported.

---

## 2. The story to tell

> *"A distribution centre in Lima ships to six stores and one industrial customer. Orders arrive
> from the ERP every night. Somebody has to turn them into trucks by 06:00, and then somebody
> has to know what actually happened to those trucks by 18:00. That is the whole product."*

Keep coming back to that sentence. Each act below closes one half of it.

---

## 3. Act by act

### 3.1 Act 0 — Where you are (2 min)

Sign in. Point at three things and move on:

1. **The company selector** in the top bar. `DEMO-LIMA` and `DEMO-AREQUIPA` are separate tenants of
   one organization. Switch to Arequipa: every screen empties. Switch back.
   *"You are not filtering. The server resolved which company you may act in from your own
   membership, and there is no id in the request the browser could have changed."*
2. **The sidebar**, grouped by module, each group behind a capability. A role that cannot price a
   shipment does not see `Tarifas` at all — and the endpoint refuses it too, so hiding is only the
   courtesy.
3. **The bell** — `Avisos`. Empty now. It will not be by the end.

### 3.2 Act 1 — Master data: one place, many uses (8 min)

**Scenario 1 — Location: store and distribution centre.**

`Maestros → Ubicaciones`. Nine places, one list.

Open `CD-CALLAO`. Point at **Roles**: it is both `ORIGIN` and `DESTINATION`.

> *"Most TMS products make you maintain a store master and a customer master and then reconcile
> them. Here a place is one record. What it **is** — a store, a DC, a plant — is its type. How it
> may be **used** — ship from, ship to — is its role. `Orígenes` and `Destinos` in the menu are the
> same list with a filter on it, not two more masters to keep in sync."*

Open `ST-4711`. Show the map, the service time (25 minutes — planning will use it), the external
reference back to the ERP.

**Zones.** `Maestros → Zonas` → create `ZN-LIMA-SUR`, *Lima Sur*. Go back to `ST-4712` and assign
it. Two screens, thirty seconds; it makes the point that masters are editable in the product.

**Scenario 2 — Frequency and route.**

`Maestros → Frecuencias` → create:

| Field | Value |
|---|---|
| Code | `FR-LUN-MIE-VIE` |
| Name | Lunes, miércoles y viernes |
| Weekly rules | Monday, Wednesday, Friday — cut-off `16:00` the day before |

Then add an **exception**: pick a public holiday, mark it *no service*. Add a second exception on a
different date: *service, but cut-off `11:00`*.

> *"That second one is the one that matters. Christmas Eve is open and closes early. A calendar
> that can only say open or closed makes somebody keep that in a spreadsheet."*

Assign the frequency to `ST-4711` and `ST-4712` (`Ubicaciones` → open → *Frecuencias*). Then use the
eligibility check on the store: pick the holiday and watch it come back **not eligible, with the
reason**.

`Maestros → Rutas` → create `RT-SUR-01`, *Circuito Sur*, from `CD-LIMA`, with stops
`ST-4712 → ST-4714 → ST-4713`. Reorder two of them. On one stop, set a **service-time override** of
`40` minutes.

> *"The store's master says 25 minutes. On this route it is 40, because the dock is shared. The
> override belongs to the stop, not to the store — the same store on another route is 25 again."*

### 3.3 Act 2 — The fleet (5 min)

**Scenario 3 — Carrier, vehicle, driver.**

`Flota → Transportistas` — three carriers. `Flota → Tipos de vehículo` — open `VT-REF` and show the
temperature range and the body type. `Flota → Vehículos` — open `VEH-003` and show the **pallet
override**: this truck takes 12, not the 14 its type says.

> *"Capacity is resolved, not typed. The type sets it, the vehicle may narrow it, and planning gets
> whichever is the real answer."*

Show `VEH-006`: `En mantenimiento`. *"It will not be offered when we plan."*

`Flota → Conductores` → create one:

| Field | Value |
|---|---|
| Code | `DRV-001` |
| Name | Ana Quispe |
| Document | DNI / 45871236 |
| Licence | `Q45871236`, category `A-IIIb` |
| Licence expiry | **a date about three weeks from today** |
| Carrier | `TR-ANDES` |

Create a second driver, `DRV-002` / Luis Bravo, with a licence expiring next year.

> *"Note the expiry on the first one. Keep it in mind — it comes back on its own in a few minutes,
> and nobody has to run a report to find it."*

### 3.4 Act 3 — Orders arrive (7 min)

**Scenario 4a — the ERP sends them (machine to machine).**

`Configuración → Integraciones` → *Nueva credencial*:

| Field | Value |
|---|---|
| Name | Demo ERP |
| Scopes | `integration.location:write`, `integration.order:write` |

The secret is shown **once**. Copy it.

> *"That is the only time this value exists anywhere. We store a one-way hash of it. If they lose
> it they rotate — and the old one keeps working for a week so nobody needs a coordinated cutover."*

In the terminal:

```bash
export TMS_TOKEN='tmsc_....tmss_....'          # what you just copied

curl -sS -X POST http://localhost:8080/integration/v1/orders/batch \
  -H "Authorization: Bearer $TMS_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-nightly-release-1' \
  --data @docs/product/demo-data/06-orders-inbound-batch.json | jq
```

Five orders, `"outcome": "CREATED"`, `"status": "READY_FOR_PLANNING"`.

**Now run the exact same command again.** Same five, still `CREATED`, and the response header says
`X-Idempotent-Replay: true`.

> *"Their job scheduler fired twice. Nothing was duplicated and nothing was changed — they got the
> answer they missed the first time, byte for byte. Both halves of that matter: identity by their
> own reference, and a replay of the original response."*

Back in the browser: `Configuración → Integraciones` → **Entradas**. Both deliveries are recorded,
with their outcome, their correlation id and no payload — *"we log that it happened, not what was
in it, unless a customer asks us to."*

**Scenario 4b — somebody uploads a spreadsheet.**

`Pedidos → Importar`. Download the template (point out that the template, the header matching and
the help text all come from one enum, so they cannot drift). Upload
`docs/product/demo-data/05-orders-with-one-bad-row.csv` and press **Validar**.

Two errors, each naming its row and its column: an unknown destination and a priority value that is
not one. **Nothing has been written.**

> *"That is a dry run. The apply is one transaction — either the whole file lands or none of it
> does. An import that half-worked is worse than one that failed."*

Now upload `05-orders.csv` and apply. Three more orders, `No listo`.

`Pedidos` — eight orders. Open `SO-2026-000104` (the declared-only one): no lines, and the totals
say **declarado**. Open `SO-2026-000101`: two lines, totals say **calculado**.

> *"The lines win when there are lines. A declaration fills in whatever the lines are silent
> about. And the browser can never send the effective totals — it sends lines, the server does the
> arithmetic, and planning reads only what the server produced."*

Mark `SO-2026-000107` ready for planning from its row menu. *"There is a completeness check behind
that button, which is why it is a button and not a column."*

### 3.5 Act 4 — Planning (8 min)

**Scenario 5 — manual planning.**

`Planificación` → *Nueva corrida*: origin `CD-LIMA`, the demo's operating date.

The board opens: eligible orders on the left, trips on the right.

Create a trip, assign `VEH-001` (8 t / 42 m³ / 14 pallets). Drag `SO-2026-000101` and
`SO-2026-000102` onto it and watch the three capacity bars fill. Drag `SO-2026-000107` on as well —
6,800 kg — and it **refuses**, naming the dimension that broke.

> *"Weight, volume and pallets, all three, live. Not a warning after the fact."*

Move an order to a second trip. Reorder its stops.

Try to put `VEH-001` on a second trip for the same date: refused. *"One vehicle, one active trip per
operating day, and that is a unique index in the database, not a check somebody remembered to
write."*

Assign `DRV-001` to the trip. **A warning appears about the licence** — it expires within thirty
days.

> *"Nobody ran a report. The rule fired at the moment somebody could still choose a different
> driver, which is the only moment it is useful."*

**Scenario 6 — automatic planning.**

Delete the trips you just made (or open a fresh run) and press **Planificación automática**.

The preview drawer shows proposed trips and, beside them, **every order that was not planned, with
the reason**.

> *"Two things about this. First, it never confirms anything — it proposes drafts a person edits.
> Second, the count reconciles: considered equals planned plus unplanned, and no order can quietly
> vanish. A planner who cannot trust that number will re-check the whole board by hand, and then
> the feature has cost more than it saved."*

Apply it. Edit one proposed trip by hand to show that the output is ordinary editable planning, not
a black box.

### 3.6 Act 5 — Committing and pricing (6 min)

**Scenario 12a — the tariff.**

`Tarifas → Tarifas` → *Nueva*:

| Field | Value |
|---|---|
| Carrier | `TR-ANDES` |
| Scope | `ORIGIN` → `CD-LIMA` |
| Vehicle type | (blank — any) |
| Valid from | today, no end date |
| Currency | `PEN` |
| Base amount | `180.00` |
| Per pallet | `24.00` |
| Minimum | `250.00` |

**Confirm the run.** From the board: *Confirmar*.

> *"The plan is now binding. The capacity it was validated against is frozen onto each trip, the
> orders move to `PLANIFICADO`, and a row lands in the outbox for anyone integrated with us. From
> here, what the trip **carries** cannot change — only what happens to it."*

`Viajes` — the trips are there, indexed by the day rather than by the run. Open one.

**Scenario 12b — cost.** In the trip workspace, *Estimar costo*. The estimate shows each component
as its own line and names the rate card that produced it.

> *"A component the card does not charge for produces no line. A component set to zero produces a
> zero line — because that proves the question was asked. And it was priced with the tariff in
> force on the shipment's own date, not today's, so re-estimating last month's load gives last
> month's number."*

**Scenario 12c — tendering.** *Nueva oferta*: amount `S/ 1,250.00`, expiry tomorrow 12:00, a note.
Save as draft, edit the amount once, then **Enviar**.

> *"Draft is editable and publishes nothing. Sent is frozen and the carrier can already see it."*

Answer it as the carrier, over their API. **Substitute the shipment number showing on the screen** —
`SH-00000001` below is illustrative, not a guarantee about where the sequence starts:

```bash
curl -sS -X POST "http://localhost:8080/integration/v1/tenders/SH-00000001/response" \
  -H "Authorization: Bearer $CARRIER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"decision":"ACCEPTED","reason":"Confirmado, unidad ABC-101"}' | jq
```

(The carrier credential is a second one, issued from the same drawer as §3.4: tick
`integration.tender:respond` and the form asks which carrier it belongs to — `TR-ANDES`. That scope
is the one that means nothing without a carrier, so the field appears only when it is ticked. Issue
it during setup, not on stage.)

Refresh the trip. Accepted, with the timestamp and **`response_source` saying it came from their
system, not from a planner typing it in**.

> *"At most one carrier can ever have accepted a shipment. That is enforced by a partial unique
> index — no sequence of retries can produce a second."*

If the API is inconvenient on the day, reject it from the screen instead and show that the rejection
is kept: attempt 2 is a new row, and attempt 1 still says who said no and why.

### 3.7 Act 6 — The day happens (8 min)

Stay in the trip workspace. The lifecycle card offers only the transitions the **server** says are
legal — the browser holds no copy of the state machine.

**Scenario 7 — dispatch.**

*Listo para despacho* → *Despachar*. On dispatch, leave the time **as it is** for one trip; on
another, back-date it fifteen minutes.

> *"The dispatcher reached a keyboard at 09:05 and the truck left at 08:40. If we stamped our own
> clock, the actual times would describe the office instead of the fleet. And the planned time is
> never overwritten — the gap between the two is the only number anybody is judged on."*

Check the bell: a `TRIP_DELAYED` notice if the actual departure was after the planned one.

**Scenario 8 — stop execution.**

On stop 1: *Llegada* → *Inicio de servicio* → *Atendida*. Point at the dwell time it computes.

On stop 2: *Llegada*, then **record the delivery** (§3.7 next).

On stop 3: **Omitir** with a typed reason (`CUSTOMER_CLOSED`).

> *"Skipped and failed are two different facts and this screen never merges them. Skipped means
> never attempted, by decision — the customer called at six. Failed means we went and could not
> deliver. Both need a typed reason, both open a problem, and that is what turns 'how many
> deliveries did we miss last week and why' into a query instead of a reading exercise."*

**Scenario 9 — an exception.** *Reportar un problema* → `TRAFFIC_DELAY`, a note. It appears in the
problems list, and a `EXCEPTION_OPENED` notice appears in the bell. Resolve it — resolving requires
a note.

**Scenario 10 — proof of delivery.**

On stop 2, for one order: **Entregado**, receiver *"J. Ramos"*, delivered at the arrival time. For
a second order on the same stop: **Rechazado**, with the mandatory reason.

> *"The stop is `Atendida` and one of its orders is `Rechazado`, and **both are true**. A stop is
> about the vehicle at a destination. A delivery result is about the goods of one order. One status
> cannot say both, which is why there are two."*

If evidence storage is on (§1.3), attach a photo or a signature image to the delivered order and
then download it back. *"The bytes are behind an authenticated, company-scoped request — never a
public URL, never a column in the database."* A `DELIVERY_FAILED` notice — the only `CRITICAL`
severity in the product — appears for the rejected one.

**Complete the trip.** Try it while a stop is still `Pendiente`: refused, naming the stops.

> *"There is no override. A stop that genuinely should not count is skipped, which costs a typed
> reason. A trip closed over three stops nobody ever touched is a day that only looks finished."*

Resolve the last stop, then complete.

### 3.8 Act 7 — What management sees (5 min)

**Scenario 11 — control tower.** Switch to the tab you left on `/control-tower`.

Seven questions on one screen: what leaves today, what is late and by how much, what has an open
problem, what is on the road, which stops ran past their window, what is still unplanned, which
vehicles are carrying the most. Every row clicks through to the shipment.

> *"No chart on it, deliberately. Every number is a count somebody can act on. And it owns nothing —
> every figure comes from the module that decides it, so it cannot become a second, slowly diverging
> opinion about the same day."*

Point at a delay: it says `+15 min`, not `Tarde`.

> *"There is no grace period, and that is on purpose. 'Late means more than fifteen minutes' is a
> commercial policy, and no customer has agreed one with us. Picking a number for them would quietly
> reclassify real lateness as punctuality in every report built on top."*

**Scenario 12d — reports.** `Reportes`. Last 30 days by default.

Shipments, on-time departure, service, delivery success, exceptions per 100 trips, utilisation,
tenders, cost — with a daily chart.

Find a figure showing **—** and stop on it.

> *"That is the most important thing on this screen. We recorded no departure for those, so there
> is no on-time percentage. Showing 0% would accuse us of never being punctual; showing 100% would
> congratulate us for the absence of evidence. Both are numbers somebody would quote in a board
> pack. A dash is the honest answer, and it is one rule in one class, so no card can decide
> differently."*

Point at utilisation: *"summed and then divided, never the average of the per-truck percentages — a
full van and an empty articulated truck is not a 50% day."*

Export the CSV.

### 3.9 Act 8 — Running it as a business (4 min)

**Administration.** `Configuración → Compañía` — profile, time zone, default country, document
prefixes. `Configuración → Usuarios y accesos` — invite somebody, change their roles, revoke, restore.

> *"Before this existed, adding a customer's second company meant an engineer at a psql prompt.
> Notice also that revoking is done at the membership, never at the person: switching the person
> off would lock them out of every organization they work for, including ones this administrator
> has never heard of."*

**Integration hub.** `Configuración → Integraciones`:

- **Credenciales** — the two you issued, with their scopes. Rotate one: a new secret, and the old
  one keeps working for the grace period.
- **Entradas** — every inbound delivery, successful or not.
- **Webhooks** (if configured in §1.3) — register `https://webhook.site/<your-id>`, subscribe to
  `SHIPMENT_DISPATCHED` and `DELIVERY_RESULT_RECORDED`, then show the delivery log: attempts,
  status codes, response time, and a retry button.

> *"HTTPS only, HMAC-signed with a per-endpoint secret, retried with backoff, and the endpoint is
> switched off automatically after ten consecutive failures — with a reason on the screen rather
> than a queue silently filling up. The envelope carries no business detail: it says what happened
> and to which shipment, and the receiver reads what we believe **now**. A retry three hours later
> that carried an embedded snapshot would be three hours out of date."*

**Tracking.** Post a position for the in-transit trip and refresh the trip's tracking card:

```bash
curl -sS -X POST http://localhost:8080/integration/v1/tracking/positions \
  -H "Authorization: Bearer $TRACKING_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"provider":"demo-feed",
       "positions":[{"shipmentNumber":"SH-00000001","occurredAt":"'"$(date -u +%FT%TZ)"'",
                     "latitude":-12.1000,"longitude":-77.0200,"speedKph":34}]}' | jq
```

> *"We ship the contract and no vendor adapter. Onboarding a telematics provider is implementing one
> interface, not redesigning our model. And nothing in the product reads a position except that map
> — no status is derived from one, no stop is closed by one. Losing the whole feed costs a map and
> no business fact, which is exactly why it is safe to run."*

---

## 4. Questions you will be asked, and the honest answer

| Question | Answer |
|---|---|
| *"Does it optimise the route?"* | **No.** Automatic planning is a capacity-and-eligibility heuristic that a person reviews. A real solver is on the roadmap and is a deliberate later decision, not an oversight. |
| *"Can the driver use it on a phone?"* | **Not yet.** Execution is recorded by a dispatcher on the web. The model is already per-stop and per-order, so a driver app writes to endpoints that exist. |
| *"Can our customer see where their delivery is?"* | **No customer portal.** Positions and delivery results exist and are on the API; the customer-facing surface is not built. |
| *"Live GPS?"* | The contract, the storage and the map are built. **No vendor adapter ships** — connecting a provider is an implementation of one interface. |
| *"Does it invoice?"* | **It records carrier cost**, estimated against the agreement and actual against the invoice, with the variance. There is no sell-side price and no invoice document. |
| *"Does it integrate with our ERP / SAP?"* | Through the published API — orders and locations in, shipments and events out, by polling or by webhook. **There is no packaged connector for any specific ERP**, and no shared tables with anything. |
| *"Where is the audit trail?"* | Every business act is recorded in an append-only table the application cannot update or delete. **There is no screen for it yet** — today it is a SQL query. |
| *"Can I see everything that failed to deliver this week, across trips?"* | Per trip, yes. **The cross-trip view is not built**; the database index for it exists. |
| *"How many customers are running this?"* | Answer it straight. This is a V1. |

Do not improvise past this table. Everything in it is in
[`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md) with the reason.

---

## 5. What will go wrong, and what to do

| Symptom | Cause | Fix |
|---|---|---|
| Sign-in succeeds, then every screen 403s | `tms.app_user.auth_user_id` is `NULL` | Run `demo_auth_users.sql` (§1.2). It is the single most common setup failure |
| Maps do not render | No `VITE_GOOGLE_MAPS_API_KEY` | Expected. The picker falls back to manual lat/long — say so rather than apologising |
| Orders do not appear as eligible | Service date does not match the planning run's date | The search-and-replace in `demo-data/README.md` |
| Import rejects the whole file | One bad row | That is the design. Show the preview, fix, re-upload |
| Evidence upload is refused | `TMS_EVIDENCE_STORAGE_MODE` is `DISABLED` | Expected default. Either turn it on beforehand or skip the attachment and record the result only |
| Webhook deliveries stay queued | `TMS_WEBHOOK_SECRET_KEY` unset | The feature is on exactly when that key is set |
| A vehicle is missing from the picker | `VEH-006` is `IN_MAINTENANCE`, or the vehicle already has a trip that day | Both are the intended behaviour — use them |

---

## 6. The 20-minute version

When you have one meeting slot and one screen:

1. **Orders arrive** (§3.4, the API batch and its replay) — 4 min.
2. **Automatic planning** (§3.5, auto-plan with the unplanned reasons) — 5 min.
3. **Confirm and dispatch** (§3.6 confirm, §3.7 dispatch with a back-dated time) — 3 min.
4. **A stop that went wrong** (§3.7, one delivered and one rejected at the same stop) — 4 min.
5. **Control tower** (§3.8) — 4 min.

Skip masters, the fleet, tendering, costing and administration entirely — and say that you are
skipping them, so nobody concludes they are missing.

---

## 7. Reset between runs

Rebuild rather than unpick. A demo run leaves confirmed trips, executed stops and delivery records,
and none of that is meant to be reversible.

```bash
supabase db reset          # drops and recreates the local database
./scripts/dev-backend.sh   # Flyway re-applies V1..V35 on startup
# then §1.2 and §1.4 again
```

Budget ten minutes. Do it the night before the next demo, not an hour before.

**Never point this at anything but the local stack.**

---

## 8. Before you promise anything

Twelve of the thirty-five migrations (V24–V35) have never been executed by any PostgreSQL server on
the build machine, because Docker is unavailable there. **Section 1.1 is the first time they run.**
Watch them apply, and if one fails, that is a finding worth more than the demo — record it and fix
it before the meeting rather than working around it.

Once they have applied once and the stack is up, everything in this script is a normal use of the
product.
