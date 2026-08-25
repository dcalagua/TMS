-- TMS by EBIM - V34 company settings and the IAM administration trail (job 12 overnight-v4).
--
-- Two things, and they belong in one migration because they are two halves of the same gap: TMS
-- has been sellable to one company since V2 and administrable by nobody since V2. The permission
-- catalogue has carried iam.company:*, iam.user:* and iam.membership:* since V3 and no endpoint
-- has ever checked them, so onboarding a second customer meant somebody with a psql prompt.
--
-- ---------------------------------------------------------------------------
-- 1. Why a table beside tms.company and not four more columns on it
-- ---------------------------------------------------------------------------
-- tms.company is on the hot path. JdbcIdentityRepository.COMPANY_PERMISSIONS_SQL and
-- JdbcCompanyScopeLoader.COMPANY_SQL read it on *every* authenticated request to decide which
-- companies the caller may select; widening that row widens both of those reads for the sake of
-- values neither of them uses.
--
-- The settings, by contrast, are read at exactly three moments - creating an order, creating a
-- shipment, and previewing a location import - and written from one administration screen. A
-- separate row with the company as its primary key keeps the hot read narrow, keeps the
-- 1:1 cardinality a database guarantee rather than a convention, and leaves an obvious place for
-- the next setting that earns its way in.
--
-- ---------------------------------------------------------------------------
-- 2. Every column here has a consumer today
-- ---------------------------------------------------------------------------
-- This is the rule the whole table is built to, because a settings table is where a product goes
-- to accumulate fields nobody reads:
--
--   default_country          -> LocationImportValidator, for a row that leaves `country` blank.
--                               Until now that fallback was the literal 'PE' in Java, which is
--                               correct for the launch market and wrong for the second one.
--   order_number_prefix      -> OrderNumbers.format, both callers (the manual API and the bulk
--                               import). Was the constant "TO-".
--   shipment_number_prefix   -> TripService.generateShipmentNumber. Was the literal "SH-".
--
-- Three columns, three call sites. A fourth was written and removed before this migration was
-- committed - see section 8 on default_locale - because its only honest consumer would have been
-- the authentication query, and that is not a trade worth making for a language preference.
--
-- time_zone is deliberately NOT duplicated here. It already exists on tms.company, CompanyScope
-- reads it on every request and CompanyScope.today() is the whole product's definition of "what
-- day is it for this tenant". A second copy could only disagree with it. The administration
-- screen edits the column where it lives.
--
-- ---------------------------------------------------------------------------
-- 3. The prefixes are safe to change, and here is why
-- ---------------------------------------------------------------------------
-- tms.transport_order_number_seq and tms.shipment_number_seq are installation-wide, not
-- per-company. So the number after the prefix is drawn from one counter for the whole
-- installation: two companies that both pick "TO-" still cannot collide, and a company that
-- switches from "TO-" to "GRP-" halfway through a year produces no duplicate either, because the
-- part that makes the value unique was never the prefix.
--
-- That is also why per-company sequences are NOT introduced here. They would make each tenant's
-- numbering start at 1 - which some customers do ask for - at the cost of one sequence per
-- company created at onboarding time, and the uniqueness of tms.transport_order.order_number
-- would then depend on the prefix actually differing. That is a real feature with a real design;
-- it is not a side effect of adding a settings row.

CREATE TABLE tms.company_settings (
    company_id             uuid        NOT NULL,
    -- ISO 3166-1 alpha-2, upper case. Narrower than tms.location.country (free text, up to 60
    -- characters) on purpose: that column records what a location's country *is*, including
    -- whatever a legacy import wrote into it, while this one is a value the product will hand to
    -- a new row and therefore has to be a code and not a spelling.
    default_country        text        NOT NULL DEFAULT 'PE',
    order_number_prefix    text        NOT NULL DEFAULT 'TO-',
    shipment_number_prefix text        NOT NULL DEFAULT 'SH-',
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    created_by             uuid,
    updated_by             uuid,
    -- The company *is* the key. No surrogate id: there is exactly one settings row per company,
    -- and a separate id would make "two settings rows for one company" expressible.
    CONSTRAINT pk_company_settings PRIMARY KEY (company_id),
    CONSTRAINT fk_company_settings_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_company_settings_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_company_settings_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_company_settings_default_country CHECK (default_country ~ '^[A-Z]{2}$'),
    -- Upper case, starting with a letter, ending in a single hyphen: "TO-", "SH-", "GRP-".
    -- The hyphen is required rather than optional so that a prefix can never run into the digits
    -- of the sequence and produce a number nobody can read back ("TO00000042").
    CONSTRAINT ck_company_settings_order_prefix CHECK (order_number_prefix ~ '^[A-Z][A-Z0-9]{0,5}-$'),
    CONSTRAINT ck_company_settings_shipment_prefix CHECK (shipment_number_prefix ~ '^[A-Z][A-Z0-9]{0,5}-$')
);

COMMENT ON TABLE tms.company_settings IS
    'Operational defaults of one company: the country a location import assumes when a row leaves '
    'it blank, and the prefixes order and shipment numbers are formatted with. One row per '
    'company. Every column has a consumer in the application - see V34 section 2 - and none of '
    'them is a secret or a credential.';
COMMENT ON COLUMN tms.company_settings.default_country IS
    'ISO 3166-1 alpha-2. Applied by LocationImportValidator to a row that left country blank; '
    'never applied to an existing location.';
COMMENT ON COLUMN tms.company_settings.order_number_prefix IS
    'Prefix OrderNumbers.format puts in front of tms.transport_order_number_seq. Changing it does '
    'not renumber anything and cannot create a duplicate: the sequence is installation-wide '
    '(V34 section 3).';
COMMENT ON COLUMN tms.company_settings.shipment_number_prefix IS
    'The shipment counterpart of order_number_prefix, used by TripService.generateShipmentNumber. '
    'tms.trip.shipment_number keeps its V19 column default as a backstop for a raw insert; the '
    'application always supplies the value.';

CREATE TRIGGER tr_company_settings_set_updated_at
    BEFORE UPDATE ON tms.company_settings
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

-- ---------------------------------------------------------------------------
-- 4. Backfill, and the rule that keeps it true
-- ---------------------------------------------------------------------------
-- One row per company that exists now, taking every column default. The defaults are exactly the
-- constants the code used before this migration ('PE', 'TO-', 'SH-'), so nothing an installation
-- has already issued changes shape.
--
-- Going forward the row is written lazily, the first time somebody saves the settings screen for
-- that company, and never at company-creation time. That is not laziness for its own sake - it is
-- forced by the tenant policy in section 5 and worth stating so nobody "fixes" it: an
-- ORGANIZATION_ADMIN creating company B is doing so inside a transaction scoped to company A, so
-- tms.current_company_id() is A and an INSERT carrying company_id = B is refused by WITH CHECK.
-- The right answer is not to weaken the policy for one insert; it is for the read to have a
-- defined answer when the row is absent.
--
-- So every read goes through CompanySettingsPort, which resolves a missing row to
-- CompanySettings.defaults() - the same constants this backfill applies - rather than through an
-- outer join each caller writes for itself. A company with no settings row behaves exactly like a
-- company that has never edited its settings, instead of failing an order creation over a
-- configuration row.
--
-- Inactive companies are included in the backfill: they can be reactivated, and a settings row is
-- not access.
INSERT INTO tms.company_settings (company_id)
SELECT c.id FROM tms.company c
ON CONFLICT (company_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5. Grants and RLS
-- ---------------------------------------------------------------------------
-- A tenant policy, not the p_backend_managed "USING (true)" that V13 section 5 gives the identity
-- tables. Those tables are read *before* a company is chosen, which is why they cannot carry a
-- policy keyed on tms.current_company_id(). This one is only ever touched inside an already
-- company-scoped transaction, so it gets the real thing and a query that forgets its predicate
-- stops being a cross-tenant read of another customer's numbering.
ALTER TABLE tms.company_settings ENABLE ROW LEVEL SECURITY;

-- No DELETE. The row is 1:1 with a company that is itself never deleted (V2: "long-lived masters
-- carry `active` and are deactivated, never deleted"), so a DELETE grant could only ever be used
-- to lose a tenant's numbering configuration.
GRANT SELECT, INSERT, UPDATE ON tms.company_settings TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.company_settings
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 6. Administration is audited like everything else
-- ---------------------------------------------------------------------------
-- Three new aggregate types, because administering a tenant produces changes that are asked about
-- by themselves and would otherwise be invisible: "who changed our shipment prefix", "who let this
-- person in", "who took their planning rights away". The audit trail already answered all three
-- for a vehicle and answered none of them for a user.
--
-- MEMBERSHIP and not "USER_ACCESS": the row that actually changes when somebody is given or denied
-- access to a company is tms.membership, and naming the aggregate after the row keeps
-- aggregate_id resolvable. APP_USER covers the profile itself - the name and the email - which is
-- a different change with a different blast radius, because tms.app_user is global and a person
-- may hold memberships in more than one organization.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_aggregate_type;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_aggregate_type CHECK (aggregate_type IN (
    'LOCATION', 'CARRIER', 'VEHICLE', 'DRIVER', 'TRANSPORT_ORDER', 'TRIP', 'PLANNING_RUN',
    'INTEGRATION_CLIENT', 'MASTER_DATA_IMPORT_BATCH', 'ORDER_IMPORT_BATCH', 'SHIPMENT',
    'RATE_CARD', 'TRIP_COST',
    'COMPANY', 'APP_USER', 'MEMBERSHIP'));

-- ROLES_CHANGED rather than a plain UPDATE on the membership, for the reason V26 gives about
-- DRIVER_CHANGE: "who gave this account permission to confirm shipments, and when" is a question
-- asked on its own, usually after something has already gone wrong, and a generic UPDATE row would
-- bury it among edits to the person's phone number. The metadata carries the role codes before and
-- after, so the answer is in the event and does not depend on reconstructing history.
--
-- Granting access itself is CREATE / DEACTIVATE / ACTIVATE on MEMBERSHIP, which already say
-- exactly what happened; no TENDER-style vocabulary is minted for them.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
    'CREATE', 'UPDATE', 'ACTIVATE', 'DEACTIVATE', 'ASSIGN_ORDER', 'REMOVE_ORDER', 'MOVE_ORDER',
    'VEHICLE_CHANGE', 'DRIVER_CHANGE', 'CONFIRM', 'CANCEL', 'CREDENTIAL_CREATE',
    'CREDENTIAL_ROTATE', 'CREDENTIAL_REVOKE', 'AUTO_PLAN', 'IMPORT_EXECUTED', 'SHIPMENT_CONFIRMED',
    'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED', 'SHIPMENT_CANCELLED',
    'DELIVERY_RESULT_RECORDED', 'COST_ESTIMATED', 'COST_ACTUAL_RECORDED', 'COST_CLOSED',
    'COST_REOPENED',
    'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED', 'TENDER_CANCELLED',
    'ROLES_CHANGED'));

-- ---------------------------------------------------------------------------
-- 7. No new permission, and that is the point
-- ---------------------------------------------------------------------------
-- The administration API added by this job is guarded entirely by permissions V3 already
-- inserted: iam.company:read/manage, iam.user:read/manage, iam.membership:read/manage. Minting
-- new ones would mean every existing installation had a role catalogue that could not reach its
-- own administration screen until somebody ran a grant.
--
-- The grants V3 made are therefore the authorization design of this job, restated:
--
--   ORGANIZATION_ADMIN  every permission, so it administers its companies and its people.
--   COMPANY_ADMIN       every permission except iam.organization:manage - it administers its own
--                       company and the people in it, and cannot rename or deactivate the
--                       organization above it.
--   PLANNER, VIEWER     iam.company:read only. They can read the company profile screen and hold
--                       none of iam.user:* or iam.membership:*, so the people screen is closed to
--                       them by the endpoint and not merely hidden by the menu.
--
-- The tenancy half is not a permission at all and never will be: a Company Admin reaching another
-- company is impossible because CompanyScope is resolved from the caller's own active memberships
-- (ADR-003), and every statement the administration repositories issue carries that company id.
-- iam.company:manage held in company A grants nothing whatsoever in company B.
--
-- ---------------------------------------------------------------------------
-- 8. Deliberately NOT here
-- ---------------------------------------------------------------------------
--   * No billing, no plan, no subscription and no seat count. Nothing in TMS reads any of them,
--     and a plan column with no enforcement is a number in a table that lies the first time
--     somebody exceeds it. When metering arrives it needs a usage source, a billing period and a
--     decision about what happens at the limit - none of which a settings row can imply.
--   * No per-organization custom roles. V2 already sketched the shape (nullable organization_id
--     plus UNIQUE (organization_id, code)) and it is still not built, because the four
--     system-managed roles cover the jobs that exist and a custom-role editor without a permission
--     picker, an impact preview and a "you are about to lock yourself out" guard is worse than no
--     editor. The administration screen assigns the catalogue; it does not author it.
--   * No self-service company creation. A company is created by an ORGANIZATION_ADMIN through the
--     administration API, inside an organization they already hold. Signing up a brand new
--     organization from a public form needs an email verification flow and an anti-abuse story
--     that this product does not have yet.
--   * No default_locale, and it is the interesting omission because the column was written and
--     then taken back out. A per-company language is a real SaaS request, but its consumer is the
--     browser choosing which bundle to load *before* any company screen is open - which means the
--     value has to travel on GET /api/v1/me, which means a join in
--     JdbcIdentityRepository.COMPANY_PERMISSIONS_SQL, the one query every authenticated request
--     runs. Widening that to carry a preference the LanguageSwitcher already stores per person is
--     the wrong trade. It becomes right the day the language is a property of the *user*
--     (tms.app_user.preferred_locale, already on that hot path's SELECT list) rather than of the
--     tenant - which is also the model that answers what happens when two people in one company
--     want different languages.
--   * No planning defaults. HeuristicPlanningEngine takes no tunable parameter - it groups by
--     corridor, sorts, and fills the biggest vehicle free (see its class comment) - so a
--     "planning defaults" section would be four inputs nothing reads. It belongs to whichever
--     engine first has a knob worth turning.
--   * No map defaults (centre, zoom, provider key). The map already centres on the stops it is
--     drawing, which is better than any stored centre, and the provider key is a deployment
--     secret that must not move into a table an administrator can read.
--   * No per-company sequences - see section 3.
