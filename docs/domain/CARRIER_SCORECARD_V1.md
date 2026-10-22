# Carrier scorecard V1 - "which carrier delivers for us"

Owner: `com.ebim.tms.planning.application.CarrierScorecardService` (queries:
`planning.infrastructure.CarrierScorecardQueries`). API: `CarrierScorecardController`.
Screen: `frontend/tms-web/src/pages/reporting/CarrierScorecardPage.tsx` (`/reporting/carriers`, menu
"Operación → Scorecard de transportistas"). **No migration.**

## 1. Endpoints

All `GET`, all `monitoring.transport:read` (the KPI report's permission: the same transport, by carrier),
range `from`/`to` resolved by `KpiRange` (default last 30 days, cap 92, refused not truncated).

| Path | Returns |
| --- | --- |
| `/api/v1/reporting/carrier-scorecard` | one row per carrier and one for the own fleet |
| `/api/v1/reporting/carrier-scorecard/trips?carrier=<id\|OWN_FLEET>` | the trips a row was counted from, newest first, max 500 with `total` + `truncated` |
| `/api/v1/reporting/carrier-scorecard/export` | the same figures as CSV (`no-store`) |

Capability `CARRIER_SCORECARD_VIEW` (derived from `monitoring.transport:read`, never stored) gates the
menu entry; the endpoint enforces the permission.

## 2. Attribution

A trip belongs to `trip.carrier_id` (owner of its vehicle), else `accepted_carrier_id`, else - with a
vehicle and no carrier - the **own fleet**. A trip with neither is nobody's and is counted apart
(`unattributedTrips`). **Offers** belong to the carrier they were made to (`trip_tender.carrier_id`):
a refusal is the refusing carrier's record. Range on `trip.planning_date`, as the KPI report.

## 3. Figures (null = no denominator, rendered "sin datos", never 0%)

| Figure | Definition |
| --- | --- |
| trips / executed | not cancelled / of those `IN_TRANSIT` or `COMPLETED` |
| on-time departure % | actual departure not after planned, over trips with both instants (no grace, as `KpiService`) |
| in-window % | stops arrived inside their window (window end on the trip date in the company zone), over stops with arrival and window |
| delivery success % | `DELIVERED` over every recorded delivery (partial, rejected, failed and not attempted count against) |
| incidents / 100 trips | exceptions on its trips that ran, per 100 such trips |
| tender acceptance % | accepted / (accepted + rejected + expired). **Expired counts against the carrier**, unlike the company-wide KPI (accepted / answered). A stored `SENT` past its deadline counts as expired |
| average response | sent → answered, minutes, over accepted and rejected |
| documents delivered % / cargo return % | `DELIVERED` over documents carried / signed copy returned (`returned_at`) over delivered |
| invoice discrepancy % and amount | from `SettlementKpiPort` (by `invoice_date`, per currency): invoices with a difference on their current match, and the over-billed amount |
| cost per trip / per kg | known cost (actual if recorded, else estimate) per currency over trips with a cost / over the kg assigned to the costed trips that carry weight. Via `TripCostAnalyticsPort.figuresForRange` |

Finer blocks are **absent (null), not zero**, without their permission: tenders
(`planning.tender:read`), documents (`planning.document_delivery:read`), invoices
(`settlement.invoice:read`), cost (`rates.trip_cost:read`). A carrier known only by its offers does not
appear for a caller who may not see offers. The CSV drops those columns from its header.

## 4. CSV shape

A row with an empty `currency` is a carrier's operation; a row with a currency is that carrier's money
in that currency (operational cells empty). Summing the trips column counts each carrier once, and two
currencies are never added.

## 5. Query budget

Six grouped statements (trips, stops, deliveries, exceptions, documents, tenders) plus one carrier-name
batch; with permission, one invoice aggregate and one cost read + one weight aggregate. None grows with
the number of carriers.

## 6. Not here

No ranking score (weights would be an invented rule), no SLA targets per carrier (the traffic light on
the screen is presentation only: 95/85 for punctuality, 90/70 for acceptance), no trend per week.
