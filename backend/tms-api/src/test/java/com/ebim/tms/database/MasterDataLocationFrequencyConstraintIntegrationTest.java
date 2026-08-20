package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Proves the V15 {@code tms.location_frequency} constraints hold at the database level,
 * independent of the Java validation in {@code LocationFrequencyService} - the same
 * defense-in-depth proof {@link MasterDataRouteConstraintIntegrationTest} gives V8's route
 * tables, extended to the location/frequency service-calendar association.
 *
 * <p>Each constraint test runs in a transaction that is rolled back, matching
 * {@code MasterDataRouteConstraintIntegrationTest}. The RLS tests follow
 * {@code TenantRlsIsolationIntegrationTest}'s own {@code SET ROLE tms_app} plus
 * {@code tms.company_id} pattern instead, since {@code location_frequency} - unlike
 * {@code tms.frequency_weekly_rule} - carries its own {@code company_id} and is scoped directly
 * by {@code p_tenant_company_scope}, exactly like {@code tms.route}.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class MasterDataLocationFrequencyConstraintIntegrationTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static String jdbcUrl;

    private Connection connection;

    @BeforeAll
    static void migrate() {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_masterdata_location_frequency_constraints");
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
    @DisplayName("effective_to before effective_from is rejected")
    void effectiveToBeforeEffectiveFromIsRejected() throws SQLException {
        UUID organization = insertOrganization("MLF-ORG");
        UUID company = insertCompany(organization, "MLF-A");
        UUID location = insertLocation(company, "LOC-A");
        UUID frequency = insertFrequency(company, "FREQ-A");

        assertViolates(CHECK_VIOLATION, () -> insertLocationFrequency(
                company, location, frequency, "2026-06-01", "2026-01-01"));
    }

    @Test
    @DisplayName("effective_to equal to effective_from is accepted: a valid one-day range")
    void effectiveToEqualToEffectiveFromIsAccepted() throws SQLException {
        UUID organization = insertOrganization("MLF-ORG");
        UUID company = insertCompany(organization, "MLF-A");
        UUID location = insertLocation(company, "LOC-A");
        UUID frequency = insertFrequency(company, "FREQ-A");

        UUID id = insertLocationFrequency(company, location, frequency, "2026-06-01", "2026-06-01");
        assertThat(count("SELECT count(*) FROM tms.location_frequency WHERE id = '" + id + "'")).isOne();
    }

    @Test
    @DisplayName("either bound may be NULL: an unbounded start, end, or both")
    void eitherBoundMayBeNull() throws SQLException {
        UUID organization = insertOrganization("MLF-ORG");
        UUID company = insertCompany(organization, "MLF-A");
        UUID location = insertLocation(company, "LOC-A");
        UUID frequencyOne = insertFrequency(company, "FREQ-A1");
        UUID frequencyTwo = insertFrequency(company, "FREQ-A2");
        UUID frequencyThree = insertFrequency(company, "FREQ-A3");

        insertLocationFrequency(company, location, frequencyOne, null, "2026-12-31");
        insertLocationFrequency(company, location, frequencyTwo, "2026-01-01", null);
        insertLocationFrequency(company, location, frequencyThree, null, null);

        assertThat(count("SELECT count(*) FROM tms.location_frequency WHERE location_id = '" + location + "'"))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a location_id from a different company than company_id is rejected")
    void locationMustBelongToTheSameCompany() throws SQLException {
        UUID organization = insertOrganization("MLF-ORG");
        UUID companyA = insertCompany(organization, "MLF-A");
        UUID companyB = insertCompany(organization, "MLF-B");
        UUID locationInB = insertLocation(companyB, "LOC-B");
        UUID frequencyInA = insertFrequency(companyA, "FREQ-A");

        assertViolates(FOREIGN_KEY_VIOLATION, () -> insertLocationFrequency(
                companyA, locationInB, frequencyInA, null, null));
    }

    @Test
    @DisplayName("a frequency_id from a different company than company_id is rejected")
    void frequencyMustBelongToTheSameCompany() throws SQLException {
        UUID organization = insertOrganization("MLF-ORG");
        UUID companyA = insertCompany(organization, "MLF-A");
        UUID companyB = insertCompany(organization, "MLF-B");
        UUID locationInA = insertLocation(companyA, "LOC-A");
        UUID frequencyInB = insertFrequency(companyB, "FREQ-B");

        assertViolates(FOREIGN_KEY_VIOLATION, () -> insertLocationFrequency(
                companyA, locationInA, frequencyInB, null, null));
    }

    @Test
    @DisplayName("associations default to active and record who changed them")
    void defaultsAndActorColumns() throws SQLException {
        UUID organization = insertOrganization("MLF-ORG");
        UUID company = insertCompany(organization, "MLF-A");
        UUID location = insertLocation(company, "LOC-A");
        UUID frequency = insertFrequency(company, "FREQ-A");
        UUID actor = insertAppUser("mlf.actor@example.test");

        UUID id = insertLocationFrequency(company, location, frequency, null, null);
        assertThat(count("SELECT count(*) FROM tms.location_frequency WHERE id = '" + id + "' AND active")).isOne();

        execute("UPDATE tms.location_frequency SET active = false, updated_by = '" + actor + "' WHERE id = '" + id + "'");
        assertThat(count("SELECT count(*) FROM tms.location_frequency WHERE id = '" + id
                + "' AND NOT active AND updated_by = '" + actor + "'")).isOne();
    }

    // --- RLS (ADR-005) ------------------------------------------------------------

    @Test
    @DisplayName("RLS: a row of another company is invisible to the scoped runtime role")
    void rlsHidesAnotherCompanysRow() throws SQLException {
        UUID organization = insertOrganization("MLF-RLS-ORG");
        UUID companyA = insertCompany(organization, "MLF-RLS-A");
        UUID companyB = insertCompany(organization, "MLF-RLS-B");
        UUID locationA = insertLocation(companyA, "RLS-LOC-A");
        UUID locationB = insertLocation(companyB, "RLS-LOC-B");
        UUID frequencyA = insertFrequency(companyA, "RLS-FREQ-A");
        UUID frequencyB = insertFrequency(companyB, "RLS-FREQ-B");
        insertLocationFrequency(companyA, locationA, frequencyA, null, null);
        insertLocationFrequency(companyB, locationB, frequencyB, null, null);

        actAs(companyA);
        assertThat(locationFrequencyCompanyIds()).containsExactly(companyA.toString());

        resetRole();
        actAs(companyB);
        assertThat(locationFrequencyCompanyIds()).containsExactly(companyB.toString());
    }

    @Test
    @DisplayName("RLS: a transaction with no company selected reads no association rows")
    void rlsUnscopedTransactionReadsNothing() throws SQLException {
        UUID organization = insertOrganization("MLF-RLS-UNSCOPED-ORG");
        UUID company = insertCompany(organization, "MLF-RLS-UNSCOPED");
        UUID location = insertLocation(company, "UNSCOPED-LOC");
        UUID frequency = insertFrequency(company, "UNSCOPED-FREQ");
        insertLocationFrequency(company, location, frequency, null, null);

        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE tms_app");
        }
        assertThat(locationFrequencyCompanyIds())
                .as("an unset tms.company_id must deny, never fall back to 'all companies'")
                .isEmpty();
    }

    @Test
    @DisplayName("RLS: an association cannot be written into another company - WITH CHECK refuses it")
    void rlsInsertsCannotTargetAnotherCompany() throws SQLException {
        UUID organization = insertOrganization("MLF-RLS-INSERT-ORG");
        UUID companyA = insertCompany(organization, "MLF-RLS-INSERT-A");
        UUID companyB = insertCompany(organization, "MLF-RLS-INSERT-B");
        UUID locationB = insertLocation(companyB, "RLS-INSERT-LOC-B");
        UUID frequencyB = insertFrequency(companyB, "RLS-INSERT-FREQ-B");

        actAs(companyA);
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> execute(
                "INSERT INTO tms.location_frequency (company_id, location_id, frequency_id)"
                        + " VALUES ('" + companyB + "', '" + locationB + "', '" + frequencyB + "')"));

        // Cast: SQLException is itself an Iterable<Throwable>, so the assertThat overload would
        // otherwise be ambiguous - the same idiom TenantRlsIsolationIntegrationTest uses.
        assertThat((Throwable) refusal)
                .as("a USING-only policy would have allowed this write and merely hidden it")
                .isNotNull();
        assertThat(refusal.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    // --- helpers -----------------------------------------------------------------

    private UUID insertOrganization(String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.organization (code, name) VALUES ('" + code + "', '" + code
                + " name') RETURNING id");
    }

    private UUID insertCompany(UUID organizationId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.company (organization_id, code, name) VALUES ('" + organizationId
                + "', '" + code + "', '" + code + " name') RETURNING id");
    }

    private UUID insertAppUser(String email) throws SQLException {
        return insertReturningId(
                "INSERT INTO tms.app_user (email, full_name) VALUES ('" + email + "', 'Test person') RETURNING id");
    }

    private UUID insertLocation(UUID companyId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.location (company_id, code, name) VALUES ('" + companyId + "', '"
                + code + "', '" + code + " name') RETURNING id");
    }

    private UUID insertFrequency(UUID companyId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.frequency (company_id, code, name) VALUES ('" + companyId + "', '"
                + code + "', '" + code + " name') RETURNING id");
    }

    private UUID insertLocationFrequency(
            UUID companyId, UUID locationId, UUID frequencyId, String effectiveFrom, String effectiveTo)
            throws SQLException {
        return insertReturningId("INSERT INTO tms.location_frequency"
                + " (company_id, location_id, frequency_id, effective_from, effective_to) VALUES ('" + companyId
                + "', '" + locationId + "', '" + frequencyId + "', " + sqlDate(effectiveFrom) + ", "
                + sqlDate(effectiveTo) + ") RETURNING id");
    }

    private static String sqlDate(String date) {
        return date == null ? "NULL" : "'" + date + "'";
    }

    private UUID insertReturningId(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return UUID.fromString(resultSet.getString(1));
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long count(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
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

    private List<String> locationFrequencyCompanyIds() throws SQLException {
        List<String> companyIds = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT company_id::text FROM tms.location_frequency")) {
            while (rows.next()) {
                companyIds.add(rows.getString(1));
            }
        }
        return companyIds;
    }

    /** See {@code TenancyConstraintIntegrationTest} for why a savepoint is used here. */
    private void assertViolates(String sqlState, ThrowingCallable statement) {
        try {
            Savepoint savepoint = connection.setSavepoint();
            try {
                Throwable thrown = catchThrowable(statement);
                assertThat(thrown).as("the database was expected to refuse the statement").isNotNull();
                assertThat(thrown).isInstanceOf(SQLException.class);
                assertThat(((SQLException) thrown).getSQLState()).isEqualTo(sqlState);
            } finally {
                connection.rollback(savepoint);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("savepoint handling failed", e);
        }
    }
}
