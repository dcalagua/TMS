-- TMS by EBIM - V33 the operating day a cost belongs to (job 11 overnight-v4).
--
-- One column, and it exists for exactly one reason: so that "what did the shipments of March cost
-- us" can be answered without a join between two modules' tables.
--
-- ---------------------------------------------------------------------------
-- 1. Why the column and not the join
-- ---------------------------------------------------------------------------
-- tms.trip_cost has carried only trip_id since V30, which is enough for every question the costing
-- screens ask - they all start from one shipment. The KPI report (job 11) asks the opposite kind of
-- question: it starts from a date range and wants a total. Answering that from trip_id alone means
--
--     SELECT sum(...) FROM tms.trip_cost c JOIN tms.trip t ON t.id = c.trip_id
--      WHERE t.planning_date BETWEEN ...
--
-- which is com.ebim.tms.rates reading com.ebim.tms.planning's table. ModuleBoundaryTest forbids the
-- Java equivalent of that sentence, and a SQL statement that does what the ArchUnit rule refuses is
-- the boundary being broken quietly rather than not being broken.
--
-- The alternative to the column is the port passing a set of trip ids from planning to rates. The
-- report is capped at a quarter (KpiRange.MAX_DAYS) and the scale target is 100-300 vehicles, so
-- that set is tens of thousands of UUIDs on the wire and in an IN list, to produce two numbers.
--
-- So the day is denormalized onto the cost, exactly as V16 rule 7 denormalized it onto tms.trip
-- itself and for the same class of reason: a column that lets the database answer a question
-- without a join it should not be making.
--
-- ---------------------------------------------------------------------------
-- 2. Why it can be NOT NULL and never drift
-- ---------------------------------------------------------------------------
-- tms.trip.planning_date is `updatable = false` and its own source (tms.planning_run.planning_date)
-- has no mutator, so the value copied here can never go stale. Both places that construct a
-- TripCost (rates.application.TripCostService.applyEstimate and .recordActual) already hold a
-- CostableTrip, whose planningDate is the same field - so this is a copy of something already in
-- the caller's hand, not a second lookup and not a second opinion.
--
-- The backfill below is total: trip_id is NOT NULL and carries a foreign key to tms.trip, so every
-- existing row has exactly one trip to take the date from.

ALTER TABLE tms.trip_cost ADD COLUMN planning_date date;

UPDATE tms.trip_cost c
   SET planning_date = t.planning_date
  FROM tms.trip t
 WHERE t.id = c.trip_id
   AND c.planning_date IS NULL;

ALTER TABLE tms.trip_cost ALTER COLUMN planning_date SET NOT NULL;

COMMENT ON COLUMN tms.trip_cost.planning_date IS
    'The operating day of the trip this cost belongs to, copied from tms.trip.planning_date when '
    'the row is created and never updated afterwards (the source is itself immutable). Exists so a '
    'cost total over a date range is one indexed scan of this table instead of a join into '
    'tms.trip, which would be com.ebim.tms.rates reading com.ebim.tms.planning - see V33 section 1.';

-- The KPI report's only access path into this table: one company, one range, summed by currency.
-- Ordered (company_id, planning_date) rather than the other way round for the reason every other
-- tenant-scoped index here is: the company predicate is present in every statement, and it is what
-- keeps one tenant's scan off another tenant's pages.
CREATE INDEX ix_trip_cost_company_planning_date
    ON tms.trip_cost (company_id, planning_date);

-- ---------------------------------------------------------------------------
-- 3. Grants and RLS
-- ---------------------------------------------------------------------------
-- Neither changes. A new column on an existing table inherits the table's policy and the grants
-- V30 already made to tms_app; there is nothing to re-grant and nothing new to expose.
--
-- ---------------------------------------------------------------------------
-- 4. Deliberately NOT here
-- ---------------------------------------------------------------------------
--   * No carrier_id beside it. It would answer "what did we pay this carrier" without a join for
--     the same price as this column does, and it is not added because nothing asks yet: the V1 KPI
--     report has no per-carrier cut (docs/domain/KPIS_REPORTING_V1.md, "Deliberately not in V1").
--     A column added ahead of the screen that reads it is a column nobody maintains.
--   * No materialized view and no rollup table. Every figure the report shows is an aggregate over
--     at most a quarter of one company's shipments, which at the stated scale is thousands of rows
--     behind two indexes. A pre-aggregated copy would buy nothing measurable and would introduce
--     the one failure mode a KPI screen cannot afford - a number that disagrees with the rows it
--     claims to summarize.
