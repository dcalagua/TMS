# Order totals V1

Where a transport order's weight, volume and pallet count come from, and which number wins when
two sources disagree. Introduced by migration `V17__orders_declared_totals.sql`; the order's
state machine around it is [`ORDER_LIFECYCLE_V1.md`](ORDER_LIFECYCLE_V1.md), and what planning
does with the resulting figures is [`CAPACITY_MODEL.md`](CAPACITY_MODEL.md).

The three dimensions and their units are the ones `CAPACITY_MODEL.md` fixes for the whole
product: **weight in kilograms**, **volume in cubic metres**, **pallets** (fractional allowed).

## The problem V10 left open

V10 stored `total_weight_kg` / `total_volume_m3` / `total_pallets` as a snapshot recomputed from
the lines. That is right for an order somebody typed line by line, and wrong for the case an
inbound integration produces constantly:

> one order, 1,200 kg, 2 pallets — and no line detail at all.

Under V10 such an order could only be stored with totals of zero. It then failed the
completeness check in `OrderService.markReadyForPlanning` and could never be planned, even
though the order was real and its capacity figures were real. The schema simply had nowhere to
put them.

## Two inputs, not two equal partners

| Input | Column | Meaning |
|---|---|---|
| The lines | `tms.transport_order_line` | what the order is made of, measure by measure |
| The declaration | `declared_weight_kg`, `declared_volume_m3`, `declared_pallets` | what the sender *asserts*, independent of any line detail |
| The result | `total_weight_kg`, `total_volume_m3`, `total_pallets` + `totals_source` | what planning reads |

`declared_*` is nullable and `total_*` is not, and that asymmetry is the point: `NULL` means
"the sender said nothing about pallets", `0` means "the sender said zero pallets". Nothing in
the chain may normalise the first into the second — the same discipline
`transport_order_line.pallet_quantity` already follows.

## The precedence rule

One implementation, `orders/domain/OrderTotals.java`, and every write path goes through it: the
manual API, the bulk import, and any future integration.

1. **Lines win wherever they speak.** An order with at least one line is `CALCULATED`, and each
   measure is the sum over its lines.
2. **A declared value fills a measure the lines are silent about.** A line set where no line
   carries a unit weight says nothing about weight — the sum is zero because nothing was known,
   not because the order weighs nothing. This is decided *per measure*, so an order can
   legitimately take its weight from the lines and its pallet count from the declaration.
3. **Where both speak, they must agree.** A declared figure that contradicts the lines by more
   than **1 %** is a data error — almost always a unit-of-measure or a per-unit/per-case mistake
   — and the write is rejected rather than one number being silently preferred. The tolerance
   absorbs the rounding of three- and four-decimal unit figures multiplied across many lines
   without absorbing a genuine discrepancy.
4. **With no lines at all** the order is `DECLARED`: the declared figures, or zero for anything
   the sender left out.

`totals_source` records which branch produced the stored totals. It is provenance, never an
input: only `TransportOrder.applyLines` writes it, from what `OrderTotals.resolve` returned.

```
resolve(lines, declared):
  lines empty  -> (declared or 0, declared or 0, declared or 0), DECLARED
  otherwise    -> per measure: sum(lines) if any line states it, else declared, else 0
                  ... CALCULATED
mismatches(lines, declared):
  every measure both sides state, differing by more than 1% of the calculated value
```

## The browser is never trusted

**No caller may ever supply `total_*`.** Not the browser, not an integration. `OrderRequest`
carries lines and, at most, `declaredWeightKg` / `declaredVolumeM3` / `declaredPallets`; the
numbers planning reads are always produced by `OrderTotals` on the server.

The Orders drawer does reproduce the rule client-side (`previewTotals` in
`OrderFormDrawer.tsx`), for one reason only: so an operator can see, while typing, which of the
two numbers in front of them is the one that will be planned. That preview is discarded on
submit and the server's answer replaces it.

A contradiction (rule 3) surfaces differently per caller, from the same `OrderTotals.mismatches`
result:

- manual API — a `400` naming the measure, the declared value and the calculated one;
- bulk import — a row-level issue in the preview report, so the whole file is refused before
  anything is written (`OrderImportReport`).

`OrderTotals` itself never throws on a contradiction. `mismatches` is a question a caller asks,
which is what lets one turn it into a status code and the other into a table row without either
driving control flow through an exception.

## What the database enforces, and what it does not

Enforced, because it is cheap and local to one row:

- `ck_transport_order_declared_nonnegative` — no negative declared figure;
- `ck_transport_order_totals_source` — the value domain `('CALCULATED', 'DECLARED')`.

**Not** enforced: that `totals_source = 'CALCULATED'` implies the order has lines. That is a
statement about two tables, so a `CHECK` cannot see it, and the only remaining option is a
constraint trigger deferred to `COMMIT`. It was written and then removed — it would run one
`count(*)` per order row at every commit, including the 1,000-order bulk import, to verify a
column no query filters on; and being a constraint trigger it fires for the schema owner too, so
every raw-SQL fixture and future data migration would have to know the rule or fail at `COMMIT`.
The reasoning is spelled out in `V17__orders_declared_totals.sql` itself.

The agreement between the lines and the source is therefore an application invariant with
exactly one writer, `TransportOrder.applyLines` — and `OrderTotalsTest` is what proves that
writer.

## Readiness for planning

`markReadyForPlanning` requires an order whose effective totals describe *something*: at least
one of weight, volume or pallets must be non-zero. With V17 a header-only order satisfies that
through its declaration, which is precisely the case V10 could not express. The rest of the
lifecycle is unchanged — see [`ORDER_LIFECYCLE_V1.md`](ORDER_LIFECYCLE_V1.md).

## Where this is proven

| Concern | Test |
|---|---|
| The rule itself, every branch | `OrderTotalsTest` |
| Manual API: declared accepted, contradiction rejected | `OrderApiIntegrationTest` |
| Import: totals per row, declared vs calculated | `OrderImportValidatorTest`, `OrderImportApiIntegrationTest` |
| Column domain and non-negativity | `OrderConstraintIntegrationTest` |
| Client-side preview mirrors the rule | `OrderFormDrawer.test.tsx` |
