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
 * Proves the V6 master data constraints hold at the database level, independent of the Java
 * validation in {@code OriginService}/{@code ZoneService} (defense in depth: the checks here
 * must refuse a bad row even if a future writer bypasses the application layer entirely).
 *
 * <p>Each test runs in a transaction that is rolled back, matching {@code
 * TenancyConstraintIntegrationTest}.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class MasterDataConstraintIntegrationTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static String jdbcUrl;

    private Connection connection;

    @BeforeAll
    static void migrate() {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_masterdata_constraints");
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
    @DisplayName("origin and zone codes are unique per company, and free to repeat across companies")
    void codesAreScopedToTheirCompany() throws SQLException {
        UUID organization = insertOrganization("MD-ORG");
        UUID companyA = insertCompany(organization, "MD-A");
        UUID companyB = insertCompany(organization, "MD-B");

        insertOrigin(companyA, "NORTH-HUB");
        insertOrigin(companyB, "NORTH-HUB"); // same code, different company: allowed
        assertViolates(UNIQUE_VIOLATION, () -> insertOrigin(companyA, "NORTH-HUB"));

        insertZone(companyA, "ZONE-1");
        insertZone(companyB, "ZONE-1");
        assertViolates(UNIQUE_VIOLATION, () -> insertZone(companyA, "ZONE-1"));
    }

    @Test
    @DisplayName("an origin cannot belong to a company that does not exist")
    void originRequiresARealCompany() {
        assertViolates(FOREIGN_KEY_VIOLATION,
                () -> execute("INSERT INTO tms.origin (company_id, code, name, time_zone)"
                        + " VALUES ('" + UUID.randomUUID() + "', 'ORPHAN', 'Orphan', 'UTC')"));
    }

    @Test
    @DisplayName("codes must already be normalized: upper case, trimmed, no stray characters")
    void codesMustBeNormalized() throws SQLException {
        UUID organization = insertOrganization("MD-ORG");
        UUID company = insertCompany(organization, "MD-A");

        assertViolates(CHECK_VIOLATION, () -> insertOrigin(company, "lower-case"));
        assertViolates(CHECK_VIOLATION, () -> insertOrigin(company, "HAS SPACE"));
        assertViolates(CHECK_VIOLATION, () -> insertZone(company, "lower-case"));
    }

    @Test
    @DisplayName("a coordinate pair must be complete and within range")
    void coordinatesAreValidated() throws SQLException {
        UUID organization = insertOrganization("MD-ORG");
        UUID company = insertCompany(organization, "MD-A");

        // one coordinate without the other cannot describe a point
        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.origin"
                + " (company_id, code, name, time_zone, latitude) VALUES ('" + company
                + "', 'PARTIAL', 'Partial', 'UTC', 10.5)"));

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.origin"
                + " (company_id, code, name, time_zone, latitude, longitude) VALUES ('" + company
                + "', 'BAD-LAT', 'Bad latitude', 'UTC', 91, 0)"));
        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.origin"
                + " (company_id, code, name, time_zone, latitude, longitude) VALUES ('" + company
                + "', 'BAD-LNG', 'Bad longitude', 'UTC', 0, 181)"));

        // a valid pair is accepted and the generated geography column reflects it
        execute("INSERT INTO tms.origin (company_id, code, name, time_zone, latitude, longitude)"
                + " VALUES ('" + company + "', 'GOOD', 'Good', 'UTC', -12.046374, -77.042793)");
        assertThat(count("SELECT count(*) FROM tms.origin WHERE code = 'GOOD' AND location IS NOT NULL"
                + " AND ST_Y(location::geometry) = -12.046374 AND ST_X(location::geometry) = -77.042793"))
                .isOne();

        // no coordinates at all is a legitimate origin: location is simply absent
        execute("INSERT INTO tms.origin (company_id, code, name, time_zone) VALUES ('"
                + company + "', 'NO-COORDS', 'No coordinates', 'UTC')");
        assertThat(count("SELECT count(*) FROM tms.origin WHERE code = 'NO-COORDS' AND location IS NULL")).isOne();
    }

    @Test
    @DisplayName("origin_type only accepts the catalogued categories")
    void originTypeIsRestricted() throws SQLException {
        UUID organization = insertOrganization("MD-ORG");
        UUID company = insertCompany(organization, "MD-A");

        assertViolates(CHECK_VIOLATION, () -> execute("INSERT INTO tms.origin"
                + " (company_id, code, name, time_zone, origin_type) VALUES ('" + company
                + "', 'BAD-TYPE', 'Bad type', 'UTC', 'SPACESHIP')"));

        execute("INSERT INTO tms.origin (company_id, code, name, time_zone, origin_type) VALUES ('"
                + company + "', 'A-HUB', 'A hub', 'UTC', 'HUB')");
        assertThat(count("SELECT count(*) FROM tms.origin WHERE code = 'A-HUB' AND origin_type = 'HUB'")).isOne();
    }

    @Test
    @DisplayName("both tables default to active and record who changed them")
    void defaultsAndActorColumns() throws SQLException {
        UUID organization = insertOrganization("MD-ORG");
        UUID company = insertCompany(organization, "MD-A");
        UUID actor = insertAppUser("md.actor@example.test");

        UUID originId = insertOrigin(company, "DEFAULTS");
        assertThat(count("SELECT count(*) FROM tms.origin WHERE id = '" + originId + "' AND active"
                + " AND origin_type = 'OTHER' AND time_zone = 'UTC'")).isOne();

        execute("UPDATE tms.origin SET active = false, updated_by = '" + actor + "' WHERE id = '" + originId + "'");
        assertThat(count("SELECT count(*) FROM tms.origin WHERE id = '" + originId
                + "' AND NOT active AND updated_by = '" + actor + "'")).isOne();

        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute(
                "UPDATE tms.origin SET updated_by = '" + UUID.randomUUID() + "' WHERE id = '" + originId + "'"));
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

    private UUID insertOrigin(UUID companyId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.origin (company_id, code, name, time_zone) VALUES ('" + companyId
                + "', '" + code + "', '" + code + " name', 'UTC') RETURNING id");
    }

    private UUID insertZone(UUID companyId, String code) throws SQLException {
        return insertReturningId("INSERT INTO tms.zone (company_id, code, name) VALUES ('" + companyId + "', '"
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
