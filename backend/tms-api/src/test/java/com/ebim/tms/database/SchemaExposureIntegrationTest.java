package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Proves the exposure decision documented in {@code docs/security/RLS_STRATEGY.md}: the
 * {@code tms} schema is backend-only, nobody but its owner and the non-owner runtime role
 * holds a privilege on it, and Row Level Security is enabled on every table - with tenant
 * policies on business data (ADR-005) and none at all for the Supabase API roles, so any
 * accidental exposure denies instead of leaking.
 *
 * <p>The Supabase API roles ({@code anon}, {@code authenticated}) do not exist on a plain
 * PostgreSQL container, so the tests create them and then assert that the schema still
 * refuses them. That reproduces the platform situation instead of assuming it.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class SchemaExposureIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static final List<String> APPLICATION_TABLES = List.of(
            "app_user", "organization", "company", "role", "permission",
            "role_permission", "membership", "membership_role", "origin", "zone",
            "destination", "frequency", "frequency_weekly_rule", "frequency_exception",
            "route", "route_stop", "carrier", "vehicle_type", "vehicle",
            "transport_order", "transport_order_line",
            "planning_run", "trip", "trip_stop", "trip_order_assignment");

    /**
     * The tables whose rows belong to a company and are therefore filtered by RLS for the
     * runtime role (ADR-005). Identity and authorization-catalogue tables are excluded on
     * purpose: they are read before a company scope exists, so they carry the explicit
     * {@code p_backend_managed} policy instead.
     */
    private static final List<String> TENANT_SCOPED_TABLES = List.of(
            "origin", "zone", "destination", "frequency", "frequency_weekly_rule",
            "frequency_exception", "route", "route_stop", "carrier", "vehicle_type", "vehicle",
            "transport_order", "transport_order_line",
            "planning_run", "trip", "trip_stop", "trip_order_assignment");

    private static String jdbcUrl;

    private Connection connection;

    @BeforeAll
    static void migrate() {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_exposure");
    }

    @BeforeEach
    void openTransaction() throws SQLException {
        connection = PostgresTestDatabase.connect(jdbcUrl);
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    @Test
    @DisplayName("every application table exists and has Row Level Security enabled")
    void rowLevelSecurityIsEnabledEverywhere() throws SQLException {
        List<String> withoutRls = strings("""
                SELECT c.relname
                FROM pg_class c
                WHERE c.relnamespace = 'tms'::regnamespace
                  AND c.relkind = 'r'
                  AND c.relname <> 'flyway_schema_history'
                  AND NOT c.relrowsecurity
                ORDER BY 1
                """);

        assertThat(withoutRls).as("tables missing ENABLE ROW LEVEL SECURITY").isEmpty();
        assertThat(strings("""
                SELECT c.relname FROM pg_class c
                WHERE c.relnamespace = 'tms'::regnamespace AND c.relkind = 'r' AND c.relrowsecurity
                ORDER BY 1
                """)).containsExactlyInAnyOrderElementsOf(APPLICATION_TABLES);
    }

    @Test
    @DisplayName("the migrating role may actually SET ROLE tms_app, not merely administer it")
    void theRuntimeRoleCanBeEntered() throws SQLException {
        // Since PostgreSQL 16 creating a role grants ADMIN OPTION but not the SET option, so
        // this is not implied by V13 having created tms_app. The distinction is invisible on a
        // superuser connection - and the Testcontainers user is one - which is precisely why it
        // is asserted on the catalogue rather than by attempting SET ROLE and seeing it pass.
        assertThat(bool("""
                SELECT m.set_option
                FROM pg_auth_members m
                JOIN pg_roles r ON r.oid = m.roleid
                JOIN pg_roles g ON g.oid = m.member
                WHERE r.rolname = 'tms_app' AND g.rolname = current_user
                """))
                .as("without the SET option the backend cannot enter the runtime role, and "
                        + "every company-scoped request fails with 'permission denied to set "
                        + "role tms_app' on any cluster where it is not a superuser")
                .isTrue();
    }

    @Test
    @DisplayName("every company-scoped table carries the tenant policy (ADR-005)")
    void businessTablesCarryTheTenantPolicy() throws SQLException {
        assertThat(strings("""
                SELECT tablename FROM pg_policies
                WHERE schemaname = 'tms' AND policyname = 'p_tenant_company_scope'
                ORDER BY 1
                """))
                .as("a company-scoped table without a tenant policy is readable across "
                        + "tenants by the runtime role; add the policy in the migration that "
                        + "creates the table")
                .containsExactlyInAnyOrderElementsOf(TENANT_SCOPED_TABLES);
    }

    @Test
    @DisplayName("no policy is granted to the Supabase API roles: the Data API stays denied")
    void policiesTargetTheRuntimeRoleOnly() throws SQLException {
        assertThat(strings("""
                SELECT DISTINCT unnest(roles)::text FROM pg_policies WHERE schemaname = 'tms'
                ORDER BY 1
                """))
                .as("V13 grants tenant access to the non-owner runtime role only; a policy "
                        + "naming anon/authenticated/service_role - or PUBLIC - would open the "
                        + "Data API path that ADR-004 closes")
                .containsExactly("tms_app");
    }

    @Test
    @DisplayName("RLS is not forced, so the owning application role keeps working by design")
    void rlsIsNotForcedForTheOwner() throws SQLException {
        assertThat(strings("""
                SELECT relname FROM pg_class
                WHERE relnamespace = 'tms'::regnamespace AND relkind = 'r' AND relforcerowsecurity
                ORDER BY 1
                """))
                .as("forcing RLS would lock out the backend connection, whose authorization is "
                        + "Spring Boot's responsibility (architecture section 4.2)")
                .isEmpty();
    }

    @Test
    @DisplayName("PUBLIC holds no privilege on the schema, its tables or its functions")
    void publicHasNothing() throws SQLException {
        assertThat(bool("SELECT has_schema_privilege('public', 'tms', 'USAGE')")).isFalse();
        assertThat(bool("SELECT has_function_privilege('public', 'tms.set_updated_at()', 'EXECUTE')")).isFalse();
        for (String table : APPLICATION_TABLES) {
            assertThat(bool("SELECT has_table_privilege('public', 'tms." + table + "', 'SELECT')"))
                    .as("PUBLIC must not be able to read tms.%s", table)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Supabase API roles cannot reach the schema even if the platform creates them")
    void supabaseApiRolesAreDenied() throws SQLException {
        execute("CREATE ROLE anon NOLOGIN");
        execute("CREATE ROLE authenticated NOLOGIN");

        for (String apiRole : List.of("anon", "authenticated")) {
            assertThat(bool("SELECT has_schema_privilege('" + apiRole + "', 'tms', 'USAGE')"))
                    .as("%s must not have USAGE on schema tms", apiRole)
                    .isFalse();
            assertThat(bool("SELECT has_table_privilege('" + apiRole + "', 'tms.organization', 'SELECT')"))
                    .as("%s must not be able to read tenant data", apiRole)
                    .isFalse();
        }

        // And it is a real refusal at query time, not only a catalogue statement.
        execute("SET LOCAL ROLE anon");
        Throwable denied = catchThrowable(() -> execute("SELECT id FROM tms.organization"));
        assertThat(denied).isInstanceOf(SQLException.class);
        assertThat(((SQLException) denied).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    @DisplayName("application tables live outside public, where the Supabase Data API looks")
    void nothingIsPublishedInThePublicSchema() throws SQLException {
        assertThat(strings("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name IN ('app_user','organization','company','role','permission',
                                     'role_permission','membership','membership_role',
                                     'origin','zone','destination','frequency',
                                     'frequency_weekly_rule','frequency_exception',
                                     'route','route_stop','carrier','vehicle_type','vehicle',
                                     'transport_order','transport_order_line')
                ORDER BY 1
                """)).isEmpty();
    }

    // --- helpers -----------------------------------------------------------------

    private List<String> strings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }

    private boolean bool(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getBoolean(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
