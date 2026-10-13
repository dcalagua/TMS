package com.ebim.tms.iam.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.application.PlatformEntitlementsProperties;
import com.ebim.tms.iam.entitlements.application.ReceiverProfile;
import com.ebim.tms.iam.entitlements.infrastructure.JdbcEntitlementRepository;
import com.ebim.tms.iam.lifecycle.application.LifecycleApplyWriter;
import com.ebim.tms.iam.lifecycle.application.LifecycleStore.Status;
import com.ebim.tms.iam.lifecycle.application.PlatformLifecycleService;
import com.ebim.tms.iam.lifecycle.domain.LifecycleRejection;
import com.ebim.tms.iam.lifecycle.domain.LifecycleState;
import com.ebim.tms.iam.lifecycle.infrastructure.JdbcLifecycleRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
 * V58 and {@link JdbcLifecycleRepository} against a real PostgreSQL with the whole Flyway history: what
 * the in-memory tests cannot prove - the foreign keys to V51, the reason/state check, the forward-only
 * and never-deleted guard, the append-only trail, the runtime role locked out of both tables, and the
 * SECURITY DEFINER reads that reveal to {@code tms_app} the state of its own organization only.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class PlatformLifecycleIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-4ccc-8000-000000005811");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-4ccc-8000-000000005812");

    private static String jdbcUrl;
    private static JdbcLifecycleRepository store;
    private static PlatformLifecycleService service;
    private static TransactionTemplate transactions;
    private static Tenant a;
    private static Tenant b;
    private static final AtomicInteger JTIS = new AtomicInteger();

    record Tenant(UUID controlPlaneTenantId, UUID organizationId, UUID companyId) {}

    @BeforeAll
    static void migrate() throws SQLException {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_lifecycle");
        DataSource dataSource = new DriverManagerDataSource(jdbcUrl, PostgresTestDatabase.username(),
                PostgresTestDatabase.password());
        JdbcClient jdbc = JdbcClient.create(dataSource);
        store = new JdbcLifecycleRepository(jdbc);
        JtiGuard jti = new JtiGuard(new JdbcEntitlementRepository(jdbc, JsonMapper.builder().build()));
        service = new PlatformLifecycleService(jti, new LifecycleApplyWriter(store, Clock.systemUTC()), store,
                new PlatformEntitlementsProperties("DEV"), ReceiverProfile.tms());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        a = provision(TENANT_A, "LCA");
        b = provision(TENANT_B, "LCB");
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
                    + code.toLowerCase() + "@lifecycle.test', 'Admin " + code + "')");
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
        return new JtiGuard.Call("masteradmin.ebim", "masteradmin-provisioning", "it-lc-jti-" + JTIS.incrementAndGet(),
                Instant.now().plusSeconds(330));
    }

    private static Map<String, Object> put(UUID tenant, long version, String state, String reason) {
        String body = """
                {"contract":"lifecycle.v1","controlPlaneTenantId":"%s","productCode":"tms","environment":"DEV",
                 "version":%d,"state":"%s","reason":%s,"effectiveAt":"2026-10-13T11:00:00Z",
                 "idempotencyKey":"lifecycle:%s:tms:%d","correlationId":"it"}
                """.formatted(tenant, version, state, reason == null ? "null" : "\"" + reason + "\"", tenant, version);
        return transactions.execute(status -> service.apply(tenant.toString(), body.getBytes(StandardCharsets.UTF_8),
                "lifecycle.v1", "it", call()));
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
    @DisplayName("suspend, replay, stale, conflict and reactivate round-trip through the real tables")
    void versionRules() throws SQLException {
        assertThat(put(TENANT_A, 1, "SUSPENDED", "NON_PAYMENT")).containsEntry("state", "SUSPENDED")
                .containsEntry("replayed", false);
        assertThat(put(TENANT_A, 1, "SUSPENDED", "NON_PAYMENT")).containsEntry("replayed", true);
        assertThatThrownBy(() -> put(TENANT_A, 1, "ACTIVE", null)).isInstanceOf(LifecycleRejection.class)
                .extracting(e -> ((LifecycleRejection) e).code()).isEqualTo(LifecycleRejection.Code.VERSION_CONFLICT);
        assertThat(put(TENANT_A, 2, "ACTIVE", null)).containsEntry("state", "ACTIVE").containsEntry("reason", null);
        assertThatThrownBy(() -> put(TENANT_A, 1, "SUSPENDED", "NON_PAYMENT")).isInstanceOf(LifecycleRejection.class)
                .extracting(e -> ((LifecycleRejection) e).code()).isEqualTo(LifecycleRejection.Code.STALE_LIFECYCLE);

        assertThat(service.applied(TENANT_A.toString(), call())).containsEntry("appliedVersion", 2L)
                .containsEntry("state", "ACTIVE");
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT outcome FROM tms.platform_tenant_lifecycle_audit WHERE "
                    + "control_plane_tenant_id = '" + TENANT_A + "' ORDER BY occurred_at, id"))
                    .containsExactlyInAnyOrder("APPLIED", "REPLAYED", "VERSION_CONFLICT", "APPLIED", "STALE_LIFECYCLE");
        }
    }

    @Test
    @DisplayName("a tenant V51 never provisioned is 404 and leaves no row")
    void unprovisioned() {
        UUID stranger = UUID.fromString("00000000-0000-4ccc-8000-000000005899");
        assertThatThrownBy(() -> put(stranger, 1, "SUSPENDED", "MANUAL")).isInstanceOf(LifecycleRejection.class)
                .extracting(e -> ((LifecycleRejection) e).code())
                .isEqualTo(LifecycleRejection.Code.TENANT_NOT_PROVISIONED);
        assertThat(store.applied(stranger)).isEmpty();
    }

    @Test
    @DisplayName("the row moves forward only, is never deleted, and keeps state and reason consistent")
    void guards() throws SQLException {
        put(TENANT_B, 5, "SUSPENDED", "MANUAL");
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            String row = " WHERE control_plane_tenant_id = '" + TENANT_B + "'";
            assertThatThrownBy(() -> statement.execute("UPDATE tms.platform_tenant_lifecycle SET applied_version = 4"
                    + row)).hasMessageContaining("forward only");
            assertThatThrownBy(() -> statement.execute("DELETE FROM tms.platform_tenant_lifecycle" + row))
                    .hasMessageContaining("never deleted");
            assertThatThrownBy(() -> statement.execute("UPDATE tms.platform_tenant_lifecycle SET state = 'ACTIVE'"
                    + row)).hasMessageContaining("ck_platform_tenant_lifecycle_reason");
            assertThatThrownBy(() -> statement.execute("UPDATE tms.platform_tenant_lifecycle_audit SET outcome = "
                    + "'APPLIED'")).hasMessageContaining("append-only");
            assertThatThrownBy(() -> statement.execute("DELETE FROM tms.platform_tenant_lifecycle_audit"))
                    .hasMessageContaining("append-only");
        }
        put(TENANT_B, 6, "ACTIVE", null);
    }

    @Test
    @DisplayName("the runtime role reads and writes neither table")
    void runtimeRoleIsLockedOut() throws SQLException {
        try (Connection connection = asRuntimeRole(a.companyId());
                Statement statement = connection.createStatement()) {
            for (String table : new String[] {"platform_tenant_lifecycle", "platform_tenant_lifecycle_audit"}) {
                assertThatThrownBy(() -> statement.executeQuery("SELECT 1 FROM tms." + table).close())
                        .as(table).isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
            }
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM tms.platform_tenant_lifecycle"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
        }
    }

    @Test
    @DisplayName("tms_app learns the state of its OWN organization only, and nothing when unscoped")
    void definerRevealsOnlyTheScopedOrganization() throws SQLException {
        UUID c = UUID.fromString("00000000-0000-4ccc-8000-000000005813");
        Tenant tenant = provision(c, "LCC");
        put(c, 1, "SUSPENDED", "TERMINATED");

        assertThat(current(tenant.companyId())).contains("SUSPENDED/TERMINATED");
        assertThat(current(null)).isEmpty();
        try (Connection connection = asRuntimeRole(tenant.companyId());
                Statement statement = connection.createStatement()) {
            List<String> states = strings(statement, "SELECT state FROM tms.tenant_lifecycle_of_organizations("
                    + "ARRAY['" + tenant.organizationId() + "'::uuid])");
            assertThat(states).containsExactly("SUSPENDED");
        }
        Map<UUID, Status> byOrganization = store.statusOfOrganizations(List.of(tenant.organizationId(),
                UUID.randomUUID()));
        assertThat(byOrganization).containsOnlyKeys(tenant.organizationId());
        assertThat(byOrganization.get(tenant.organizationId()).state()).isEqualTo(LifecycleState.SUSPENDED);
    }

    private static Optional<String> current(UUID companyId) throws SQLException {
        try (Connection connection = asRuntimeRole(companyId);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT state, reason FROM tms.tenant_lifecycle_current_company()")) {
            return rs.next() ? Optional.of(rs.getString(1) + "/" + rs.getString(2)) : Optional.empty();
        }
    }

    private static List<String> strings(Statement statement, String sql) throws SQLException {
        List<String> values = new java.util.ArrayList<>();
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
