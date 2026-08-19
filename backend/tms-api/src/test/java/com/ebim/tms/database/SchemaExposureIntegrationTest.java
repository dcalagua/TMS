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
 * Proves the V1 exposure decision documented in {@code docs/security/RLS_STRATEGY.md}:
 * the {@code tms} schema is backend-only, nobody but its owner holds a privilege on it, and
 * Row Level Security is enabled on every table with no policy, so any accidental exposure
 * denies instead of leaking.
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
            "role_permission", "membership", "membership_role", "origin", "zone");

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
    @DisplayName("no RLS policy exists: V1 is a documented deny-all, not a permissive placeholder")
    void thereAreNoPolicies() throws SQLException {
        assertThat(strings("SELECT policyname FROM pg_policies WHERE schemaname = 'tms' ORDER BY 1"))
                .as("a policy would claim an access path that V1 does not open; "
                        + "add one only together with an ADR and its tests")
                .isEmpty();
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
                                     'origin','zone')
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
