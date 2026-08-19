package com.ebim.tms.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.iam.application.PrincipalResolutionService;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import com.ebim.tms.shared.security.TmsPrincipal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The tenancy rules, proved against a real PostgreSQL with the real migrations applied.
 *
 * <p>The web-layer tests stub identity resolution so they can concentrate on the filter chain.
 * This one does the opposite: it exercises the SQL that decides who may act where, which is the
 * single query the whole authorization model rests on. Both halves are needed - a stub that
 * agrees with a wrong query proves nothing.
 *
 * <p>Every assertion here is an isolation assertion. The fixture deliberately contains data
 * that <em>must not</em> be returned: another organization, a deactivated company, a revoked
 * membership, a deactivated user, and a role attached to the wrong kind of membership.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@ActiveProfiles("test")
class IdentityResolutionIntegrationTest {

    private static final UUID NORTH_PLANNER_AUTH = UUID.fromString("11111111-0000-4000-8000-000000000001");
    private static final UUID NORTH_ADMIN_AUTH = UUID.fromString("11111111-0000-4000-8000-000000000002");
    private static final UUID SOUTH_VIEWER_AUTH = UUID.fromString("11111111-0000-4000-8000-000000000003");
    private static final UUID REVOKED_AUTH = UUID.fromString("11111111-0000-4000-8000-000000000004");
    private static final UUID DEACTIVATED_AUTH = UUID.fromString("11111111-0000-4000-8000-000000000005");
    private static final UUID MISASSIGNED_AUTH = UUID.fromString("11111111-0000-4000-8000-000000000006");
    private static final UUID UNKNOWN_AUTH = UUID.fromString("11111111-0000-4000-8000-0000000000ff");

    private static String jdbcUrl;

    @Autowired
    private PrincipalResolutionService principalResolution;

    @Autowired
    private JdbcClient jdbcClient;

    /**
     * Creates a private, freshly migrated database and seeds the tenant fixture into it.
     *
     * <p>Both happen here rather than in a {@code @BeforeAll}: this callback runs while the
     * application context is being built, which is the first moment the Docker availability
     * check has already passed and the last moment before the beans under test exist.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_identity_resolution");
        seedTenants();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedTenants() {
        // Applied through a plain connection rather than the application's beans: the fixture
        // is test data and must not depend on the code under test to be created correctly.
        execute("""
                INSERT INTO tms.organization (code, name, active) VALUES
                    ('NORTH', 'North Organization', true),
                    ('SOUTH', 'South Organization', true),
                    ('CLOSED', 'Closed Organization', false);

                INSERT INTO tms.company (organization_id, code, name, time_zone, active)
                SELECT o.id, v.code, v.name, 'America/Lima', v.active
                FROM tms.organization o
                JOIN (VALUES
                    ('NORTH', 'NORTH-LIMA',     'North Lima',     true),
                    ('NORTH', 'NORTH-TRUJILLO', 'North Trujillo', true),
                    ('NORTH', 'NORTH-CLOSED',   'North Closed',   false),
                    ('SOUTH', 'SOUTH-AREQUIPA', 'South Arequipa', true),
                    ('CLOSED','CLOSED-ONE',     'Closed One',     true)
                ) AS v(org, code, name, active) ON v.org = o.code;

                INSERT INTO tms.app_user (auth_user_id, email, full_name, active) VALUES
                    ('11111111-0000-4000-8000-000000000001', 'north.planner@example.invalid', 'North Planner', true),
                    ('11111111-0000-4000-8000-000000000002', 'north.admin@example.invalid',   'North Admin',   true),
                    ('11111111-0000-4000-8000-000000000003', 'south.viewer@example.invalid',  'South Viewer',  true),
                    ('11111111-0000-4000-8000-000000000004', 'revoked@example.invalid',       'Revoked User',  true),
                    ('11111111-0000-4000-8000-000000000005', 'deactivated@example.invalid',   'Left Company',  false),
                    ('11111111-0000-4000-8000-000000000006', 'misassigned@example.invalid',   'Misassigned',   true);
                """);

        // Company-scoped memberships.
        membership("north.planner@example.invalid", 'C', "NORTH-LIMA", "PLANNER", true);
        membership("south.viewer@example.invalid", 'C', "SOUTH-AREQUIPA", "VIEWER", true);
        membership("revoked@example.invalid", 'C', "NORTH-LIMA", "PLANNER", false);
        membership("deactivated@example.invalid", 'C', "NORTH-LIMA", "PLANNER", true);
        // An ORGANIZATION-level role attached to a company-scoped membership: a configuration
        // mistake the database cannot reject. It must grant nothing.
        membership("misassigned@example.invalid", 'C', "NORTH-TRUJILLO", "ORGANIZATION_ADMIN", true);
        // Organization-wide membership: no company id, reaches every active company of NORTH.
        membership("north.admin@example.invalid", 'O', "NORTH", "ORGANIZATION_ADMIN", true);
    }

    @Test
    @DisplayName("a company-scoped membership resolves to exactly that company")
    void companyScopedMembershipResolvesToOneCompany() {
        TmsPrincipal principal = resolve(NORTH_PLANNER_AUTH);

        assertThat(principal.companies()).hasSize(1);
        CompanyScope scope = principal.companies().getFirst();
        assertThat(scope.companyCode()).isEqualTo("NORTH-LIMA");
        assertThat(scope.organizationCode()).isEqualTo("NORTH");
        assertThat(scope.permissions())
                .contains(Permission.ORDERS_ORDER_MANAGE, Permission.PLANNING_TRIP_MANAGE,
                        Permission.PLANNING_PLAN_MANAGE, Permission.MONITORING_TRANSPORT_READ)
                .doesNotContain(Permission.IAM_USER_MANAGE, Permission.MASTERDATA_ORIGIN_MANAGE);
    }

    @Test
    @DisplayName("an organization-wide membership reaches every active company of its organization")
    void organizationWideMembershipExpandsToActiveCompanies() {
        TmsPrincipal principal = resolve(NORTH_ADMIN_AUTH);

        assertThat(principal.companies().stream().map(CompanyScope::companyCode))
                .containsExactlyInAnyOrder("NORTH-LIMA", "NORTH-TRUJILLO");
    }

    @Test
    @DisplayName("no membership ever reaches a company of another organization")
    void companiesOfAnotherOrganizationAreNeverReturned() {
        List<String> northCompanies = resolve(NORTH_ADMIN_AUTH).companies().stream()
                .map(CompanyScope::companyCode).toList();
        List<String> southCompanies = resolve(SOUTH_VIEWER_AUTH).companies().stream()
                .map(CompanyScope::companyCode).toList();

        assertThat(northCompanies).doesNotContain("SOUTH-AREQUIPA", "CLOSED-ONE");
        assertThat(southCompanies).containsExactly("SOUTH-AREQUIPA");
    }

    @Test
    @DisplayName("a deactivated company is not reachable, even through an organization-wide membership")
    void deactivatedCompanyIsExcluded() {
        assertThat(resolve(NORTH_ADMIN_AUTH).companies().stream().map(CompanyScope::companyCode))
                .doesNotContain("NORTH-CLOSED");
    }

    @Test
    @DisplayName("a revoked membership grants nothing")
    void revokedMembershipGrantsNothing() {
        assertThat(resolve(REVOKED_AUTH).companies()).isEmpty();
    }

    @Test
    @DisplayName("a deactivated user does not resolve at all, so the request is refused")
    void deactivatedUserDoesNotResolve() {
        assertThat(principalResolution.loadByAuthUserId(DEACTIVATED_AUTH)).isEmpty();
    }

    @Test
    @DisplayName("an unknown Supabase identity does not resolve")
    void unknownIdentityDoesNotResolve() {
        assertThat(principalResolution.loadByAuthUserId(UNKNOWN_AUTH)).isEmpty();
    }

    @Test
    @DisplayName("an organization-level role on a company-scoped membership grants nothing")
    void organizationRoleOnCompanyMembershipGrantsNothing() {
        // The pairing rule ADR-003 and migration V2 leave to Java. Without it, attaching
        // ORGANIZATION_ADMIN to a single-company membership would silently hand that user the
        // whole catalogue inside that company.
        assertThat(resolve(MISASSIGNED_AUTH).companies()).isEmpty();
    }

    @Test
    @DisplayName("the resolved profile is the app_user row, not the auth identity")
    void profileComesFromAppUser() {
        TmsPrincipal principal = resolve(NORTH_PLANNER_AUTH);

        assertThat(principal.email()).isEqualTo("north.planner@example.invalid");
        assertThat(principal.fullName()).isEqualTo("North Planner");
        assertThat(principal.authUserId()).isEqualTo(NORTH_PLANNER_AUTH);
        assertThat(principal.appUserId()).isNotEqualTo(NORTH_PLANNER_AUTH);
    }

    @Test
    @DisplayName("the Java permission enum and the migrated catalogue are the same set")
    void permissionEnumMatchesTheDatabaseCatalogue() {
        List<String> inDatabase = jdbcClient.sql("SELECT code FROM tms.permission ORDER BY code")
                .query(String.class).list();

        assertThat(inDatabase)
                .as("every tms.permission row must have a Permission constant, and vice versa - "
                        + "a drift here means a @PreAuthorize string that can never match")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(Permission.values()).map(Permission::code).toList());
    }

    private TmsPrincipal resolve(UUID authUserId) {
        Optional<TmsPrincipal> principal = principalResolution.loadByAuthUserId(authUserId);
        assertThat(principal).as("principal for auth user %s", authUserId).isPresent();
        return principal.orElseThrow();
    }

    /**
     * @param kind {@code 'O'} for an organization-wide membership (no company), {@code 'C'} for
     *     a company-scoped one; {@code target} is the organization or company code accordingly
     */
    private static void membership(String email, char kind, String target, String roleCode, boolean active) {
        String companySelect = kind == 'O' ? "NULL" : "(SELECT id FROM tms.company WHERE code = '" + target + "')";
        String organizationSelect = kind == 'O'
                ? "(SELECT id FROM tms.organization WHERE code = '" + target + "')"
                : "(SELECT organization_id FROM tms.company WHERE code = '" + target + "')";

        execute("""
                INSERT INTO tms.membership (app_user_id, organization_id, company_id, active)
                VALUES ((SELECT id FROM tms.app_user WHERE email = '%s'), %s, %s, %s);

                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id
                FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = '%s'
                JOIN tms.role r ON r.code = '%s'
                WHERE m.organization_id = %s
                  AND m.company_id IS NOT DISTINCT FROM %s;
                """.formatted(email, organizationSelect, companySelect, active,
                        email, roleCode, organizationSelect, companySelect));
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception failed) {
            throw new IllegalStateException("could not seed the identity fixture", failed);
        }
    }
}
