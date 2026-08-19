-- TMS by EBIM - V2 identity and tenancy foundation.
--
-- Implements ADR-003: organization is the parent tenant boundary, company is the
-- operational scope that business masters and transactions will carry, and membership is
-- the server-side source of truth for what a user may do and where.
--
-- Conventions used here and by every later migration:
--   * uuid primary keys, defaulted with gen_random_uuid();
--   * codes are normalized (upper case for tenant/role codes, lower case for permission
--     parts) and enforced by check constraints, not by convention alone;
--   * long-lived masters carry `active` and are deactivated, never deleted;
--   * created_at/updated_at on every table, updated_at maintained by trigger;
--   * created_by/updated_by only where a real actor exists (see the note on reference data);
--   * ON DELETE RESTRICT everywhere except pure configuration link tables, so that no
--     delete can silently erase history;
--   * constraint names are explicit: pk_/fk_/uq_/ck_/ix_ + table + purpose.

-- ---------------------------------------------------------------------------
-- app_user - the business profile behind a Supabase Auth identity
-- ---------------------------------------------------------------------------
-- Created first because it is the target of every actor column.
--
-- auth_user_id maps to auth.users.id. There is deliberately NO foreign key to auth.users:
--   * Flyway must not depend on, or lock, the Supabase-managed auth schema (ADR-002);
--   * TMS stays portable if the identity provider ever changes;
--   * the mapping is established server-side by the backend at first sign-in, which is
--     also where the JWT is validated (Step 03).
-- It is nullable so that a profile can be created by an administrator before the person
-- has accepted an invitation, and unique so that one auth identity maps to one profile.
CREATE TABLE tms.app_user (
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    auth_user_id  uuid,
    email         text        NOT NULL,
    full_name     text        NOT NULL,
    active        boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_by    uuid,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uq_app_user_auth_user_id UNIQUE (auth_user_id),
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT ck_app_user_email_shape CHECK (email ~ '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$'),
    CONSTRAINT ck_app_user_full_name_not_blank CHECK (btrim(full_name) <> ''),
    CONSTRAINT fk_app_user_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_app_user_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

COMMENT ON TABLE tms.app_user IS
    'Business profile of a person using TMS. Deliberately not tenant-scoped: tenancy comes '
    'from tms.membership, because one person may work for several organizations.';
COMMENT ON COLUMN tms.app_user.auth_user_id IS
    'Supabase auth.users.id. No FK on purpose: the auth schema is Supabase-managed and TMS '
    'stays portable. Resolved server-side from the validated JWT.';
COMMENT ON COLUMN tms.app_user.active IS
    'Deactivation flag. Users are never physically deleted; history and actor columns must survive.';

-- ---------------------------------------------------------------------------
-- organization - the parent tenant boundary
-- ---------------------------------------------------------------------------
CREATE TABLE tms.organization (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    code       text        NOT NULL,
    name       text        NOT NULL,
    active     boolean     NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid,
    CONSTRAINT pk_organization PRIMARY KEY (id),
    CONSTRAINT uq_organization_code UNIQUE (code),
    CONSTRAINT ck_organization_code_shape CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$'),
    CONSTRAINT ck_organization_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT fk_organization_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

COMMENT ON TABLE tms.organization IS
    'Top level tenant. Owns one or more companies. Organization codes are unique across the '
    'installation because they are the outermost tenant key.';

-- ---------------------------------------------------------------------------
-- company - the operational tenant scope (ADR-003)
-- ---------------------------------------------------------------------------
CREATE TABLE tms.company (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    organization_id uuid        NOT NULL,
    code            text        NOT NULL,
    name            text        NOT NULL,
    tax_identifier  text,
    time_zone       text        NOT NULL DEFAULT 'UTC',
    active          boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    CONSTRAINT pk_company PRIMARY KEY (id),
    CONSTRAINT fk_company_organization FOREIGN KEY (organization_id)
        REFERENCES tms.organization (id) ON DELETE RESTRICT,
    -- Company codes are unique inside their organization, not globally: two customers may
    -- both run a company called "LOGISTICA".
    CONSTRAINT uq_company_organization_code UNIQUE (organization_id, code),
    -- Redundant on its own, but it is the target of the composite foreign key on
    -- membership, which is what makes "the company must belong to the same organization"
    -- a database guarantee instead of an application convention.
    CONSTRAINT uq_company_id_organization UNIQUE (id, organization_id),
    CONSTRAINT ck_company_code_shape CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$'),
    CONSTRAINT ck_company_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_company_time_zone_not_blank CHECK (btrim(time_zone) <> ''),
    CONSTRAINT ck_company_tax_identifier_not_blank CHECK (tax_identifier IS NULL OR btrim(tax_identifier) <> ''),
    CONSTRAINT fk_company_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_company_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

COMMENT ON TABLE tms.company IS
    'Operational tenant scope. Business masters and transactions carry company_id (ADR-003).';
COMMENT ON COLUMN tms.company.time_zone IS
    'IANA time zone used to interpret operational days and planning windows. Validated in Java; '
    'the database only guarantees it is not blank because a CHECK cannot query pg_timezone_names.';
COMMENT ON CONSTRAINT uq_company_id_organization ON tms.company IS
    'Supports the composite FK from tms.membership so a membership can never point at a company '
    'that belongs to another organization.';

-- organization_id is already the leading column of uq_company_organization_code, so
-- "list the companies of an organization" is covered without an extra index.

-- ---------------------------------------------------------------------------
-- role and permission - the authorization vocabulary
-- ---------------------------------------------------------------------------
-- V1 ships a single system-managed catalogue of roles. Per-organization custom roles are
-- a later migration (nullable organization_id + unique (organization_id, code)); they are
-- not built now because nothing in V1 needs them.
--
-- role and permission carry no created_by/updated_by: their rows are reference data
-- inserted by migration V3, so there is no reliable actor to record. That is the rule the
-- step calls for - actor columns only where actor context genuinely exists.
CREATE TABLE tms.role (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    code           text        NOT NULL,
    name           text        NOT NULL,
    description    text,
    scope_level    text        NOT NULL,
    system_managed boolean     NOT NULL DEFAULT true,
    active         boolean     NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_role PRIMARY KEY (id),
    CONSTRAINT uq_role_code UNIQUE (code),
    CONSTRAINT ck_role_code_shape CHECK (code ~ '^[A-Z][A-Z0-9_]{2,39}$'),
    CONSTRAINT ck_role_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_role_scope_level CHECK (scope_level IN ('ORGANIZATION', 'COMPANY'))
);

COMMENT ON TABLE tms.role IS
    'Named bundle of permissions. scope_level records whether the role is meaningful on an '
    'organization-wide membership or on a company-scoped one; Java enforces the pairing.';

CREATE TABLE tms.permission (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    resource    text        NOT NULL,
    action      text        NOT NULL,
    code        text        GENERATED ALWAYS AS (resource || ':' || action) STORED,
    description text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_permission PRIMARY KEY (id),
    CONSTRAINT uq_permission_resource_action UNIQUE (resource, action),
    CONSTRAINT uq_permission_code UNIQUE (code),
    CONSTRAINT ck_permission_resource_shape CHECK (resource ~ '^[a-z][a-z0-9_]*([.][a-z][a-z0-9_]*)*$'),
    CONSTRAINT ck_permission_action_shape CHECK (action ~ '^[a-z][a-z_]{2,31}$')
);

COMMENT ON TABLE tms.permission IS
    'Atomic capability, normalized as (resource, action). `code` is generated as '
    '"resource:action" so application code and logs have one stable string form.';

CREATE TABLE tms.role_permission (
    role_id       uuid        NOT NULL,
    permission_id uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, permission_id),
    -- Configuration link, not business history: removing a role legitimately removes its
    -- grants, so CASCADE here is safe and intended. A permission still in use cannot be
    -- deleted (RESTRICT), because that would silently widen or narrow authorization.
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id)
        REFERENCES tms.role (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id)
        REFERENCES tms.permission (id) ON DELETE RESTRICT
);

-- "which roles grant this permission" - the reverse of the primary key.
CREATE INDEX ix_role_permission_permission ON tms.role_permission (permission_id);

COMMENT ON TABLE tms.role_permission IS
    'Role to permission grants. Pure configuration: cascades from role, restricts on permission.';

-- ---------------------------------------------------------------------------
-- membership - who may act, in which tenant, with which roles
-- ---------------------------------------------------------------------------
-- This is the one table that deliberately carries BOTH organization_id and company_id,
-- and ADR-003 requires that exception to be documented in the migration that creates it:
--
--   1. company_id IS NULL expresses an organization-wide membership (an administrator who
--      may act in every company of the organization). Without organization_id that row
--      could not name its tenant at all.
--   2. With both columns present, the composite foreign key
--      (company_id, organization_id) -> company (id, organization_id) makes it physically
--      impossible to attach a membership to a company of a different organization. A
--      single company_id column would leave that invariant to application code.
--
-- Business tables from Step 05 onwards carry company_id only.
CREATE TABLE tms.membership (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    app_user_id     uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    company_id      uuid,
    active          boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    CONSTRAINT pk_membership PRIMARY KEY (id),
    CONSTRAINT fk_membership_app_user FOREIGN KEY (app_user_id)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_membership_organization FOREIGN KEY (organization_id)
        REFERENCES tms.organization (id) ON DELETE RESTRICT,
    -- MATCH SIMPLE (the default): satisfied when company_id is NULL, enforced otherwise.
    CONSTRAINT fk_membership_company_in_organization FOREIGN KEY (company_id, organization_id)
        REFERENCES tms.company (id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_membership_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_membership_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

-- One membership row per (user, organization, company). Two partial unique indexes rather
-- than UNIQUE NULLS NOT DISTINCT: the intent is explicit and it does not depend on a
-- PostgreSQL 15+ behaviour change.
CREATE UNIQUE INDEX uq_membership_user_organization_company
    ON tms.membership (app_user_id, organization_id, company_id)
    WHERE company_id IS NOT NULL;

CREATE UNIQUE INDEX uq_membership_user_organization_wide
    ON tms.membership (app_user_id, organization_id)
    WHERE company_id IS NULL;

-- The hot path of every authenticated request: JWT -> app_user -> active memberships.
CREATE INDEX ix_membership_app_user_active
    ON tms.membership (app_user_id)
    WHERE active;

-- Administration screens: "who can act in this company / organization".
CREATE INDEX ix_membership_company
    ON tms.membership (company_id)
    WHERE company_id IS NOT NULL;

CREATE INDEX ix_membership_organization ON tms.membership (organization_id);

COMMENT ON TABLE tms.membership IS
    'Server-side source of truth for tenancy. company_id NULL means organization-wide scope. '
    'A client-supplied company id is always validated against these rows (ADR-003).';
COMMENT ON COLUMN tms.membership.company_id IS
    'NULL = organization-wide membership. When set, the composite FK guarantees the company '
    'belongs to organization_id.';

-- ---------------------------------------------------------------------------
-- membership_role - roles held inside one tenant scope
-- ---------------------------------------------------------------------------
-- ADR-003 sketched a single role on the membership row. A link table is used instead
-- because a real planner is frequently also a viewer of another module, and a single role
-- column would force duplicate membership rows for the same (user, company) pair - which
-- the unique indexes above correctly forbid. The ADR decision (membership is the source of
-- truth for tenancy and role) is unchanged; only its cardinality is refined. Documented in
-- docs/database/DATA_MODEL.md.
CREATE TABLE tms.membership_role (
    membership_id uuid        NOT NULL,
    role_id       uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    CONSTRAINT pk_membership_role PRIMARY KEY (membership_id, role_id),
    -- Cascade from membership only: the link has no meaning without its membership.
    -- Roles and users cannot be deleted out from under it.
    CONSTRAINT fk_membership_role_membership FOREIGN KEY (membership_id)
        REFERENCES tms.membership (id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_role_role FOREIGN KEY (role_id)
        REFERENCES tms.role (id) ON DELETE RESTRICT,
    CONSTRAINT fk_membership_role_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

-- "who holds this role" and impact analysis before a role change.
CREATE INDEX ix_membership_role_role ON tms.membership_role (role_id);

COMMENT ON TABLE tms.membership_role IS
    'Roles held by a membership. Many-to-many so one person needs only one membership row '
    'per company.';

-- ---------------------------------------------------------------------------
-- updated_at triggers
-- ---------------------------------------------------------------------------
-- No WHEN (OLD.* IS DISTINCT FROM NEW.*) filter on purpose. PostgreSQL rejects a
-- whole-row reference in a BEFORE trigger's WHEN condition on a table that has generated
-- columns (tms.permission.code today; the generated geography columns of Steps 05/06
-- tomorrow), so the unconditional form is the one convention that holds for every table.
-- The cost is a refreshed updated_at on a no-op UPDATE, which is harmless.
CREATE TRIGGER tr_app_user_set_updated_at
    BEFORE UPDATE ON tms.app_user
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

CREATE TRIGGER tr_organization_set_updated_at
    BEFORE UPDATE ON tms.organization
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

CREATE TRIGGER tr_company_set_updated_at
    BEFORE UPDATE ON tms.company
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

CREATE TRIGGER tr_role_set_updated_at
    BEFORE UPDATE ON tms.role
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

CREATE TRIGGER tr_permission_set_updated_at
    BEFORE UPDATE ON tms.permission
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

CREATE TRIGGER tr_membership_set_updated_at
    BEFORE UPDATE ON tms.membership
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();
