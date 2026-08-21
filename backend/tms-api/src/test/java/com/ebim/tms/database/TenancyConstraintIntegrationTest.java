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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Proves the tenancy invariants are enforced by the database, not only by Java.
 *
 * <p>These are the guarantees the backend leans on: a company belongs to exactly one
 * organization, a membership can never bridge two organizations, a user cannot hold two
 * memberships for the same scope, and nothing that carries history can be deleted away.
 *
 * <p>Each test runs in a transaction that is rolled back, so the migrated database stays
 * pristine between tests.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class TenancyConstraintIntegrationTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";

    private static String jdbcUrl;

    private Connection connection;

    @BeforeAll
    static void migrate() {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_tenancy");
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

    @AfterAll
    static void forgetUrl() {
        jdbcUrl = null;
    }

    @Test
    @DisplayName("company codes are unique inside an organization and free across organizations")
    void companyCodeIsScopedToItsOrganization() throws SQLException {
        UUID first = insertOrganization("ORG-A");
        UUID second = insertOrganization("ORG-B");

        insertCompany(first, "LOGISTICA");
        insertCompany(second, "LOGISTICA"); // same code, different tenant: allowed

        assertViolates(UNIQUE_VIOLATION, () -> insertCompany(first, "LOGISTICA"));
    }

    @Test
    @DisplayName("a membership cannot point at a company of another organization")
    void membershipCannotBridgeTwoOrganizations() throws SQLException {
        UUID organizationA = insertOrganization("ORG-A");
        UUID organizationB = insertOrganization("ORG-B");
        UUID companyOfB = insertCompany(organizationB, "TRANSPORTES");
        UUID user = insertAppUser("planner@example.test");

        assertViolates(FOREIGN_KEY_VIOLATION, () -> insertMembership(user, organizationA, companyOfB));

        // The same membership is valid when the organization matches the company's own.
        insertMembership(user, organizationB, companyOfB);
    }

    @Test
    @DisplayName("a user holds at most one membership per company and one organization-wide")
    void membershipScopesAreUnique() throws SQLException {
        UUID organization = insertOrganization("ORG-A");
        UUID company = insertCompany(organization, "LOGISTICA");
        UUID user = insertAppUser("planner@example.test");

        insertMembership(user, organization, company);
        assertViolates(UNIQUE_VIOLATION, () -> insertMembership(user, organization, company));

        insertMembership(user, organization, null); // organization-wide scope is a different row
        assertViolates(UNIQUE_VIOLATION, () -> insertMembership(user, organization, null));
    }

    @Test
    @DisplayName("nothing that carries history can be deleted: organizations, companies, users")
    void deletesAreRestrictedWhereHistoryExists() throws SQLException {
        UUID organization = insertOrganization("ORG-A");
        UUID company = insertCompany(organization, "LOGISTICA");
        UUID actor = insertAppUser("admin@example.test");
        UUID member = insertAppUser("planner@example.test");
        insertMembership(member, organization, company);
        execute("UPDATE tms.company SET created_by = '" + actor + "' WHERE id = '" + company + "'");

        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("DELETE FROM tms.organization WHERE id = '" + organization + "'"));
        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("DELETE FROM tms.company WHERE id = '" + company + "'"));
        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("DELETE FROM tms.app_user WHERE id = '" + member + "'"));
        assertViolates(FOREIGN_KEY_VIOLATION, () -> execute("DELETE FROM tms.app_user WHERE id = '" + actor + "'"));
    }

    @Test
    @DisplayName("deleting a membership removes its role links and nothing else")
    void membershipRolesCascadeButRolesSurvive() throws SQLException {
        UUID organization = insertOrganization("ORG-A");
        UUID company = insertCompany(organization, "LOGISTICA");
        UUID user = insertAppUser("planner@example.test");
        UUID membership = insertMembership(user, organization, company);
        execute("INSERT INTO tms.membership_role (membership_id, role_id)"
                + " SELECT '" + membership + "', id FROM tms.role WHERE code = 'PLANNER'");

        execute("DELETE FROM tms.membership WHERE id = '" + membership + "'");

        assertThat(count("SELECT count(*) FROM tms.membership_role WHERE membership_id = '" + membership + "'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM tms.role WHERE code = 'PLANNER'")).isOne();
    }

    @Test
    @DisplayName("app_user email is normalized, unique, and the auth mapping is one-to-one")
    void appUserIdentityRules() throws SQLException {
        insertAppUser("planner@example.test");

        assertViolates(CHECK_VIOLATION, () -> insertAppUser("Planner@Example.test"));
        assertViolates(CHECK_VIOLATION, () -> insertAppUser("not-an-email"));
        assertViolates(UNIQUE_VIOLATION, () -> insertAppUser("planner@example.test"));

        UUID authUserId = UUID.randomUUID();
        execute("INSERT INTO tms.app_user (email, full_name, auth_user_id)"
                + " VALUES ('first@example.test', 'First', '" + authUserId + "')");
        assertViolates(UNIQUE_VIOLATION, () -> execute("INSERT INTO tms.app_user (email, full_name, auth_user_id)"
                + " VALUES ('second@example.test', 'Second', '" + authUserId + "')"));

        // Two profiles may exist without an auth identity yet (invited, not accepted).
        execute("INSERT INTO tms.app_user (email, full_name) VALUES ('invited.a@example.test', 'A')");
        execute("INSERT INTO tms.app_user (email, full_name) VALUES ('invited.b@example.test', 'B')");
    }

    @Test
    @DisplayName("tenant codes must be normalized upper-case codes")
    void tenantCodesAreNormalized() {
        assertViolates(CHECK_VIOLATION, () -> insertOrganization("lower-case"));
        assertViolates(CHECK_VIOLATION, () -> insertOrganization("HAS SPACE"));
    }

    @Test
    @DisplayName("updated_at is stamped by the database even when the writer supplies its own value")
    void updatedAtIsMaintainedByTrigger() throws SQLException {
        UUID organization = insertOrganization("ORG-A");

        execute("UPDATE tms.organization SET name = 'Renamed', updated_at = timestamptz '2000-01-01 00:00:00Z'"
                + " WHERE id = '" + organization + "'");

        // now() is the transaction timestamp, so inside one transaction the correct result is
        // "updated_at was overwritten with the transaction time", not "updated_at moved forward".
        assertThat(count("SELECT count(*) FROM tms.organization WHERE id = '" + organization + "'"
                + " AND updated_at = now() AND updated_at = created_at")).isOne();
    }

    @Test
    @DisplayName("updated_at moves forward when a later transaction changes the row")
    void updatedAtAdvancesBetweenTransactions() throws SQLException {
        String isolated = PostgresTestDatabase.createMigratedDatabase("tms_updated_at");
        try (Connection committing = PostgresTestDatabase.connect(isolated);
                Statement statement = committing.createStatement()) {
            statement.execute("INSERT INTO tms.organization (code, name) VALUES ('ORG-T', 'Trigger test')");

            statement.execute("UPDATE tms.organization SET name = 'Renamed' WHERE code = 'ORG-T'");

            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT count(*) FROM tms.organization WHERE code = 'ORG-T' AND updated_at > created_at")) {
                resultSet.next();
                assertThat(resultSet.getLong(1)).isOne();
            }
        }
    }

    @Test
    @DisplayName("the authorization catalogue is seeded exactly as the migrations declare")
    void referenceDataIsPresent() throws SQLException {
        // V3 seeded 29 permissions; V5 completed the catalogue with planning.plan:read,
        // planning.plan:manage and monitoring.transport:read (Step 03 authorization model); V25
        // added planning.trip:execute, granted to every role except VIEWER (execute is not read).
        assertThat(count("SELECT count(*) FROM tms.role")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM tms.permission")).isEqualTo(33);
        assertThat(count("SELECT count(*) FROM tms.permission WHERE code = resource || ':' || action"))
                .isEqualTo(33);

        assertThat(count("SELECT count(*) FROM tms.role_permission")).isEqualTo(95);
        assertThat(count("SELECT count(*) FROM tms.role_permission rp"
                + " JOIN tms.role r ON r.id = rp.role_id WHERE r.code = 'ORGANIZATION_ADMIN'"))
                .isEqualTo(33);
        assertThat(count("SELECT count(*) FROM tms.role_permission rp"
                + " JOIN tms.role r ON r.id = rp.role_id WHERE r.code = 'COMPANY_ADMIN'"))
                .isEqualTo(32);
        assertThat(count("SELECT count(*) FROM tms.role_permission rp"
                + " JOIN tms.role r ON r.id = rp.role_id WHERE r.code = 'PLANNER'"))
                .isEqualTo(17);
        assertThat(count("SELECT count(*) FROM tms.role_permission rp"
                + " JOIN tms.role r ON r.id = rp.role_id WHERE r.code = 'VIEWER'"))
                .isEqualTo(13);
        assertThat(count("SELECT count(*) FROM tms.role_permission rp"
                + " JOIN tms.role r ON r.id = rp.role_id"
                + " JOIN tms.permission p ON p.id = rp.permission_id"
                + " WHERE r.code = 'COMPANY_ADMIN' AND p.code = 'iam.organization:manage'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM tms.role_permission rp"
                + " JOIN tms.role r ON r.id = rp.role_id"
                + " JOIN tms.permission p ON p.id = rp.permission_id"
                + " WHERE r.code = 'VIEWER' AND p.action <> 'read'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM tms.permission WHERE resource = 'audit.log' AND action = 'manage'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM tms.permission"
                + " WHERE resource = 'monitoring.transport' AND action = 'manage'"))
                .isZero();
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
        return insertReturningId("INSERT INTO tms.app_user (email, full_name) VALUES ('" + email
                + "', 'Test person') RETURNING id");
    }

    private UUID insertMembership(UUID appUserId, UUID organizationId, UUID companyId) throws SQLException {
        return insertReturningId("INSERT INTO tms.membership (app_user_id, organization_id, company_id) VALUES ('"
                + appUserId + "', '" + organizationId + "', "
                + (companyId == null ? "NULL" : "'" + companyId + "'") + ") RETURNING id");
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

    /**
     * Asserts the statement is refused with the expected SQLSTATE and leaves the transaction
     * usable: PostgreSQL aborts a transaction on error, so each expected failure is isolated
     * in a savepoint.
     */
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
