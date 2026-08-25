# Demo data — LOCAL / DEMO ONLY

> **Never load these files into a shared or production database.** They describe a fictional
> Lima operation. Every company name, tax id, phone number and email address in them is invented,
> and the email domains use the reserved `.example` TLD so nothing can ever be delivered.

## Why these are import files and not a SQL seed

There is already a SQL seed for **identity** — `supabase/seeds/local_dev_seed.sql` creates the
organization, two companies, three users and their memberships, and
`supabase/seeds/demo_auth_users.sql` links them to Supabase Auth accounts. That one is SQL because
identity has no API to go through, and it is covered by `LocalSeedIntegrationTest`.

The **operational** data below deliberately is not SQL:

1. **It goes through the product's own rules.** Codes are normalised, capacity is resolved,
   order totals are computed by `OrderTotals`, audit rows are written, and every value is
   validated by the same code a customer's data will hit. A SQL insert bypasses all of that and
   can produce a dataset the application then refuses to plan — the worst possible thing to
   discover on a demo morning.
2. **Loading it is itself a feature worth showing.** Scenario 4 of the demo *is* the import.
3. **It cannot be executed against a database here.** Docker is unavailable on the build machine
   (see `../KNOWN_LIMITATIONS.md` §1), so any SQL written in this job would ship unverified. An
   import file that is wrong tells you so in a dry-run preview, in the browser, before writing
   anything.

## Load order

The files are numbered because each depends on the codes the previous one created.

| # | File | Screen | Endpoint behind it |
|---|---|---|---|
| 1 | `01-locations.csv` | Maestros → Ubicaciones → *Importar* | `POST /api/v1/masterdata/locations/import` |
| 2 | `02-carriers.csv` | Flota → Transportistas → *Importar* | `POST /api/v1/fleet/carriers/import` |
| 3 | `03-vehicle-types.csv` | Flota → Tipos de vehículo → *Importar* | `POST /api/v1/fleet/vehicle-types/import` |
| 4 | `04-vehicles.csv` | Flota → Vehículos → *Importar* | `POST /api/v1/fleet/vehicles/import` |
| 5 | `06-orders-inbound-batch.json` | — (machine to machine) | `POST /integration/v1/orders/batch` |
| 6 | `05-orders.csv` | Pedidos → *Importar* | `POST /api/v1/orders/import` |

Drivers, zones, frequencies, routes and rate cards are **not** in this set. They are created by
hand during the demo, because creating one is a thirty-second screen that is worth showing and
because none of the five has an import endpoint.

## Before you load anything: fix the service date

Every order in `05-orders.csv` and `06-orders-inbound-batch.json` carries `2026-08-25`. Replace it
with the day you intend to plan — one search-and-replace per file:

```bash
sed -i 's/2026-08-25/2026-09-14/g' docs/product/demo-data/05-orders.csv
sed -i 's/2026-08-25/2026-09-14/g' docs/product/demo-data/06-orders-inbound-batch.json
```

Plan for that same date in the planning run, or the orders will not appear as eligible.

## What is in the set

**9 locations** — two distribution centres (one of them both `ORIGIN` and `DESTINATION`, which is
the point of the canonical location model), six stores and one industrial customer. Coordinates are
real Lima districts, so the maps draw something recognisable. `zoneCode` is left blank on every row
on purpose: zones are created live in the demo, and a CSV referring to a zone that does not exist
yet would fail the import.

**3 carriers**, **4 vehicle types** (including one refrigerated and one articulated), **6 vehicles**
— five belonging to carriers and one owned by the company, one with a pallet override and one
`IN_MAINTENANCE` so the planner can be shown that it is not offered.

**8 orders**, five delivered over the integration API already marked ready for planning, three
imported from the spreadsheet and left `NOT_READY` so the completeness check can be shown. Their
combined weight and pallet count exceed a single 8-tonne truck, so automatic planning has to build
more than one trip — which is what makes the auto-plan step worth watching.

`05-orders-with-one-bad-row.csv` is the same file as `05-orders.csv` with two deliberate errors: an
unknown destination code (`ST-9999`) and a priority in Spanish (`URGENTE`, where the value is
`URGENT`). Upload it first to show that the dry-run preview names both rows and that **nothing is
written**, then upload the clean file.

## Re-running

The four master-data imports are idempotent on `code`: re-uploading the same file skips rows that
already exist rather than duplicating them. The order paths are idempotent on
`(externalSource, externalReference)`.

That makes them safe to re-run, with one exception: an order that has already been **planned** or
cancelled is not silently rewritten — the API answers `409` naming its status. Reset by rebuilding
the database (`../DEMO_SCRIPT.md` §7), not by re-importing over a planned order.
