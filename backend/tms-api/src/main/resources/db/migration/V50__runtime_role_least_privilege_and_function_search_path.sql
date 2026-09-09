-- ---------------------------------------------------------------------------
-- V50 - the runtime role loses the privileges the schema already said it did not have
-- ---------------------------------------------------------------------------
--
-- Nothing here is a new decision. Every REVOKE below enforces a narrowing that an earlier
-- migration wrote down in prose, that docs/database/DATA_MODEL.md repeats, and that
-- SchemaExposureIntegrationTest's own comments describe - but that no statement ever
-- performed. This file performs them.
--
-- Why the narrowings silently did nothing
-- ---------------------------------------
-- V13 gave tms_app its privileges twice: once with GRANT ... ON ALL TABLES for what existed
-- then, and once with
--
--     ALTER DEFAULT PRIVILEGES IN SCHEMA tms
--         GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tms_app;
--
-- which fires at CREATE TABLE time for every table Flyway creates afterwards. So a later
-- migration that writes
--
--     GRANT SELECT, INSERT ON tms.settlement_approval TO tms_app;
--
-- does not narrow anything. GRANT is additive: the four verbs were already attached to the
-- table the instant it was created, and a shorter GRANT adds nothing and removes nothing. The
-- privilege that the migration's comment says was withheld is present.
--
-- V22 spotted this and wrote the REVOKE that actually withholds it ("the REVOKE below is what
-- actually withholds the two that matter"), and V23, V27, V28 and V29 followed. Eleven other
-- tables declared the same intent in a comment and stopped at the GRANT. The result is a
-- documented posture that is not the deployed one, which is worse than no claim at all: the
-- next reader trusts the comment.
--
-- Why this is a new file and not an edit
-- --------------------------------------
-- Applied migrations are immutable (ADR-002, docs/development/DATABASE_SAFETY.md). Editing
-- V20/V32/V34/V35/V40/V41/V45/V46 would invalidate every deployed checksum and Flyway would
-- refuse to start. The correction is always forward.
--
-- Idempotence
-- -----------
-- REVOKE of a privilege the role does not hold is a no-op, not an error, so this file is safe
-- to apply to a database that already lacks these privileges (one rebuilt under an
-- administrative role for which V13's ALTER DEFAULT PRIVILEGES never fired). CREATE OR REPLACE
-- FUNCTION keeps the function's OID and its ACL, so the triggers bound to it and V4's
-- REVOKE ... FROM PUBLIC both survive.
--
-- Verified before writing: no Java path in backend/tms-api issues any of the revoked verbs
-- against these tables. Section 4 below records the audit per table so the claim is checkable
-- rather than asserted.

-- ---------------------------------------------------------------------------
-- 1. Append-only and narrowed-verb business tables
-- ---------------------------------------------------------------------------

-- V20: "an outbox row is a fact about what happened and is never edited or removed by the
-- application". Consumption is cursor-based (ShipmentOutboxEventRepository.findPublishable),
-- there is no publish flag to stamp, and the entity's every column is updatable = false.
REVOKE UPDATE, DELETE ON tms.shipment_outbox_event FROM tms_app;

-- V32: "an alert history that the application can silently erase stops being evidence of what
-- the operation was told". UPDATE stays: an alert is acknowledged and resolved in place.
REVOKE DELETE ON tms.notification FROM tms_app;

-- V34: "the row is 1:1 with a company that is itself never deleted, so a DELETE grant could
-- only ever be used to lose a tenant's numbering configuration". The write path is an
-- INSERT ... ON CONFLICT DO UPDATE, which needs no DELETE.
REVOKE DELETE ON tms.company_settings FROM tms_app;

-- V35: "a delivery and its attempts are the record of what TMS told an outside system, and the
-- application never edits that record away". The attempt row is insert-only in Java too - all
-- eight of its columns are updatable = false - so it loses UPDATE as well as DELETE.
REVOKE DELETE ON tms.webhook_delivery FROM tms_app;
REVOKE UPDATE, DELETE ON tms.webhook_delivery_attempt FROM tms_app;

-- V40: "a waterfall is the record of who was offered a shipment and in what order ... It is
-- cancelled, not removed."
--
-- The candidate row is the one that needed this most. TenderWaterfall maps its candidates with
-- cascade = ALL and orphanRemoval = true, so the day somebody clears or re-assigns that list
-- Hibernate emits DELETE without anybody deciding to. Today no code does; with the grant gone,
-- the day one does it fails loudly under a company-scoped request instead of quietly discarding
-- the evidence a carrier disputing a rate would ask for.
REVOKE DELETE ON tms.tender_waterfall FROM tms_app;
REVOKE DELETE ON tms.tender_waterfall_candidate FROM tms_app;

-- V41: "an appointment is cancelled, never deleted - who booked which door and what happened is
-- exactly what a carrier disputing a detention charge asks for". Appointment.cancel() is a
-- state change. The three master tables beside it keep their DELETE on purpose.
REVOKE DELETE ON tms.appointment FROM tms_app;

-- V45: "what was signed for is a commercial fact somebody may be invoiced or credited against.
-- A line entered by mistake is corrected to zero, not erased."
REVOKE DELETE ON tms.order_delivery_line FROM tms_app;

-- V46: "an approval and an export are decisions somebody made, and the record of a decision that
-- can be edited is not a record. A reversal is a new approval row." Both entities are
-- constructor-only in Java, with every column updatable = false, so both lose UPDATE too.
REVOKE UPDATE, DELETE ON tms.settlement_approval FROM tms_app;
REVOKE UPDATE, DELETE ON tms.payable_export FROM tms_app;

-- ---------------------------------------------------------------------------
-- 2. The authorization catalogue becomes read-only for the runtime role
-- ---------------------------------------------------------------------------
--
-- This one was never claimed anywhere, and it is the sharpest of the set.
--
-- tms.role, tms.permission and tms.role_permission are schema-contract reference data: seeded by
-- V3/V5 and extended by V14/V18/V23, all of which run as the schema owner. No JPA entity maps
-- them; the only Java that names them at all is JdbcIdentityRepository and
-- UserAdministrationRepository, and both only JOIN. Nothing in the application, and nothing in
-- any test, writes a row.
--
-- Meanwhile tms_app held all four verbs on them from V13's blanket grant, and their policy is
-- p_backend_managed - USING (true) WITH CHECK (true) - because they are read before a company
-- scope exists and cannot be keyed on a tenant. So RLS contributes no filter here at all: on
-- these three tables the grant *is* the whole control, and it was the widest one in the schema.
--
-- What that bought an attacker is not a data leak but a privilege escalation. Effective
-- permissions are resolved by joining tms.role_permission (JdbcIdentityRepository); a single
-- injected INSERT under any company-scoped request would have added a permission to a role for
-- every user of every tenant, and the audit trail would show a legitimate request. Read-only is
-- the privilege the application actually uses.
--
-- Membership is deliberately not in this list: tms.membership and tms.membership_role are
-- written by the user-administration surface and keep their four verbs.
REVOKE INSERT, UPDATE, DELETE ON tms.role            FROM tms_app;
REVOKE INSERT, UPDATE, DELETE ON tms.permission      FROM tms_app;
REVOKE INSERT, UPDATE, DELETE ON tms.role_permission FROM tms_app;

-- ---------------------------------------------------------------------------
-- 3. tms.set_updated_at() pins its search_path
-- ---------------------------------------------------------------------------
--
-- The schema has exactly two functions. tms.current_company_id() (V13) pins
-- `SET search_path = pg_catalog, pg_temp`; this one, written in V1, inherits whatever the caller
-- happens to have.
--
-- Today that is not exploitable: the body resolves only now(), pg_catalog is searched first
-- whether or not it appears in search_path, and PostgreSQL 15+ no longer lets PUBLIC create
-- objects in `public`. It is pinned anyway for two reasons. It is the standing advice for every
-- function attached to a trigger, and Supabase's own linter reports the unpinned case as
-- `function_search_path_mutable`, so leaving it open means living with a finding that has to be
-- re-explained every time somebody runs the check. And the property that makes it harmless -
-- "the body happens to reference nothing schema-qualified" - is one line of a future edit away
-- from being false, at which point the trigger fires under the caller's search_path on every
-- write in the schema.
--
-- CREATE OR REPLACE, not DROP and re-create: the 34 triggers bound to this function reference
-- it by OID, and replacing in place keeps them - and keeps V4's REVOKE ... FROM PUBLIC, which a
-- re-created function would lose.
CREATE OR REPLACE FUNCTION tms.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION tms.set_updated_at() IS
    'BEFORE UPDATE trigger function: stamps updated_at with the transaction timestamp. '
    'SECURITY INVOKER with a pinned search_path (V50), so it resolves nothing from the '
    'caller''s namespace.';

-- ---------------------------------------------------------------------------
-- 4. What was NOT done: FORCE ROW LEVEL SECURITY
-- ---------------------------------------------------------------------------
--
-- The obvious next hardening is FORCE ROW LEVEL SECURITY, which would make the policies apply
-- to the schema owner too - and the owner is what the backend connects as outside a
-- company-scoped request. It is deliberately still not set, and re-examined here rather than
-- inherited from ADR-005, because the evidence against it is now countable rather than
-- predicted:
--
--   * The migration history performs 19 cross-company data backfills as the owner - V14's
--     `UPDATE tms.origin SET location_id = id;` and V23's rewrite of origin, destination,
--     location_role, route, route_stop, transport_order, planning_run and trip_stop among them.
--     Under FORCE every one of those matches `company_id = tms.current_company_id()`, which is
--     NULL in a migration, so each would touch zero rows and Flyway would report success. A
--     control whose failure mode is a silently empty data migration is a trap, not a control.
--
--   * WebhookDispatchScheduler - the product's only scheduled task - runs with no security
--     context and therefore no CompanyScope, so its connection is never switched to tms_app and
--     it drains every company's queue as the owner. V35 documents this as the only way one
--     worker can serve every tenant. Under FORCE it would read zero rows: outbound webhooks
--     would stop, with no error anywhere.
--
--   * Principal resolution reads tms.app_user and tms.membership before a company exists. Those
--     carry p_backend_managed USING (true), so they would survive FORCE - but every
--     principal-scoped endpoint that then touches a company-scoped table as the owner would not.
--
-- All three failures are silent: zero rows, not SQLSTATE 42501. FORCE would convert a
-- defence-in-depth measure into an availability and data-integrity failure mode that no test
-- and no alert would catch. The narrowing this file performs instead reduces what the runtime
-- role can do at all, which holds on every path including the ones FORCE would break.
--
-- MigrationConventionTest.rowLevelSecurityIsNeverForcedOnTheOwner() now fails the build if a
-- future migration sets it without an ADR, and SchemaExposureIntegrationTest keeps asserting the
-- same thing against a real database.
