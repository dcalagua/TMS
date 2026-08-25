# Proof of Delivery & Delivery Result V1

What was actually handed over at a stop, and the evidence that says so.

Migration: `V28__delivery_result_and_pod_evidence.sql`.
Code: `planning.domain.DeliveryResult`, `planning.domain.OrderDelivery`,
`planning.domain.DeliveryEvidence`, `planning.domain.EvidenceType`,
`planning.application.TripDeliveryService`, `planning.application.DeliveryEvidenceService`,
`planning.api.TripDeliveryController`, `shared.storage.*`.
UI: `pages/trips/TripWorkspacePage.tsx`, `pages/trips/DeliveryDrawer.tsx`,
`pages/trips/DeliveryEvidenceDrawer.tsx`.

Companion documents: [`TRIP_EXECUTION_V1.md`](TRIP_EXECUTION_V1.md) for the stop lifecycle this
sits beside, [`ORDER_LIFECYCLE_V1.md`](ORDER_LIFECYCLE_V1.md) for the order status this does *not*
move, [`../integrations/OUTBOUND_SHIPMENT_V1.md`](../integrations/OUTBOUND_SHIPMENT_V1.md) for what
a partner sees.

---

## 1. The problem this solves

Migration V27 gave every stop an execution status: `PENDING → ARRIVED → IN_SERVICE → COMPLETED`,
with `SKIPPED` and `FAILED` for the two ways a stop does not happen. That answered the
dispatcher's questions — *did we get there?*, *which stops are still outstanding?* — and it left
the customer-service desk with nothing:

- *"the customer says one of the three orders on that stop was refused — which one, and why?"*
- *"they are disputing the delivery; who signed for it, and what do we have on file?"*

Neither is answerable from a stop. A stop serves a **destination**, and a destination can take
three orders and refuse the fourth. The commercial outcome therefore belongs one level down, at the
order — and *beside* the stop's operational status, never inside it.

## 2. The two facts, and why they stay separate

| | `TripStop.executionStatus` (V27) | `OrderDelivery.result` (V28) |
|---|---|---|
| Subject | the **vehicle** at a destination | the **goods** of one order |
| Asked by | the dispatcher | customer service, the ERP, the claim |
| Grain | one stop | one order at one stop |
| Values | `PENDING`, `ARRIVED`, `IN_SERVICE`, `COMPLETED`, `SKIPPED`, `FAILED` | `DELIVERED`, `PARTIAL`, `REJECTED`, `FAILED`, `NOT_ATTEMPTED` |

A stop can be `COMPLETED` with one of its orders `REJECTED`, and **both statements are true**. That
is the whole reason for a second table: one status could not represent it, and the Spanish and
English labels keep the two apart on screen too — a stop is *Atendida* / *Served*, an order is
*Entregado* / *Delivered*.

## 3. The five results

| Result | Meaning | `deliveredAt` | Receiver | Notes |
|---|---|---|---|---|
| `DELIVERED` | Handed over in full. | required | allowed | optional |
| `PARTIAL` | Some taken, some not. | required | allowed | **required** |
| `REJECTED` | The customer refused it, to the driver's face. | optional | allowed | **required** |
| `FAILED` | Attempted and not delivered: nobody there, dock closed, goods damaged. | optional | forbidden | **required** |
| `NOT_ATTEMPTED` | Never taken off the vehicle. | forbidden | forbidden | optional |

Three rules produce that table, and each is enforced three times — in `TripDeliveryService` with a
sentence a dispatcher can read, in `OrderDelivery` as a last line of defense, and by a `CHECK`
constraint under both:

1. **A result that claims goods changed hands says when.** `NOT_ATTEMPTED` says the opposite and
   carries no time at all.
2. **A receiver is somebody who was present.** `FAILED` and `NOT_ATTEMPTED` mean nobody took the
   goods, took part of them, or refused them in person — the only name that could go there is the
   driver's own, which is not what the column means.
3. **Anything short of a clean delivery is explained.** `DELIVERED` needs no note; `NOT_ATTEMPTED`
   is already explained by the stop that was skipped or failed, which V27 requires a typed
   `tms.trip_exception` for. The other three are the ones a customer rings about, and "PARTIAL" on
   its own is not an answer to that call.

### Why there is no `PENDING` result

An order with no `tms.order_delivery` row has not been recorded yet, which is the same statement. A
value for it would give one fact two representations — the trap V25 avoided by not adding a
`DISPATCHED` beside `IN_TRANSIT`. The UI says *Sin registrar* / *Not recorded* and the outbound
payload sends `null`; neither is ever read as "not delivered".

### Why there are no quantities

`PARTIAL` records that something was short and a sentence saying what. Quantities need a unit model
that agrees with `transport_order_line`'s, a rule for what a partial delivery does to the order's
declared totals, and a decision about who is right when the two disagree. Those are three product
decisions nobody has made; the mandatory note is what keeps the shortfall on file in the meantime,
in the only form that cannot be wrong.

## 4. The window

A delivery can be recorded from the moment the vehicle leaves until the shipment is closed out —
`IN_TRANSIT` **and** `COMPLETED` — and at no other time.

- Before departure there is nothing to record. A delivery against a shipment still on the dock is
  not an early record, it is a wrong one.
- After completion is deliberately allowed, unlike a stop transition. **That is when the paperwork
  comes back.** A dispatcher closing the day at 18:00 and keying twelve signed notes at 18:40 is
  the ordinary case, and forcing them to leave the shipment open would corrupt the completion time
  to protect the delivery record.
- A cancelled shipment delivered nothing, and is refused outright.

The stop must also have been reached: any outcome except `PENDING`. `SKIPPED` and `FAILED` are
explicitly fine — they are where `NOT_ATTEMPTED` rows come from.

And the order must be going *to that stop*: `TripDeliveryService` checks the active assignment and
compares the order's destination with the stop's. Without that a dispatcher could record order 4711
as delivered at a stop it was never going to, and the record would contradict the plan it belongs
to.

## 5. Corrections

One row per `(tripStop, order)`, and re-recording overwrites it. Nothing is lost: every recording
also appends a `DELIVERY_RECORDED` entry to `tms.transport_event`, which is append-only, so *what
was claimed, by whom and when* lives there. Two rows here would instead make "was this order
delivered" a question with two answers and an ordering rule to pick between them.

The `PUT` carries the **whole state** of the delivery, not a patch: an omitted `receiverName` means
there is no receiver. Anything else would make it impossible to remove a name typed by mistake —
exactly the field somebody would want removed.

## 6. Evidence

`tms.delivery_evidence` holds **metadata only**: what the artefact is, how big it is, what it
hashes to, and an opaque key. Three things it deliberately does not hold:

- **No bytes.** No `bytea`, no base64 column. A photo of a pallet is megabytes; putting those in
  the row puts them in every backup, every replica, every dump a developer takes, and in the WAL of
  a database sized for transport rows.
- **No URL.** Not a signed one, and emphatically not a public one: a permanent public link to a
  customer's signed delivery note is a data leak with a stable address.
- **No delete.** V1 never removes evidence, and the database withholds the grant. A wrong photo is
  answered by uploading the right one beside it.

### The storage port

`shared.storage.EvidenceStoragePort` is the abstraction TMS did not have. Two implementations ship:

| `tms.storage.evidence.mode` | Behaviour |
|---|---|
| `DISABLED` (default) | Uploads and downloads answer **503** `storage-unavailable`. Delivery results are recorded normally. |
| `LOCAL` | Writes to a private directory (`tms.storage.evidence.root`) — a single node or a container with a mounted volume. |

The default is the decision: a deployment that has not said where a signed delivery note goes must
not have somewhere guessed for it. Supabase Storage is the next implementation and needs no change
to any caller, because a storage key is opaque.

Three rules every implementation keeps:

1. **The key is ours.** `store` generates it — `<companyId>/<yyyy>/<MM>/<uuid>.<ext>`, with the
   extension taken from the validated media type. No character of a caller-supplied string reaches
   a path, which is what makes traversal not a class of bug here rather than a filter that has to
   be right. `open` re-checks the key against that exact shape *and* that the resolved path is
   still under the root.
2. **The store is private.** There is no method returning a URL. Bytes come back through
   `GET .../evidence/{id}/content`, which is authenticated, company-scoped, permission-checked, and
   answers `Content-Disposition: attachment` with `Cache-Control: no-store`.
3. **Company first.** Every operation takes the company, and a key that does not name the caller's
   company is refused by the store even if it somehow reached the database layer.

The size limit is enforced **while writing** (`EvidenceRejectedException`), not from a declared
length: a chunked upload need not declare one, and the only number that cannot be lied about is the
count of bytes that arrived. A refused upload leaves nothing behind — the write is staged and moved
into place only on success.

Bytes are written **before** the metadata row. The failure modes are not symmetric: a row pointing
at an object that was never stored is evidence that cannot be produced, while an object with no row
is an orphan a sweep can find.

## 7. What a partner sees

One new outbox event, `DELIVERY_RESULT_RECORDED` — the first that is not a trip-state change, and
the reason `tms.shipment_outbox_event` was built with an event *type* rather than a status column.
It carries the shipment number and nothing else, exactly as V20 designed it; the partner re-reads
`GET /integration/v1/shipments/{shipmentNumber}`, whose order list now reports
`deliveryResult`, `deliveredAt`, `deliveryReceiverName`, `deliveryNotes` and `evidenceCount`.

Deliberately **one** event type rather than one per result: a partner subscribes to "a delivery
outcome was recorded" and reads what it was, which keeps today's five results out of the wire
contract. The receiver's identity *document* is not published — no partner has asked for it, and it
is the more sensitive half of the pair. `evidenceCount` is a count and not links, because there is
no address these bytes are reachable at.

## 8. Authorization

No new permission, following V27's argument for stop execution:

| Action | Requires |
|---|---|
| Record or correct a delivery | `planning.trip:execute` |
| Attach evidence | `planning.trip:execute` |
| Read deliveries (part of the trip detail) | `planning.trip:read` + `orders.order:read` |
| Download an artefact | `planning.trip:read` + `orders.order:read` |

Recording what was handed over at a stop is "operate a trip through its day" at its finest grain.
Reading one costs exactly what reading the trip's order list already costs, because a delivery
names an order.

## 9. What is recorded, in three places

One user action produces three records, written in the same transaction so they cannot drift:

| Record | Audience | What it holds |
|---|---|---|
| `tms.order_delivery` | the app | the current statement: result, time, receiver, notes, actor |
| `tms.transport_event` (`DELIVERY_RECORDED`) | the dispatcher | the timeline entry, append-only, with the stop and the order number in its metadata |
| `tms.audit_event` (`DELIVERY_RESULT_RECORDED`) | compliance | who recorded what, under which correlation id |
| `tms.shipment_outbox_event` (`DELIVERY_RESULT_RECORDED`) | the partner | the shipment number, to be re-read |

V27 argued against an audit row per *stop* transition and that argument is unchanged — the
operational log already holds those. A delivery result is the exception it named: it is what a
dispute, an insurance claim or a credit note is argued from, which is a compliance fact as well as
an operational one.

Attaching evidence is audited as a plain `UPDATE` on the shipment and writes **no** timeline entry:
the fact worth recording twice is the result, and an attachment is corroboration of a fact already
logged, on a row that is itself append-only and stamped with who uploaded it.

## 10. The screen

Inside `/trips/{id}`, under each stop, once the trip has run: every order that stop serves, with
its outcome badge (or *Not recorded*), the receiver, the note, and its artefacts as download
buttons. *Record delivery* / *Correct* opens one drawer that changes shape with the result — the
fields a result cannot carry are not shown, so nobody fills in a box the server would refuse.
*Attach* opens the evidence drawer.

Nothing is drawn before departure: a column of *Not recorded* against a shipment still on the dock
is noise that reads like a problem.

## 11. Deliberately not in V1

- **No delivered/refused quantities and no line-level result.** See §3.
- **No change to `transport_order.status`.** A `DELIVERED` order here is still `PLANNED` there. The
  orders module owns that lifecycle (V25 and V27 both said so), and the day it grows a delivered
  state, this table is what will feed it. **This remains the known gap.**
- **No evidence deletion and no retention job.** Both become real the day a customer asks for
  erasure, and both need a data-protection decision that does not belong in a migration. The
  withheld grant is the honest interim answer: nothing can remove evidence, so nothing can remove
  it by accident.
- **No Supabase Storage adapter.** The port is the deliverable; the adapter is a day's work behind
  it whenever the platform decision is made.
- **No signature capture canvas.** V1 uploads an image. Drawing one in the browser is a UI feature,
  not a model change, and it would still produce exactly this row.
- **No digital signature in the legal sense.** `SIGNATURE` is a captured image. TMS makes no
  cryptographic claim about who drew it, and no label in either language suggests otherwise.
- **No cross-trip deliveries screen.** "Everything that did not arrive today" is a real supervisor
  question and the index for it exists (`ix_order_delivery_company_shortfall`); the screen is the
  next increment.
