# TMS OVERNIGHT JOB 07 RESULT

RESULT=PASS
STOP_CHAIN=false

STARTED_AT=2026-08-28 03:22 America/Lima
COMPLETED_AT=2026-08-28 03:45 America/Lima

## OBJECTIVE

Rank carriers by rate, lane applicability and eligibility, and offer a shipment down that list:
A rejected → B expired → C accepted. One active tender per shipment, response deadlines, max
attempts, idempotency, concurrency safety, immutable history, manual override, audit and metrics.

## BASELINE

Clean tree at `3ec0d67`... corrected: `38172c3` + log commit. Backend 1517, frontend 55, E2E 33.
Flyway head V39, so **V40 was the next free number** - checked on the filesystem. JOB 06's rating is
complete enough to price a carrier, which is what JOB 07's ranking depends on.

## IMPLEMENTED

- **`CarrierQuotationPort` / `CarrierQuotationService`** - what each carrier would charge for one
  shipment, through *the same selector, calculator and inputs* `TripCostService` uses. Two code
  paths computing "the price" is exactly how the offer and the invoice come to differ.
- **`CarrierRanking`** - pure, reproducible: a price beats no price, cheapest first among comparable
  currencies, then by code. **"No tariff entered" is not "free"** - such a carrier ranks last, not
  first. Currencies are not converted; the *majority* currency is the reference so one mis-keyed
  card cannot invert the list.
- **`TenderWaterfall` / `TenderWaterfallCandidate`** - the ranking is **stored**, with the price each
  carrier was ranked on and the card that produced it. Re-ranking at each step would let a card
  edited on Tuesday change the order of a list approved on Monday.
- **`TenderWaterfallService`** - start, advance, stop, and `tenderAnswered`, which runs **in the same
  transaction as the rejection** so the tender's status and the candidate's cannot disagree.
- **`TripTenderService.createFor`** - offers to a named carrier, which subcontracting requires.
- Four endpoints on the existing tender controller; a `TenderWaterfallCard` on the trip workspace.

## MIGRATIONS

**V40__tender_waterfall.sql**. No applied migration touched. Two tables, RLS + tenant policy on
both, **no DELETE grant** (who was offered a shipment and in what order is what a carrier disputing
a rate asks for - it is cancelled, not removed), `uq_tender_waterfall_active`, `uq_twc_rank`,
`uq_twc_carrier`, and `WATERFALL_STARTED` / `WATERFALL_ENDED` added to the audit vocabulary.

## BACKEND / FRONTEND / DATABASE

See FILES_CHANGED. Flyway contiguous V1-V40.

## SECURITY

No new authority: the four endpoints reuse `planning.tender:read` / `:manage`. Every waterfall write
takes the **trip's** row lock - the same serialisation point every other trip write uses - so a
carrier accepting and a dispatcher advancing cannot interleave. Quotes are produced against the
caller's company only; candidates carry `fk_twc_carrier_company`, so a waterfall cannot name another
tenant's carrier as a database fact.

## TENANT_TESTS

No new tenant surface beyond the two tables, both of which `SchemaExposureIntegrationTest` now
enumerates and demands the tenant policy of - it failed until they were correctly policed, then
passed. Carrier and trip references are company-pinned by composite FK.

## AUDIT

`WATERFALL_STARTED` (who, over how many candidates, with what ceiling) and `WATERFALL_ENDED`
(outcome, after how many offers). Two actions rather than six: every step already produces
`TENDER_SENT` / `TENDER_REJECTED` / `TENDER_EXPIRED`, and a parallel row per step would duplicate
the trail rather than extend it.

## OBSERVABILITY

`tms.tender.waterfall` (started / stopped / accepted / exhausted / cancelled) and
`tms.tender.waterfall.advances` (offered / accepted / rejected / expired / withdrawn).

## TESTS_FOCUSED

`CarrierRankingTest` (7) and `TenderWaterfallTest` (12), both pure. The brief's sequence -
A rejected, B expired, C accepted - is asserted directly, along with the attempt ceiling stopping
with carriers still on the list, `finish` being idempotent (the accept-vs-stop race), unreached
candidates becoming `SKIPPED`, and a candidate refusing to be walked backwards into `PENDING`
(which would let one refusal consume two attempts, or none).

## TESTS_CLEAN

`./mvnw -B clean test` - **1536 tests, 0 failures, 0 errors**, BUILD SUCCESS. (+19.)

## FRONTEND_TESTS

typecheck clean; lint 0 errors (17 pre-existing warnings); `npm test` **55 passed**; build succeeds.

## E2E

33 passed, 7 skipped.

## RETRIES_ATTEMPTED=4
## RETRIES_RECOVERED=4

1. **TYPE C.** `CompanyScope.forSystemTask` did not exist - I had written the scheduler assuming a
   system-actor concept the product does not have. See KNOWN_LIMITATIONS: this became a design
   decision rather than a workaround.
2. **TYPE C.** `AuditAction.WATERFALL_*` and `TenderWaterfallView` referenced before they existed.
3. **TYPE C.** A python edit inserted the `waterfall()` accessor between `publish`'s javadoc and its
   signature, and put the field on a class member that does not exist here. Caught by the clean
   compile; restructured.
4. **TYPE C.** `SchemaExposureIntegrationTest`'s table registry did not know the two new tables -
   the guard working exactly as designed.

## BLOCKED_GATES

None. Docker up throughout. No remote environment contacted.

## KNOWN_LIMITATIONS

**Two, and both are decisions rather than omissions.**

1. **No background scheduler.** Creating a tender goes through `requireAppUserId`, which refuses a
   machine *by design* - "this operation is restricted to an interactive user". That rule predates
   this feature and is right: an offer to a carrier is a commercial commitment and the trail must
   name who made it. A sweep offering on a company's behalf needs a **system-actor concept this
   product does not have**, and inventing one at speed would put an unattributable commitment into
   the history - exactly the ambiguous semantics this run was told not to introduce. So the waterfall
   reports `currentOfferLapsed` (computed on read, never stored) and a dispatcher advances with one
   click. **The follow-up, if unattended tendering is wanted:** a first-class system actor with its
   own audit identity, a `@Scheduled` sweep using `FOR UPDATE ... SKIP LOCKED`, and a company opt-in.
2. **Accepting does not reassign the vehicle.** A shipment's carrier is the owner of its assigned
   vehicle. A waterfall offers to carriers that do *not* own it, so an acceptance is recorded and
   putting one of that carrier's vehicles on the trip stays an explicit planner action. Doing it
   silently would leave a trip whose carrier and whose vehicle's owner disagreed.

Also: **no carrier capacity model** (ranking uses price, applicability and active status - the facts
the product holds); **no broadcast tendering**; **no automatic acceptance, ever**.

**Delivery quantity** (JOB 03) remains unmodelled. Nothing here needed or inferred it.

## FILES_CHANGED

    backend/.../db/migration/V40__tender_waterfall.sql                    new
    backend/.../shared/reference/{CarrierQuote,CarrierQuotationPort}.java new
    backend/.../shared/reference/CarrierLookupPort.java                   findAllActiveInCompany
    backend/.../rates/application/CarrierQuotationService.java            new
    backend/.../planning/domain/{TenderWaterfall,TenderWaterfallCandidate}.java  new
    backend/.../planning/domain/{WaterfallStatus,WaterfallCandidateStatus}.java  new
    backend/.../planning/application/{CarrierRanking,TenderWaterfallService,TenderWaterfallView}.java  new
    backend/.../planning/application/TripTenderService.java               createFor + waterfall wiring
    backend/.../planning/infrastructure/TenderWaterfallRepository.java    new
    backend/.../planning/api/TripTenderController.java                    4 endpoints
    backend/.../fleet/infrastructure/{CarrierRepository,CarrierLookupAdapter}.java
    backend/.../shared/audit/AuditAction.java                             2 actions
    frontend/.../pages/trips/TenderWaterfallCard.tsx                      new
    frontend/.../shared/api/tendersApi.ts                                 waterfall types + client
    docs/domain/CARRIER_SELECTION_AND_WATERFALL_V1.md                     new

## LOCAL_COMMIT

One local commit. No push.

## NEXT_JOB

**JOB 08 - Dock / Appointment Scheduling.** Self-contained (location resources, calendars, blocked
slots, appointments on trip stops) and it does not depend on anything left open here.
Next migration: **V41**.
