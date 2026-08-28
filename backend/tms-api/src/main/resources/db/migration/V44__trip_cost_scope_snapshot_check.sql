-- ===========================================================================
-- V44 - The one enum column in the schema with no CHECK behind it
-- ===========================================================================
--
-- Found by tms's new generic enum guard (Phase 2 JOB 18,
-- PersistedEnumConstraintTest), which compares every persisted
-- @Enumerated(STRING) column against the CHECK that governs it. Forty-five of
-- forty-six columns matched their Java enum exactly. This was the forty-sixth,
-- and it matched nothing because nothing constrained it.
--
-- ---------------------------------------------------------------------------
-- What this is, and why it was missed
-- ---------------------------------------------------------------------------
--
-- tms.trip_cost.rate_card_scope is a SNAPSHOT. V30 froze the winning card's
-- id, code, name and scope onto the cost row so that deactivating or re-pricing
-- a card can never restate what a shipment was estimated at last week - the same
-- argument tms.trip.snapshot_max_* makes for capacity.
--
-- The source column, tms.rate_card.scope, has always been constrained
-- (ck_rate_card_scope, widened by V39 to admit LANE). The copy never was. That
-- is an easy thing to miss precisely because it is a copy: the value is written
-- by Java from an enum, so in practice it has always been valid, and no test
-- could have noticed.
--
-- ---------------------------------------------------------------------------
-- Why it is worth a migration rather than an exemption
-- ---------------------------------------------------------------------------
--
--   * The failure it prevents is a READ failure, not a write one. A value this
--     column accepted that Java cannot map back - from a raw data fix, a restore,
--     or a future writer that is not the enum - surfaces when somebody opens a
--     cost breakdown, long after whatever wrote it.
--   * The guard now enforces this class of agreement across the schema. Leaving
--     one column permanently exempt would turn "45 of 46" into a standing apology
--     rather than a finding that was closed.
--   * It is additive and cannot fail on existing data: every value in this column
--     was written by Hibernate from RateCardScope, and NULL stays legal because
--     the snapshot is absent until a card wins.
--
-- Deliberately NOT reusing ck_rate_card_scope's name or definition by reference:
-- PostgreSQL has no way to share a CHECK between tables, and the two are allowed
-- to diverge in principle - a snapshot must keep accepting a scope the live
-- catalogue has since retired. What must not diverge is this column and the Java
-- enum that writes it, and that is exactly what the guard asserts.
ALTER TABLE tms.trip_cost ADD CONSTRAINT ck_trip_cost_rate_card_scope CHECK (
    rate_card_scope IS NULL OR rate_card_scope IN ('CARRIER', 'ORIGIN', 'LANE', 'ROUTE'));

COMMENT ON COLUMN tms.trip_cost.rate_card_scope IS
    'The scope of the rate card that produced this estimate, frozen at the moment of the estimate '
    '(V30). Constrained since V44 to the values com.ebim.tms.rates.domain.RateCardScope declares - '
    'NULL until a card has won. A snapshot may keep a scope the live catalogue has since retired; '
    'what it may not hold is a value Java cannot read back.';
