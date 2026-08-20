package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Proves that tenant isolation survives a repository query that forgets its {@code WHERE}
 * clause: with the runtime role active, PostgreSQL itself refuses rows from another company
 * (ADR-005, migration V13).
 *
 * <p>The test speaks SQL directly rather than going through the API on purpose. The API path
 * is already covered by the vertical slices; what is under test here is the *second* line of
 * defense, and it is only a control if it holds for a query the backend never wrote.
 *
 * <p>Each test switches role exactly as {@code TenantScopedDataSource} does at runtime:
 * {@code SET ROLE tms_app} plus the {@code tms.company_id} setting. The seed is written first,
 * as the schema owner, which is also the proof that Flyway and the other integration tests are
 * untouched by the policies.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class TenantRlsIsolationIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static final UUID ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID ZONE_A = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID ZONE_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static String jdbcUrl;

    private Connection connection;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_tenant_rls");
        try (Connection owner = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = owner.createStatement()) {
            statement.execute("""
                    INSERT INTO tms.organization (id, code, name)
                    VALUES ('%s', 'ISOLATION', 'Isolation test organization')
                    """.formatted(ORGANIZATION));
            statement.execute("""
                    INSERT INTO tms.company (id, organization_id, code, name) VALUES
                        ('%s', '%s', 'COMPANY-A', 'Company A'),
                        ('%s', '%s', 'COMPANY-B', 'Company B')
                    """.formatted(COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION));
            statement.execute("""
                    INSERT INTO tms.zone (id, company_id, code, name) VALUES
                        ('%s', '%s', 'ZONE-A', 'Zone of company A'),
                        ('%s', '%s', 'ZONE-B', 'Zone of company B')
                    """.formatted(ZONE_A, COMPANY_A, ZONE_B, COMPANY_B));
        }
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
    @DisplayName("the seed is visible to the schema owner: Flyway and the other tests are unaffected")
    void theOwnerStillSeesEverything() throws SQLException {
        assertThat(zoneCodes())
                .as("forcing RLS on the owner was rejected precisely so this keeps working")
                .containsExactly("ZONE-A", "ZONE-B");
    }

    @Test
    @DisplayName("an unfiltered SELECT returns only the scoped company's rows")
    void readsAreFilteredToTheScopedCompany() throws SQLException {
        actAs(COMPANY_A);
        assertThat(zoneCodes()).containsExactly("ZONE-A");

        resetRole();
        actAs(COMPANY_B);
        assertThat(zoneCodes()).containsExactly("ZONE-B");
    }

    @Test
    @DisplayName("a transaction with no company selected reads nothing: it fails closed")
    void anUnscopedTransactionReadsNothing() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE tms_app");
        }
        assertThat(zoneCodes())
                .as("an unset tms.company_id must deny, never fall back to 'all companies'")
                .isEmpty();
    }

    @Test
    @DisplayName("a row of another company cannot be updated, even without a WHERE on company")
    void writesCannotReachAnotherCompany() throws SQLException {
        actAs(COMPANY_A);
        try (Statement statement = connection.createStatement()) {
            int updated = statement.executeUpdate(
                    "UPDATE tms.zone SET name = 'hijacked' WHERE code = 'ZONE-B'");
            assertThat(updated)
                    .as("company B's row is not visible to company A, so it cannot be updated")
                    .isZero();

            int deleted = statement.executeUpdate("DELETE FROM tms.zone WHERE code = 'ZONE-B'");
            assertThat(deleted).isZero();
        }
    }

    @Test
    @DisplayName("a row cannot be inserted into another company: WITH CHECK refuses it")
    void insertsCannotTargetAnotherCompany() throws SQLException {
        actAs(COMPANY_A);
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tms.zone (company_id, code, name)
                        VALUES ('%s', 'SMUGGLED', 'Written into company B')
                        """.formatted(COMPANY_B));
            }
        });

        // Cast: SQLException is itself an Iterable<Throwable>, so the assertThat overload
        // would otherwise be ambiguous.
        assertThat((Throwable) refusal)
                .as("a USING-only policy would have allowed this write and merely hidden it")
                .isNotNull();
        assertThat(refusal.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    /** Reproduces what {@code TenantScopedDataSource} does for a company-scoped request. */
    private void actAs(UUID companyId) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('tms.company_id', '" + companyId + "', false)");
            statement.execute("SET ROLE tms_app");
        }
    }

    private void resetRole() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET ROLE");
        }
    }

    private List<String> zoneCodes() throws SQLException {
        List<String> codes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT code FROM tms.zone ORDER BY code")) {
            while (rows.next()) {
                codes.add(rows.getString(1));
            }
        }
        return codes;
    }
}
