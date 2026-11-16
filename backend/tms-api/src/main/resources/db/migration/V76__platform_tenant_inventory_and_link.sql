-- ===========================================================================
-- V76 - Tenant inventory and tenant link for EBIM MasterAdmin (tenant-inventory.v1)
-- ===========================================================================
--
-- Two additive routes on the MasterAdmin chain (ADR-012), with scopes of their own taken from
-- configuration (docs/platform-provisioning/TENANT_INVENTORY.md):
--
--     GET /internal/platform-provisioning/tenants                       list every organization
--     PUT /internal/platform-provisioning/tenants/{controlPlaneTenantId}/link
--
-- The list is a read of tms.organization, V51 and V58 and needs nothing new here.
--
-- The link "adopts" an organization that exists in TMS but was not created by MasterAdmin (it
-- predates the control plane, or was created by hand). It writes ONE row into V51's
-- platform_provisioning_request mapping the MasterAdmin tenant to that existing organization, so the
-- status GET, entitlements.v1 (V56) and lifecycle.v1 (V58) - which all resolve the tenant from that
-- table, and whose foreign keys point at it - work exactly as for a tenant MasterAdmin created.
-- Nothing else is written: no organization, company, user, membership or role.
--
-- What that row cannot have is what only creation produces: the administrator profile and membership
-- (a linked organization already has its people). Hence:
--
--   origin = 'PROVISIONED'  created by POST /tenants; every column as V51 defined it (unchanged)
--   origin = 'LINKED'       adopted by PUT .../link; no administrator columns, and the company is the
--                           organization's oldest one (NULL only if it has none)
--
-- The row stays write-once (V51's trigger is untouched) and unique per MasterAdmin tenant and per
-- organization, which is what makes a second link to either side a LINK_CONFLICT.

-- ---------------------------------------------------------------------------
-- 1. platform_provisioning_request: origin, and the columns a link cannot fill
-- ---------------------------------------------------------------------------
-- ADD COLUMN with a constant default rewrites no row and fires no row trigger, so the write-once
-- trigger of V51 does not object; every existing row reads 'PROVISIONED'.
ALTER TABLE tms.platform_provisioning_request
    ADD COLUMN origin text NOT NULL DEFAULT 'PROVISIONED';

ALTER TABLE tms.platform_provisioning_request
    ALTER COLUMN company_id DROP NOT NULL,
    ALTER COLUMN admin_app_user_id DROP NOT NULL,
    ALTER COLUMN admin_membership_id DROP NOT NULL,
    ALTER COLUMN admin_profile_reused DROP NOT NULL;

-- The NOT NULLs just dropped are kept, for a created tenant, by this check: relaxing them is for a
-- link only. A linked row never names an administrator - it did not create one.
ALTER TABLE tms.platform_provisioning_request
    ADD CONSTRAINT ck_platform_provisioning_request_origin CHECK (
        (origin = 'PROVISIONED'
            AND company_id IS NOT NULL
            AND admin_app_user_id IS NOT NULL
            AND admin_membership_id IS NOT NULL
            AND admin_profile_reused IS NOT NULL)
        OR (origin = 'LINKED'
            AND admin_app_user_id IS NULL
            AND admin_membership_id IS NULL
            AND admin_profile_reused IS NULL));

COMMENT ON COLUMN tms.platform_provisioning_request.origin IS
    'PROVISIONED: created by POST /tenants (V51). LINKED: an existing organization adopted by '
    'PUT /tenants/{id}/link (V76, tenant-inventory.v1) - no administrator columns, nothing created.';

-- ---------------------------------------------------------------------------
-- 2. platform_provisioning_audit: the two new operations and the link outcomes
-- ---------------------------------------------------------------------------
ALTER TABLE tms.platform_provisioning_audit
    DROP CONSTRAINT ck_platform_provisioning_audit_operation,
    ADD CONSTRAINT ck_platform_provisioning_audit_operation
        CHECK (operation IN ('CREATE_TENANT', 'GET_TENANT_STATUS', 'LIST_TENANTS', 'LINK_TENANT'));

ALTER TABLE tms.platform_provisioning_audit
    DROP CONSTRAINT ck_platform_provisioning_audit_result,
    ADD CONSTRAINT ck_platform_provisioning_audit_result
        CHECK (result IN ('CREATED', 'REPLAYED', 'FOUND', 'REJECTED', 'CONFLICT', 'ERROR',
                          'LINKED', 'ALREADY_LINKED'));
