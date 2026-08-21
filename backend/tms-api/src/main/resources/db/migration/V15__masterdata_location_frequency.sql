-- TMS by EBIM - V15 master data: location-frequency service calendar association.
--
-- Closes the gap V7 deliberately left open (docs/database/DATA_MODEL.md section 8.3): "when
-- Orders/Planning defines what 'which frequency serves this destination' needs to look like,
-- add the table then." Job 03 of the overnight V3 pack is that concrete requirement - a
-- location/store must be able to answer "can I be serviced/dispatched on this date?"
-- independently of whether it also happens to be a stop on some tms.route, because a route's
-- own frequency_id (V8) only ever describes the route's cadence, not an individual store's.
--
-- This table associates the canonical tms.location (V14) with tms.frequency, not
-- tms.destination: tms.location is now the forward-looking master, and every location that
-- currently has a SHIP_TO role already has a location_id-linked tms.destination projection
-- (ADR_LOCATION_MODEL), so nothing downstream loses reach by attaching the calendar here.
--
-- Deliberately NOT hard-wired to tms.route: a route may reference a frequency (V8) for its own
-- planning cadence, and a location may independently reference one or more frequencies for its
-- own service calendar. Neither implies the other, which is exactly what the job brief asks
-- for ("do not hard-wire frequency solely to a route if that prevents a store from having its
-- own calendar").

-- ---------------------------------------------------------------------------
-- tms.location_frequency - a location's (possibly time-bounded) service calendar assignment
-- ---------------------------------------------------------------------------
-- Carries its own company_id, like tms.route_stop (V8) and unlike tms.frequency_weekly_rule
-- (V7): it has cross-table references to two independently company-scoped masters
-- (tms.location and tms.frequency), so both composite tenant FKs below need a company_id on
-- this row to be checked against - the same reasoning DATA_MODEL.md section 9.1 gives for
-- route_stop.
CREATE TABLE tms.location_frequency (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id      uuid        NOT NULL,
    location_id     uuid        NOT NULL,
    frequency_id    uuid        NOT NULL,
    -- Both optional: NULL effective_from means "always has, no start boundary"; NULL
    -- effective_to means "still in effect, no end boundary". Most V1 associations will leave
    -- both NULL - a store simply follows a frequency - but a seasonal or contract-bound
    -- calendar can bound either edge without a second migration.
    effective_from  date,
    effective_to    date,
    -- Independent of the row's date range: an operator can pause an association (keep the
    -- configured dates) without losing them, the same reason frequency_weekly_rule.enabled
    -- exists (V7) instead of deleting/recreating the row on every toggle.
    active          boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    CONSTRAINT pk_location_frequency PRIMARY KEY (id),
    CONSTRAINT fk_location_frequency_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_location_frequency_location FOREIGN KEY (location_id)
        REFERENCES tms.location (id) ON DELETE RESTRICT,
    -- MATCH SIMPLE is irrelevant here (location_id is NOT NULL, always checked): a location's
    -- frequency association must belong to the location's own company - the same composite-FK
    -- tenant guarantee route_stop.destination_id carries (V8).
    CONSTRAINT fk_location_frequency_location_company FOREIGN KEY (location_id, company_id)
        REFERENCES tms.location (id, company_id),
    CONSTRAINT fk_location_frequency_frequency FOREIGN KEY (frequency_id)
        REFERENCES tms.frequency (id) ON DELETE RESTRICT,
    CONSTRAINT fk_location_frequency_frequency_company FOREIGN KEY (frequency_id, company_id)
        REFERENCES tms.frequency (id, company_id),
    CONSTRAINT ck_location_frequency_date_range
        CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from)
);

-- Every business list is "list mine": company_id leads the index (ADR-003).
CREATE INDEX ix_location_frequency_company ON tms.location_frequency (company_id);
-- "This location's calendar" - the eligibility service's own lookup shape.
CREATE INDEX ix_location_frequency_location ON tms.location_frequency (location_id);
-- "Which locations use this frequency" - the reverse lookup a Frequency detail screen would need.
CREATE INDEX ix_location_frequency_frequency ON tms.location_frequency (frequency_id);

COMMENT ON TABLE tms.location_frequency IS
    'A location''s service-calendar assignment: which tms.frequency governs whether it can be '
    'serviced/dispatched on a given date, optionally bounded by an effective date range. A '
    'location may hold several (one or multiple schedules per location - job 03 brief), and this '
    'is deliberately independent of tms.route.frequency_id (V8), which describes a route''s own '
    'planning cadence, not any one stop''s.';
COMMENT ON COLUMN tms.location_frequency.effective_from IS
    'NULL means no start boundary - the association has always applied.';
COMMENT ON COLUMN tms.location_frequency.effective_to IS
    'NULL means no end boundary - the association is still in effect.';
COMMENT ON COLUMN tms.location_frequency.active IS
    'Lets an association be paused without losing its configured date range, mirroring '
    'frequency_weekly_rule.enabled (V7).';

CREATE TRIGGER tr_location_frequency_set_updated_at
    BEFORE UPDATE ON tms.location_frequency
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

-- ---------------------------------------------------------------------------
-- Tenant Row Level Security (ADR-005)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.location_frequency ENABLE ROW LEVEL SECURITY;

-- V13's ALTER DEFAULT PRIVILEGES already grants DML on tables Flyway creates later, so this
-- grant is belt and braces - see V14 section 6 for why that redundancy is still worth writing.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.location_frequency TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.location_frequency
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- Authorization catalogue
-- ---------------------------------------------------------------------------
-- No new permission: this is a sub-resource of the Location master ("this location's service
-- calendar"), reachable only from /masterdata/locations/{id}/frequencies and gated by the
-- masterdata.location:read / masterdata.location:manage permissions V14 already added.
