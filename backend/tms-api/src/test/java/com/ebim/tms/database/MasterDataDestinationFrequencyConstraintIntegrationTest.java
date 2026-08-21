package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Proves the V7 master data constraints hold at the database level, independent of the Java
 * validation in {@code DestinationService}/{@code FrequencyService} - the same defense-in-depth
 * proof {@link MasterDataConstraintIntegrationTest} gives V6's origin/zone tables, extended to
 * destinations, frequencies, weekly rules and date exceptions.
 *
 * <p>Each test runs in a transaction that is rolled back, matching {@code
 * MasterDataConstraintIntegrationTest}.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class MasterDataDestinationFrequencyConstraintIntegrationTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String NOT_NULL_VIOLATION = "23502";

    private static String jdbcUrl;

    private Connection connection;

    @BeforeAll
    static void migrate() {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_masterdata_dest_freq_constraints");
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
    @DisplayName("destination and frequency codes are unique per company, and free to repeat across companies")
    void codesAreScopedToTheirCompany() throws SQLException {
        UUID organization = insertOrganization("MDF-ORG");
        UUID companyA = insertCompany(organization, "MDF-A");
        UUID companyB = insertCompany(organization, "MDF-B");

        insertDestination(companyA, "NORTH-STORE", null);
        insertDestination(companyB, "NORTH-STORE", null); // same code, different company: allowed
        assertViolates(UNIQUE_VIOLATION, () -> insertDestination(companyA, "NORTH-STORE", null));

        insertFrequency(companyA, "WEEKLY-A");
        insertFrequency(companyB, "WEEKLY-A");
        assertViolates(UNIQUE_VIOLATION, () -> insertFrequency(companyA, "WEEKLY-A"));
    }

    @Test
    @DisplayName("a destination cannot belong to a company that does not exist")
    void destinationRequiresARealCompany() {
        assertViolates(FOREIGN_KEY_VIOLATION,
                () -> execute("INSERT INTO tms.destination (company_id, code, name, country)"
                        + " VALUES ('" + UUID.randomUUID() + "', 'ORPHAN', 'Orphan', 'PE')"));
    }

    @Test
    @DisplayName("codes must already be normalized: upper case, trimmed, no stray characters")
    void codesMustBeNormalized() throws SQLException {
        UUID organization = insertOrganization("MDF-ORG");
        UUID company = insertCompany(organization, "MDF-A");

        assertViolates(CHECK_VIOLATION, () -> insertDestination(company, "lower-case", null));
        assertViolates(CHECK_VIOLATION, () -> insertDestination(company, "HAS SPACE", null));
        assertViolates(CHECK_VIOLATION, () -> insertFrequency(company, "lower-case"));
    }

    @Test
    @DisplayName("a destination coordinate pair must be complete and within range")
    void coordinatesAreValidated() throws SQLException {
        UUID organization = insertOrganization("MDF-ORG");
        UUID company = insertCompany(organization, "MDF-A");

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.destination"
                + " (company_id, code, name, country, latitude) VALUES ('" + company
                + "', 'PARTIAL', 'Partial', 'PE', 10.5)"));

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.destination"
                + " (company_id, code, name, country, latitude, longitude) VALUES ('" + company
                + "', 'BAD-LAT', 'Bad latitude', 'PE', 91, 0)"));
        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.destination"
                + " (company_id, code, name, country, latitude, longitude) VALUES ('" + company
                + "', 'BAD-LNG', 'Bad longitude', 'PE', 0, 181)"));

        execute("INSERT INTO tms.destination (company_id, code, name, country, latitude, longitude)"
                + " VALUES ('" + company + "', 'GOOD', 'Good', 'PE', -12.046374, -77.042793)");
        assertThat(count("SELECT count(*) FROM tms.destination WHERE code = 'GOOD' AND location IS NOT NULL"
                + " AND ST_Y(location::geometry) = -12.046374 AND ST_X(location::geometry) = -77.042793"))
                .isOne();

        execute("INSERT INTO tms.destination (company_id, code, name, country) VALUES ('"
                + company + "', 'NO-COORDS', 'No coordinates', 'PE')");
        assertThat(count("SELECT count(*) FROM tms.destination WHERE code = 'NO-COORDS' AND location IS NULL")).isOne();
    }

    @Test
    @DisplayName("destination_type only accepts the catalogued categories, and service time cannot be negative")
    void destinationTypeAndServiceTimeAreRestricted() throws SQLException {
        UUID organization = insertOrganization("MDF-ORG");
        UUID company = insertCompany(organization, "MDF-A");

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.destination"
                + " (company_id, code, name, country, destination_type) VALUES ('" + company
                + "', 'BAD-TYPE', 'Bad type', 'PE', 'SPACESHIP')"));

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.destination"
                + " (company_id, code, name, country, service_time_minutes) VALUES ('" + company
                + "', 'BAD-SERVICE-TIME', 'Bad service time', 'PE', -5)"));

        execute("INSERT INTO tms.destination (company_id, code, name, country, destination_type) VALUES ('"
                + company + "', 'A-STORE', 'A store', 'PE', 'STORE')");
        assertThat(count("SELECT count(*) FROM tms.destination WHERE code = 'A-STORE' AND destination_type = 'STORE'"))
                .isOne();
    }

    @Test
    @DisplayName("a destination's zone must belong to the same company, even though the FK columns are separate")
    void zoneMustBelongToTheSameCompany() throws SQLException {
        UUID organization = insertOrganization("MDF-ORG");
        UUID companyA = insertCompany(organization, "MDF-A");
        UUID companyB = insertCompany(organization, "MDF-B");
        UUID zoneInB = insertZone(companyB, "ZONE-B");

        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("INSERT INTO tms.destination"
                + " (company_id, code, name, country, zone_id) VALUES ('" + companyA
                + "', 'CROSS-ZONE', 'Cross zone', 'PE', '" + zoneInB + "')"));

        UUID zoneInA = insertZone(companyA, "ZONE-A");
        execute("INSERT INTO tms.destination (company_id, code, name, country, zone_id) VALUES ('"
                + companyA + "', 'SAME-COMPANY-ZONE', 'Same company zone', 'PE', '" + zoneInA + "')");
        assertThat(count("SELECT count(*) FROM tms.destination WHERE code = 'SAME-COMPANY-ZONE' AND zone_id = '"
                + zoneInA + "'")).isOne();
    }

    @Test
    @DisplayName("a weekly rule's day of week is restricted to 1-7, unique per frequency, and lead time cannot be negative")
    void weeklyRuleConstraintsHold() throws SQLException {
        UUID organization = insertOrganization("MDF-ORG");
        UUID company = insertCompany(organization, "MDF-A");
        UUID frequency = insertFrequency(company, "WEEKLY");

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.frequency_weekly_rule"
                + " (frequency_id, day_of_week) VALUES ('" + frequency + "', 0)"));
        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.frequency_weekly_rule"
                + " (frequency_id, day_of_week) VALUES ('" + frequency + "', 8)"));
        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.frequency_weekly_rule"
                + " (frequency_id, day_of_week, lead_time_days) VALUES ('" + frequency + "', 1, -1)"));

        execute("INSERT INTO tms.frequency_weekly_rule (frequency_id, day_of_week) VALUES ('" + frequency + "', 1)");
        assertViolates(UNIQUE_VIOLATION, () -> execute(
                "INSERT INTO tms.frequency_weekly_rule (frequency_id, day_of_week) VALUES ('" + frequency + "', 1)"));
    }

    @Test
    @DisplayName("weekly rules and exceptions are deleted when their frequency is deleted (cascade, no orphans)")
    void weeklyRulesAndExceptionsCascadeFromFrequency() throws SQLException {
        UUID organization = insertOrganization("MDF-ORG");
        UUID company = insertCompany(organization, "MDF-A");
        UUID frequency = insertFrequency(company, "CASCADED");
        execute("INSERT INTO tms.frequency_weekly_rule (frequency_id, day_of_week) VALUES ('" + frequency + "', 3)");
        execute("INSERT INTO tms.frequency_exception (frequency_id, exception_date, service_override)"
                + " VALUES ('" + frequency + "', '2026-12-25', false)");

        execute("DELETE FROM tms.frequency WHERE id = '" + frequency + "'");

        assertThat(count("SELECT count(*) FROM tms.frequency_weekly_rule WHERE frequency_id = '" + frequency + "'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM tms.frequency_exception WHERE frequency_id = '" + frequency + "'"))
                .isZero();
    }

    @Test
    @DisplayName("a frequency exception date is unique per frequency, and the note cannot be blank")
    void exceptionConstraintsHold() throws SQLException {
        UUID organization = insertOrganization("MDF-ORG");
        UUID company = insertCompany(organization, "MDF-A");
        UUID frequency = insertFrequency(company, "EXCEPTIONS");

        execute("INSERT INTO tms.frequency_exception (frequency_id, exception_date, service_override)"
                + " VALUES ('" + frequency + "', '2026-01-01', false)");
        assertViolates(UNIQUE_VIOLATION, () -> execute("INSERT INTO tms.frequency_exception"
                + " (frequency_id, exception_date, service_override) VALUES ('" + frequency
                + "', '2026-01-01', true)"));
        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.frequency_exception"
                + " (frequency_id, exception_date, service_override, note) VALUES ('" + frequency
                + "', '2026-02-01', true, '   ')"));
        assertViolates(NOT_NULL_VIOLATION, () -> execute("INSERT INTO tms.frequency_exception"
                + " (frequency_id, exception_date) VALUES ('" + frequency + "', '2026-03-01')"));
    }

    @Test
    @DisplayName("only an open exception date may carry a cutoff override (V24)")
    void exceptionCutoffOverrideRequiresService() throws SQLException {
        UUID organization = insertOrganization("MDF-ORG");
        UUID company = insertCompany(organization, "MDF-A");
        UUID frequency = insertFrequency(company, "CUTOFFS");

        // Open, closing early - the case the column exists for.
        execute("INSERT INTO tms.frequency_exception"
                + " (frequency_id, exception_date, service_override, cutoff_time_override) VALUES ('" + frequency
                + "', '2026-12-24', true, '11:00')");
        // Open with no opinion about the cutoff, and closed with none - both ordinary.
        execute("INSERT INTO tms.frequency_exception (frequency_id, exception_date, service_override)"
                + " VALUES ('" + frequency + "', '2026-12-19', true)");
        execute("INSERT INTO tms.frequency_exception (frequency_id, exception_date, service_override)"
                + " VALUES ('" + frequency + "', '2026-12-25', false)");

        // A blackout has no cutoff: nothing is dispatched, so there is no last moment to order.
        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.frequency_exception"
                + " (frequency_id, exception_date, service_override, cutoff_time_override) VALUES ('" + frequency
                + "', '2026-12-26', false, '11:00')"));
        // And it cannot be reached by toggling an existing open date closed either.
        assertViolates(CHECK_VIOLATION, () -> execute("UPDATE tms.frequency_exception SET service_override = false"
                + " WHERE frequency_id = '" + frequency + "' AND exception_date = '2026-12-24'"));
    }

    @Test
    @DisplayName("destinations and frequencies default to active and record who changed them")
    void defaultsAndActorColumns() throws SQLException {
        UUID organization = insertOrganization("MDF-ORG");
        UUID company = insertCompany(organization, "MDF-A");
        UUID actor = insertAppUser("mdf.actor@example.test");

        UUID destinationId = insertDestination(company, "DEFAULTS", null);
        assertThat(count("SELECT count(*) FROM tms.destination WHERE id = '" + destinationId + "' AND active"
                + " AND destination_type = 'CUSTOMER' AND service_time_minutes = 0 AND country = 'PE'")).isOne();

        execute("UPDATE tms.destination SET active = false, updated_by = '" + actor + "' WHERE id = '"
                + destinationId + "'");
        assertThat(count("SELECT count(*) FROM tms.destination WHERE id = '" + destinationId
                + "' AND NOT active AND updated_by = '" + actor + "'")).isOne();

        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("UPDATE tms.destination SET updated_by = '"
                + UUID.randomUUID() + "' WHERE id = '" + destinationId + "'"));

        UUID frequencyId = insertFrequency(company, "DEFAULTS-FREQ");
        assertThat(count("SELECT count(*) FROM tms.frequency WHERE id = '" + frequencyId + "' AND active")).isOne();
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

    private UUID insertZone(UUID companyId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.zone (company_id, code, name) VALUES ('" + companyId + "', '"
                + code + "', '" + code + " name') RETURNING id");
    }

    private UUID insertDestination(UUID companyId, String code, UUID zoneId) throws SQLException {
        String zoneColumn = zoneId == null ? "" : ", zone_id";
        String zoneValue = zoneId == null ? "" : ", '" + zoneId + "'";
        return insertReturningId("INSERT INTO tms.destination (company_id, code, name, country" + zoneColumn
                + ") VALUES ('" + companyId + "', '" + code + "', '" + code + " name', 'PE'" + zoneValue
                + ") RETURNING id");
    }

    private UUID insertFrequency(UUID companyId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.frequency (company_id, code, name) VALUES ('" + companyId + "', '"
                + code + "', '" + code + " name') RETURNING id");
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
