package com.ebim.tms.iam.entitlements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.iam.entitlements.application.ApplyEntitlementSnapshotService;
import com.ebim.tms.iam.entitlements.application.EntitlementApplyWriter;
import com.ebim.tms.iam.entitlements.application.EntitlementQueryService;
import com.ebim.tms.iam.entitlements.application.EntitlementStore.AccessFacts;
import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.application.PlatformEntitlementsProperties;
import com.ebim.tms.iam.entitlements.application.ReceiverProfile;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import com.ebim.tms.iam.entitlements.domain.EntitlementRejection;
import com.ebim.tms.iam.entitlements.infrastructure.JdbcEntitlementRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * V52 and {@link JdbcEntitlementRepository} against a real PostgreSQL with the whole Flyway history:
 * the SQL the in-memory receiver tests cannot prove - the foreign key to V51, the append-only and
 * one-step-mode triggers, the runtime role's lack of access, and the SECURITY DEFINER function that
 * reveals to {@code tms_app} its own organization and no other.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class PlatformEntitlementsIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-4ccc-8000-000000001211");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-4ccc-8000-000000001212");

    private static String jdbcUrl;
    private static JdbcEntitlementRepository store;
    private static TransactionTemplate transactions;
    private static Tenant a;
    private static Tenant b;
    private static final AtomicInteger JTIS = new AtomicInteger();

    record Tenant(UUID controlPlaneTenantId, UUID organizationId, UUID companyId) {}

    @BeforeAll
    static void migrate() throws SQLException {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_entitlements");
        DataSource dataSource = new DriverManagerDataSource(jdbcUrl, PostgresTestDatabase.username(),
                PostgresTestDatabase.password());
        store = new JdbcEntitlementRepository(JdbcClient.create(dataSource), JsonMapper.builder().build());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        a = provision(TENANT_A, "ENTA");
        b = provision(TENANT_B, "ENTB");
    }

    /** The rows V51 provisioning leaves behind, written as the owner the way the provisioner does. */
    private static Tenant provision(UUID controlPlaneTenantId, String code) throws SQLException {
        UUID organization = UUID.randomUUID();
        UUID company = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + organization + "', '"
                    + code + "', 'Org " + code + "')");
            statement.execute("INSERT INTO tms.company (id, organization_id, code, name) VALUES ('" + company + "', '"
                    + organization + "', '" + code + "', 'Co " + code + "')");
            statement.execute("INSERT INTO tms.app_user (id, email, full_name) VALUES ('" + user + "', '"
                    + code.toLowerCase() + "@entitlements.test', 'Admin " + code + "')");
            statement.execute("INSERT INTO tms.membership (id, app_user_id, organization_id, company_id) VALUES ('"
                    + membership + "', '" + user + "', '" + organization + "', NULL)");
            statement.execute("""
                    INSERT INTO tms.platform_provisioning_request (
                        idempotency_key, request_hash, control_plane_tenant_id, organization_id, company_id,
                        admin_app_user_id, admin_membership_id, admin_profile_reused, product_code,
                        contract_version, tenant_code, m2m_subject, m2m_jti)
                    VALUES ('ma-prov-v1-%s', '%s', '%s', '%s', '%s', '%s', '%s', false, 'tms', 'v1', '%s',
                            'masteradmin-provisioning', 'jti-seed-%s')
                    """.formatted(code.toLowerCase() + "0000", "0".repeat(64), controlPlaneTenantId, organization,
                    company, user, membership, code, code));
        }
        return new Tenant(controlPlaneTenantId, organization, company);
    }

    private static JtiGuard.Call call() {
        return new JtiGuard.Call("masteradmin.ebim", "masteradmin-provisioning", "it-jti-" + JTIS.incrementAndGet(),
                Instant.now().plusSeconds(330));
    }

    /** The next version for this tenant, so the tests do not depend on their execution order. */
    private static Map<String, Object> put(UUID tenant, boolean appActive) {
        return put(tenant, store.applied(tenant).map(row -> row.version() + 1).orElse(1L), appActive);
    }

    private static Map<String, Object> put(UUID tenant, long version, boolean appActive) {
        ApplyEntitlementSnapshotService service = new ApplyEntitlementSnapshotService(new JtiGuard(store),
                new EntitlementApplyWriter(store, ReceiverProfile.tms(), Clock.systemUTC()),
                new PlatformEntitlementsProperties("DEV"), ReceiverProfile.tms());
        byte[] body = TmsSnapshots.snapshot(tenant, version, appActive).toString().getBytes(StandardCharsets.UTF_8);
        return transactions.execute(status -> service.apply(tenant.toString(), body, "entitlements.v1", "it", call()));
    }

    private static Connection asRuntimeRole(UUID companyId) throws SQLException {
        Connection connection = PostgresTestDatabase.connect(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            if (companyId != null) {
                statement.execute("SELECT set_config('tms.company_id', '" + companyId + "', false)");
            }
            statement.execute("SET ROLE tms_app");
        }
        return connection;
    }

    @Test
    @DisplayName("PUT then GET round-trips through the real tables; the product mode is the seeded SHADOW")
    void appliesDurablyAndReadsBack() {
        Map<String, Object> applied = put(TENANT_A, true);
        Map<String, Object> read = new EntitlementQueryService(new JtiGuard(store), store, ReceiverProfile.tms())
                .applied(TENANT_A.toString(), call());

        assertThat(applied).containsEntry("status", "APPLIED");
        assertThat(read).containsEntry("appliedVersion", applied.get("appliedVersion"))
                .containsEntry("appliedChecksum", applied.get("appliedChecksum"))
                .containsEntry("enforcementMode", "SHADOW");
        assertThat(store.applied(TENANT_A)).get().satisfies(row -> {
            assertThat(row.organizationId()).isEqualTo(a.organizationId());
            assertThat(row.snapshot().get("controlPlaneTenantId").stringValue()).isEqualTo(TENANT_A.toString());
        });
        assertThat(store.mode(null)).isEqualTo(EnforcementMode.SHADOW);
    }

    @Test
    @DisplayName("a jti is accepted once; a tenant V51 never provisioned is 404 and leaves no row")
    void jtiAndUnprovisionedTenant() {
        assertThat(store.consumeJti("masteradmin.ebim", "it-once", Instant.now().plusSeconds(60))).isTrue();
        assertThat(store.consumeJti("masteradmin.ebim", "it-once", Instant.now().plusSeconds(60))).isFalse();

        UUID stranger = UUID.fromString("00000000-0000-4ccc-8000-000000001299");
        assertThatThrownBy(() -> put(stranger, 1, true)).isInstanceOf(EntitlementRejection.class)
                .extracting(e -> ((EntitlementRejection) e).code()).isEqualTo(EntitlementRejection.Code.TENANT_NOT_PROVISIONED);
        assertThat(store.applied(stranger)).isEmpty();
    }

    @Test
    @DisplayName("the audit trail, the mode history and the shadow differences are append-only for the owner")
    void trailsAreAppendOnly() throws SQLException {
        put(TENANT_B, false);
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT count(*) FROM tms.platform_entitlement_shadow_diff WHERE "
                    + "control_plane_tenant_id = '" + TENANT_B + "' AND kind = 'APP_ACTIVE'")).isPositive();
            for (String table : new String[] {"platform_entitlement_audit", "platform_entitlement_shadow_diff",
                    "platform_entitlement_mode_event"}) {
                assertThatThrownBy(() -> statement.execute("UPDATE tms." + table + " SET id = id"))
                        .as(table).hasMessageContaining("append-only");
                assertThatThrownBy(() -> statement.execute("DELETE FROM tms." + table))
                        .as(table).hasMessageContaining("append-only");
            }
        }
    }

    @Test
    @DisplayName("modes move one step at a time, are never deleted, and every change is recorded")
    void modesMoveOneStep() throws SQLException {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("INSERT INTO tms.platform_entitlement_mode "
                    + "(scope_key, mode, reason, updated_by) VALUES ('" + TENANT_B + "', 'PRIMARY', 'jump', 'it')"))
                    .hasMessageContaining("one step");
            statement.execute("INSERT INTO tms.platform_entitlement_mode (scope_key, mode, reason, updated_by) "
                    + "VALUES ('" + TENANT_B + "', 'DUAL_READ', 'cohort', 'it')");
            assertThatThrownBy(() -> statement.execute("UPDATE tms.platform_entitlement_mode SET mode = 'LEGACY', "
                    + "reason = 'jump back' WHERE scope_key = '" + TENANT_B + "'"))
                    .hasMessageContaining("one step");
            statement.execute("UPDATE tms.platform_entitlement_mode SET mode = 'SHADOW', reason = 'step back' "
                    + "WHERE scope_key = '" + TENANT_B + "'");
            assertThatThrownBy(() -> statement.execute("DELETE FROM tms.platform_entitlement_mode WHERE scope_key = '"
                    + TENANT_B + "'")).hasMessageContaining("never deleted");
            assertThat(count(statement, "SELECT count(*) FROM tms.platform_entitlement_mode_event WHERE scope_key = '"
                    + TENANT_B + "'")).isEqualTo(2);
            assertThat(count(statement, "SELECT count(*) FROM tms.platform_entitlement_mode_event WHERE "
                    + "scope_key = 'PRODUCT' AND to_mode = 'SHADOW' AND updated_by = 'V52'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the runtime role reads and writes none of the receiver's tables: no legacy path moves a mode or a snapshot")
    void runtimeRoleIsLockedOut() throws SQLException {
        try (Connection connection = asRuntimeRole(a.companyId());
                Statement statement = connection.createStatement()) {
            for (String table : new String[] {"platform_entitlement_applied", "platform_entitlement_audit",
                    "platform_entitlement_jti", "platform_entitlement_mode", "platform_entitlement_mode_event",
                    "platform_entitlement_shadow_diff"}) {
                assertThatThrownBy(() -> statement.executeQuery("SELECT 1 FROM tms." + table).close())
                        .as(table).isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
            }
            // D-14: TMS has no legacy entitlement write path. The runtime role cannot move a mode nor
            // replace or erase the applied snapshot; only the MasterAdmin receiver (owner) and the operator do.
            for (String write : new String[] {
                "UPDATE tms.platform_entitlement_mode SET mode = 'LEGACY', reason = 'app', updated_by = 'app'",
                "INSERT INTO tms.platform_entitlement_mode (scope_key, mode, reason, updated_by) "
                        + "VALUES ('" + TENANT_B + "', 'SHADOW', 'app', 'app')",
                "UPDATE tms.platform_entitlement_applied SET app_active = true",
                "DELETE FROM tms.platform_entitlement_applied"}) {
                assertThatThrownBy(() -> statement.executeUpdate(write))
                        .as(write).isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
            }
        }
    }

    @Test
    @DisplayName("tms_app learns the access facts of its OWN organization only, and nothing when unscoped")
    void definerRevealsOnlyTheScopedOrganization() throws SQLException {
        put(TENANT_A, true);
        put(TENANT_B, false);

        assertThat(facts(a.companyId())).contains(new AccessFacts(true, EnforcementMode.SHADOW, true));
        assertThat(facts(b.companyId())).contains(new AccessFacts(true, EnforcementMode.SHADOW, false));
        assertThat(facts(null)).isEmpty();
    }

    private static Optional<AccessFacts> facts(UUID companyId) throws SQLException {
        try (Connection connection = asRuntimeRole(companyId);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT under_control_plane, enforcement_mode, app_active "
                                + "FROM tms.commercial_access_current_company()")) {
            if (!rs.next()) {
                return Optional.empty();
            }
            AccessFacts facts = new AccessFacts(rs.getBoolean(1), EnforcementMode.valueOf(rs.getString(2)),
                    (Boolean) rs.getObject(3));
            assertThat(rs.next()).as("one row per scoped company").isFalse();
            return Optional.of(facts);
        }
    }

    private static long count(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
