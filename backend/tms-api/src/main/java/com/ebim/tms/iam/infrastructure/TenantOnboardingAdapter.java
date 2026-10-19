package com.ebim.tms.iam.infrastructure;

import com.ebim.tms.shared.reference.TenantIdentity;
import com.ebim.tms.shared.reference.TenantIdentityLookupPort;
import com.ebim.tms.shared.reference.TenantOnboarding;
import com.ebim.tms.shared.reference.TenantOnboardingCommand;
import com.ebim.tms.shared.reference.TenantOnboardingPort;
import com.ebim.tms.shared.reference.TenantOnboardingRefusedException;
import com.ebim.tms.shared.reference.TenantOnboardingRefusedException.Reason;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code iam}'s side of {@link TenantOnboardingPort}: the only way the platform module creates an
 * organization, a company, an administrator and the membership that ties them together.
 *
 * <p>Two decisions here are load-bearing and neither is obvious from the schema alone.
 *
 * <p><strong>The membership is organization-wide, not company-scoped.</strong> {@code
 * JdbcIdentityRepository} resolves a principal's companies with
 * {@code AND (m.company_id IS NULL OR r.scope_level <> 'ORGANIZATION')}: a membership that names a
 * company <em>discards</em> roles whose scope level is ORGANIZATION. Attaching
 * {@code ORGANIZATION_ADMIN} to a company-scoped membership would therefore produce an
 * administrator with no permissions at all - the rows would look right and the person could sign in
 * to nothing. Leaving {@code company_id} null grants every active company of the organization,
 * which for a tenant administrator is the intent.
 *
 * <p><strong>No password is ever invented.</strong> {@code tms.app_user.auth_user_id} is nullable,
 * so the administrator row is created without a Supabase identity and the person claims it by
 * signing in. TMS neither creates auth users nor stores credentials here.
 *
 * <p>Everything runs in the caller's transaction, which is what makes provisioning all-or-nothing.
 */
@Component
public class TenantOnboardingAdapter implements TenantOnboardingPort, TenantIdentityLookupPort {

    /**
     * The role a tenant administrator is given. Organization-level on purpose: a tenant's first
     * user has to be able to administer the organization itself, including creating the next
     * company, which {@code COMPANY_ADMIN} cannot do.
     */
    private static final String ADMIN_ROLE_CODE = "ORGANIZATION_ADMIN";

    private final JdbcClient jdbcClient;

    public TenantOnboardingAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public TenantOnboarding onboard(TenantOnboardingCommand command) {
        requireOrganizationIsFree(command);
        requireCompanyIsFree(command);

        jdbcClient.sql("""
                        INSERT INTO tms.organization (id, code, name)
                        VALUES (:id, :code, :name)
                        """)
                .param("id", command.organizationId())
                .param("code", command.organizationCode())
                .param("name", command.organizationName())
                .update();

        jdbcClient.sql("""
                        INSERT INTO tms.company (id, organization_id, code, name, tax_identifier, time_zone)
                        VALUES (:id, :organizationId, :code, :name, :taxIdentifier, :timeZone)
                        """)
                .param("id", command.companyId())
                .param("organizationId", command.organizationId())
                .param("code", command.companyCode())
                .param("name", command.companyName())
                .param("taxIdentifier", command.companyTaxIdentifier())
                .param("timeZone", command.companyTimeZone())
                .update();

        if (!command.hasAdmin()) {
            return new TenantOnboarding(command.organizationId(), command.companyId(), null, false);
        }

        UUID roleId = adminRoleId();
        String email = command.adminEmail().trim().toLowerCase(java.util.Locale.ROOT);
        Optional<UUID> existing = existingUserWithoutMembership(email);
        boolean linked = existing.isPresent();
        UUID adminId = existing.orElseGet(() -> createAdminUser(email, command.adminFullName()));

        UUID membershipId = jdbcClient.sql("""
                        INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                        VALUES (:appUserId, :organizationId, NULL)
                        RETURNING id
                        """)
                .param("appUserId", adminId)
                .param("organizationId", command.organizationId())
                .query(UUID.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO tms.membership_role (membership_id, role_id)
                        VALUES (:membershipId, :roleId)
                        """)
                .param("membershipId", membershipId)
                .param("roleId", roleId)
                .update();

        return new TenantOnboarding(command.organizationId(), command.companyId(), adminId, linked);
    }

    @Override
    public Optional<TenantIdentity> find(UUID organizationId, UUID companyId) {
        return jdbcClient.sql("""
                        SELECT o.code AS organization_code, c.code AS company_code, c.time_zone AS time_zone
                        FROM tms.company c
                        JOIN tms.organization o ON o.id = c.organization_id
                        WHERE c.id = :companyId AND o.id = :organizationId
                        """)
                .param("companyId", companyId)
                .param("organizationId", organizationId)
                .query((rs, row) -> new TenantIdentity(
                        rs.getString("organization_code"),
                        rs.getString("company_code"),
                        rs.getString("time_zone")))
                .optional();
    }

    private void requireOrganizationIsFree(TenantOnboardingCommand command) {
        boolean taken = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM tms.organization WHERE id = :id OR code = :code
                        )
                        """)
                .param("id", command.organizationId())
                .param("code", command.organizationCode())
                .query(Boolean.class)
                .single();
        if (taken) {
            throw new TenantOnboardingRefusedException(Reason.ORGANIZATION_TAKEN,
                    "An organization already exists with that identifier or code.");
        }
    }

    private void requireCompanyIsFree(TenantOnboardingCommand command) {
        boolean taken = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM tms.company
                            WHERE id = :id
                               OR (organization_id = :organizationId AND code = :code)
                        )
                        """)
                .param("id", command.companyId())
                .param("organizationId", command.organizationId())
                .param("code", command.companyCode())
                .query(Boolean.class)
                .single();
        if (taken) {
            throw new TenantOnboardingRefusedException(Reason.COMPANY_TAKEN,
                    "A company already exists with that identifier, or with that code in this organization.");
        }
    }

    /**
     * An existing user is reusable only when they belong nowhere yet - a row somebody created and
     * never finished. A user who already has a membership belongs to a tenant, and handing them to
     * another one is not provisioning.
     */
    private Optional<UUID> existingUserWithoutMembership(String email) {
        Optional<UUID> existing = jdbcClient.sql("SELECT id FROM tms.app_user WHERE email = :email")
                .param("email", email)
                .query(UUID.class)
                .optional();
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        boolean placed = jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM tms.membership WHERE app_user_id = :id)")
                .param("id", existing.get())
                .query(Boolean.class)
                .single();
        if (placed) {
            throw new TenantOnboardingRefusedException(Reason.ADMIN_EMAIL_TAKEN,
                    "That administrator email already belongs to a user of another tenant.");
        }
        return existing;
    }

    private UUID createAdminUser(String email, String fullName) {
        return jdbcClient.sql("""
                        INSERT INTO tms.app_user (auth_user_id, email, full_name)
                        VALUES (NULL, :email, :fullName)
                        RETURNING id
                        """)
                .param("email", email)
                .param("fullName", fullName)
                .query(UUID.class)
                .single();
    }

    private UUID adminRoleId() {
        return jdbcClient.sql("SELECT id FROM tms.role WHERE code = :code AND active")
                .param("code", ADMIN_ROLE_CODE)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new TenantOnboardingRefusedException(Reason.ADMIN_ROLE_MISSING,
                        "The " + ADMIN_ROLE_CODE + " role is missing from this deployment's role catalogue."));
    }
}
