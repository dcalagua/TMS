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
 * The second line of defense for the inbound integration API (migration V18): what PostgreSQL
 * itself refuses, independently of anything the backend does.
 *
 * <p>{@code IntegrationApiTenancyTest} proves the application never asks for another company's
 * data. This proves it could not get it if it did. The two are deliberately separate: an
 * application-level guarantee is only worth what its weakest query is, and the point of ADR-005 is
 * that a query which forgets its predicate stops being a cross-tenant leak.
 *
 * <p>Like {@code TenantRlsIsolationIntegrationTest}, this speaks SQL directly. A test that went
 * through the repositories would be testing the repositories; what is under test here is the
 * database's own answer to statements the backend would never write.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class IntegrationTenancyIsolationIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";

    private static final UUID ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
    private static final UUID COMPANY_A = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID COMPANY_B = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID CLIENT_A = UUID.fromString("00000000-0000-0000-0000-0000000000fa");
    private static final UUID CLIENT_B = UUID.fromString("00000000-0000-0000-0000-0000000000fb");

    /** A syntactically valid SHA-256 hex digest; never a real secret, and never a real hash of one. */
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private static String jdbcUrl;

    private Connection connection;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_integration_tenancy");
        try (Connection owner = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = owner.createStatement()) {
            statement.execute("""
                    INSERT INTO tms.organization (id, code, name)
                    VALUES ('%s', 'INTEG', 'Integration isolation organization')
                    """.formatted(ORGANIZATION));
            statement.execute("""
                    INSERT INTO tms.company (id, organization_id, code, name) VALUES
                        ('%s', '%s', 'INTEG-A', 'Company A'),
                        ('%s', '%s', 'INTEG-B', 'Company B')
                    """.formatted(COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION));
            statement.execute("""
                    INSERT INTO tms.integration_client
                        (id, company_id, client_id, name, secret_hash) VALUES
                        ('%s', '%s', 'tmsc_%s', 'Partner of A', '%s'),
                        ('%s', '%s', 'tmsc_%s', 'Partner of B', '%s')
                    """.formatted(
                            CLIENT_A, COMPANY_A, "A".repeat(22), HASH_A,
                            CLIENT_B, COMPANY_B, "B".repeat(22), HASH_B));
            statement.execute("""
                    INSERT INTO tms.integration_client_scope (integration_client_id, scope) VALUES
                        ('%s', 'integration.order:write'),
                        ('%s', 'integration.order:write')
                    """.formatted(CLIENT_A, CLIENT_B));
            statement.execute("""
                    INSERT INTO tms.integration_request
                        (company_id, integration_client_id, operation, payload_hash, status, http_status,
                         external_system, external_reference) VALUES
                        ('%s', '%s', 'order.upsert', '%s', 'SUCCEEDED', 201, 'ERP-A', 'SO-A-1'),
                        ('%s', '%s', 'order.upsert', '%s', 'SUCCEEDED', 201, 'ERP-B', 'SO-B-1')
                    """.formatted(COMPANY_A, CLIENT_A, HASH_A, COMPANY_B, CLIENT_B, HASH_B));
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

    // -----------------------------------------------------------------
    // Row Level Security
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a credential list shows only the scoped company's credentials")
    void credentialsAreFilteredByTenant() throws SQLException {
        actAs(COMPANY_A);
        assertThat(query("SELECT name FROM tms.integration_client ORDER BY name"))
                .containsExactly("Partner of A");

        resetRole();
        actAs(COMPANY_B);
        assertThat(query("SELECT name FROM tms.integration_client ORDER BY name"))
                .containsExactly("Partner of B");
    }

    @Test
    @DisplayName("the inbox shows only the scoped company's deliveries")
    void inboxIsFilteredByTenant() throws SQLException {
        actAs(COMPANY_A);
        assertThat(query("SELECT external_reference FROM tms.integration_request ORDER BY external_reference"))
                .containsExactly("SO-A-1");

        resetRole();
        actAs(COMPANY_B);
        assertThat(query("SELECT external_reference FROM tms.integration_request ORDER BY external_reference"))
                .containsExactly("SO-B-1");
    }

    @Test
    @DisplayName("scopes are reachable exactly when their credential is")
    void scopesInheritTheirParentsTenant() throws SQLException {
        actAs(COMPANY_A);
        assertThat(query("SELECT scope FROM tms.integration_client_scope")).hasSize(1);
    }

    @Test
    @DisplayName("a transaction with no company selected reads no credential at all: it fails closed")
    void anUnscopedTransactionReadsNothing() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE tms_app");
        }
        assertThat(query("SELECT name FROM tms.integration_client"))
                .as("an unset tms.company_id must deny, never fall back to 'all companies'")
                .isEmpty();
        assertThat(query("SELECT external_reference FROM tms.integration_request")).isEmpty();
    }

    @Test
    @DisplayName("another company's credential cannot be revoked, even without a company predicate")
    void credentialsOfAnotherCompanyCannotBeWritten() throws SQLException {
        actAs(COMPANY_A);
        try (Statement statement = connection.createStatement()) {
            int updated = statement.executeUpdate(
                    "UPDATE tms.integration_client SET active = false WHERE name = 'Partner of B'");
            assertThat(updated)
                    .as("company B's credential is not visible to company A, so it cannot be touched")
                    .isZero();
        }
    }

    @Test
    @DisplayName("a credential cannot be created inside another company: WITH CHECK refuses it")
    void credentialsCannotBeCreatedInAnotherCompany() throws SQLException {
        actAs(COMPANY_A);
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tms.integration_client (company_id, client_id, name, secret_hash)
                        VALUES ('%s', 'tmsc_%s', 'Smuggled into B', '%s')
                        """.formatted(COMPANY_B, "C".repeat(22), HASH_A));
            }
        });

        // Cast: SQLException is itself an Iterable<Throwable>, so the assertThat overload
        // would otherwise be ambiguous.
        assertThat((Throwable) refusal)
                .as("a USING-only policy would have allowed this write and merely hidden it")
                .isNotNull();
        assertThat(refusal.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    @DisplayName("an inbox row cannot be written into another company either")
    void inboxRowsCannotBeWrittenIntoAnotherCompany() throws SQLException {
        actAs(COMPANY_A);
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tms.integration_request
                            (company_id, integration_client_id, operation, payload_hash, status, http_status)
                        VALUES ('%s', '%s', 'order.upsert', '%s', 'SUCCEEDED', 201)
                        """.formatted(COMPANY_B, CLIENT_B, HASH_A));
            }
        });

        assertThat((Throwable) refusal).isNotNull();
        assertThat(refusal.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    // -----------------------------------------------------------------
    // Structural guarantees, independent of RLS
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an inbox row can never name a credential of a different company")
    void theCompositeForeignKeyForbidsAMismatchedPair() throws SQLException {
        // As the schema owner: RLS is not what is under test here, the composite foreign key is.
        // It is the guarantee that survives even a bug in the tenant plumbing.
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tms.integration_request
                            (company_id, integration_client_id, operation, payload_hash, status, http_status)
                        VALUES ('%s', '%s', 'order.upsert', '%s', 'SUCCEEDED', 201)
                        """.formatted(COMPANY_A, CLIENT_B, HASH_A));
            }
        });

        assertThat((Throwable) refusal)
                .as("company A's inbox must not be able to reference company B's credential")
                .isNotNull();
        assertThat(refusal.getSQLState()).isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    @Test
    @DisplayName("an idempotency key is unique per company, credential and operation")
    void idempotencyKeysAreUniquePerCredentialAndOperation() throws SQLException {
        insertKeyed(COMPANY_A, CLIENT_A, "order.upsert", "shared-key-value");

        SQLException refusal = catchThrowableOfType(SQLException.class,
                () -> insertKeyed(COMPANY_A, CLIENT_A, "order.upsert", "shared-key-value"));

        assertThat((Throwable) refusal).isNotNull();
        assertThat(refusal.getSQLState()).isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("the same key is free to be used by another company, another credential or another operation")
    void keysDoNotCollideAcrossTenantsOrOperations() throws SQLException {
        insertKeyed(COMPANY_A, CLIENT_A, "order.upsert", "shared-key-value");

        // A partner's own correlation id must not be constrained by what an unrelated tenant sent.
        insertKeyed(COMPANY_B, CLIENT_B, "order.upsert", "shared-key-value");
        insertKeyed(COMPANY_A, CLIENT_A, "location.upsert", "shared-key-value");

        assertThat(query("SELECT idempotency_key FROM tms.integration_request WHERE idempotency_key IS NOT NULL"))
                .hasSize(3);
    }

    @Test
    @DisplayName("deliveries without a key are not forced through the unique index")
    void keylessDeliveriesDoNotCollide() throws SQLException {
        insertKeyed(COMPANY_A, CLIENT_A, "order.upsert", null);
        insertKeyed(COMPANY_A, CLIENT_A, "order.upsert", null);

        assertThat(query("""
                SELECT external_reference FROM tms.integration_request
                WHERE company_id = '%s' AND idempotency_key IS NULL
                """.formatted(COMPANY_A)))
                .as("the partial index exists precisely so keyless deliveries stay independent")
                .hasSize(3);
    }

    @Test
    @DisplayName("a revoked credential cannot also be active")
    void revocationIsTerminal() throws SQLException {
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        UPDATE tms.integration_client SET revoked_at = now(), active = true WHERE id = '%s'
                        """.formatted(CLIENT_A));
            }
        });

        assertThat((Throwable) refusal).isNotNull();
        assertThat(refusal.getSQLState()).isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("a secret hash of the wrong shape is refused rather than stored")
    void onlyRealDigestsAreStored() throws SQLException {
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tms.integration_client (company_id, client_id, name, secret_hash)
                        VALUES ('%s', 'tmsc_%s', 'Plaintext by mistake', 'tmss_a_plaintext_secret')
                        """.formatted(COMPANY_A, "D".repeat(22)));
            }
        });

        assertThat((Throwable) refusal)
                .as("storing anything but a lower-case hex digest would mean the hashing code is broken")
                .isNotNull();
        assertThat(refusal.getSQLState()).isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("a rotation cannot leave the superseded secret equal to the new one")
    void rotationMustActuallyChangeTheSecret() throws SQLException {
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        UPDATE tms.integration_client
                        SET previous_secret_hash = secret_hash, previous_secret_expires_at = now() + interval '7 days'
                        WHERE id = '%s'
                        """.formatted(CLIENT_A));
            }
        });

        assertThat((Throwable) refusal)
                .as("a 'rotation' the old secret still opens is not a rotation")
                .isNotNull();
        assertThat(refusal.getSQLState()).isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("a scope outside the closed vocabulary is refused")
    void scopeVocabularyIsClosed() throws SQLException {
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tms.integration_client_scope (integration_client_id, scope)
                        VALUES ('%s', 'iam.user:manage')
                        """.formatted(CLIENT_A));
            }
        });

        assertThat((Throwable) refusal)
                .as("granting a machine a user permission must not be possible by writing a string")
                .isNotNull();
        assertThat(refusal.getSQLState()).isEqualTo(CHECK_VIOLATION);
    }

    // -----------------------------------------------------------------
    // Authorization catalogue
    // -----------------------------------------------------------------

    @Test
    @DisplayName("only the two administrator roles may manage integration credentials")
    void credentialManagementIsAdministratorOnly() throws SQLException {
        assertThat(query("""
                SELECT r.code
                FROM tms.role r
                JOIN tms.role_permission rp ON rp.role_id = r.id
                JOIN tms.permission p ON p.id = rp.permission_id
                WHERE p.code = 'integration.client:manage'
                ORDER BY r.code
                """))
                .as("a credential list is an inventory of who can write into the tenant")
                .containsExactly("COMPANY_ADMIN", "ORGANIZATION_ADMIN");
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private void insertKeyed(UUID companyId, UUID clientId, String operation, String key) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO tms.integration_request
                        (company_id, integration_client_id, operation, idempotency_key, payload_hash,
                         status, http_status, external_reference)
                    VALUES ('%s', '%s', '%s', %s, '%s', 'SUCCEEDED', 201, 'REF-%s')
                    """.formatted(companyId, clientId, operation,
                            key == null ? "NULL" : "'" + key + "'", HASH_A, UUID.randomUUID()));
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

    private List<String> query(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
