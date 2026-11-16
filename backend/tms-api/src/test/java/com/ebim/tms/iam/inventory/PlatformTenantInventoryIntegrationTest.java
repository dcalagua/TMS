package com.ebim.tms.iam.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.application.PlatformEntitlementsProperties;
import com.ebim.tms.iam.entitlements.application.ReceiverProfile;
import com.ebim.tms.iam.entitlements.infrastructure.JdbcEntitlementRepository;
import com.ebim.tms.iam.inventory.application.PlatformTenantInventoryService;
import com.ebim.tms.iam.inventory.domain.InventoryRejection;
import com.ebim.tms.iam.inventory.infrastructure.JdbcTenantInventoryRepository;
import com.ebim.tms.iam.lifecycle.application.LifecycleApplyWriter;
import com.ebim.tms.iam.lifecycle.application.LifecycleStore.CallContext;
import com.ebim.tms.iam.lifecycle.application.PlatformLifecycleService;
import com.ebim.tms.iam.lifecycle.domain.LifecycleCommand;
import com.ebim.tms.iam.lifecycle.infrastructure.JdbcLifecycleRepository;
import com.ebim.tms.iam.provisioning.application.GenericProvisioningFixtures;
import com.ebim.tms.iam.provisioning.application.PlatformProvisioningService;
import com.ebim.tms.iam.provisioning.application.PlatformTenantProvisioner;
import com.ebim.tms.iam.provisioning.application.ProvisionedTenantView;
import com.ebim.tms.iam.provisioning.domain.ProvisioningMetadata;
import com.ebim.tms.iam.provisioning.infrastructure.PlatformProvisioningRepository;
import com.ebim.tms.iam.provisioning.security.PlatformCaller;
import com.ebim.tms.iam.provisioning.security.PlatformInventoryProperties;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * V76 and {@link JdbcTenantInventoryRepository} against a real PostgreSQL with the whole Flyway history:
 * the keyset SQL and its LATERAL "oldest company", the LINKED row V51's write-once table now accepts and
 * the check that keeps a created row whole, and - the point of the link - that the status read, the
 * entitlements resolution and lifecycle.v1 then resolve an adopted organization exactly like a created one.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class PlatformTenantInventoryIntegrationTest {

    private static final PlatformCaller CALLER = new PlatformCaller("masteradmin-provisioning", "it-inventory", null,
            null);
    private static final AtomicInteger JTIS = new AtomicInteger();

    private static String jdbcUrl;
    private static PlatformTenantInventoryService inventory;
    private static PlatformProvisioningService provisioning;
    private static PlatformLifecycleService lifecycle;
    private static JdbcEntitlementRepository entitlements;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_inventory");
        DataSource dataSource = new DriverManagerDataSource(jdbcUrl, PostgresTestDatabase.username(),
                PostgresTestDatabase.password());
        JdbcClient jdbc = JdbcClient.create(dataSource);
        PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        PlatformProvisioningRepository provisioningRepository = new PlatformProvisioningRepository(jdbc);
        entitlements = new JdbcEntitlementRepository(jdbc, JsonMapper.builder().build());
        JtiGuard jti = new JtiGuard(entitlements);

        inventory = new PlatformTenantInventoryService(new JdbcTenantInventoryRepository(jdbc, provisioningRepository),
                jti, new PlatformInventoryProperties("tms:tenants:list", "tms:tenants:link"),
                new PlatformEntitlementsProperties("DEV"), ReceiverProfile.tms(), transactionManager);
        // The provisioner in a transaction, as its Spring proxy runs it.
        PlatformTenantProvisioner provisioner = new PlatformTenantProvisioner(provisioningRepository) {
            @Override
            public Outcome provision(com.ebim.tms.iam.provisioning.application.ProvisionTenantCommand command,
                    ProvisioningMetadata metadata) {
                return transactions.execute(status -> super.provision(command, metadata));
            }
        };
        provisioning = new PlatformProvisioningService(provisioner, provisioningRepository, transactionManager);
        JdbcLifecycleRepository lifecycleStore = new JdbcLifecycleRepository(jdbc);
        LifecycleApplyWriter writer = new LifecycleApplyWriter(lifecycleStore, Clock.systemUTC()) {
            @Override
            public Outcome apply(LifecycleCommand command, String productCode, CallContext caller) {
                return transactions.execute(status -> super.apply(command, productCode, caller));
            }
        };
        lifecycle = new PlatformLifecycleService(jti, writer, lifecycleStore, new PlatformEntitlementsProperties("DEV"),
                ReceiverProfile.tms());
    }

    private static JtiGuard.Call call() {
        return new JtiGuard.Call("masteradmin.ebim", "masteradmin-provisioning", "it-inv-jti-" + JTIS.incrementAndGet(),
                Instant.now().plusSeconds(330));
    }

    /** An organization that exists in TMS without MasterAdmin, with companies created in this order. */
    private static UUID legacy(String code, boolean active, String... companies) throws SQLException {
        UUID organization = UUID.randomUUID();
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO tms.organization (id, code, name, active) VALUES ('" + organization + "', '"
                    + code + "', 'Org " + code + "', " + active + ")");
            int minute = 0;
            for (String company : companies) {
                statement.execute("INSERT INTO tms.company (organization_id, code, name, created_at) VALUES ('"
                        + organization + "', '" + company + "', 'Co " + company + "', now() - interval '"
                        + (60 - minute++) + " minutes')");
            }
        }
        return organization;
    }

    private static UUID companyOf(UUID organization, String code) throws SQLException {
        return UUID.fromString(single("SELECT id FROM tms.company WHERE organization_id = '" + organization
                + "' AND code = '" + code + "'"));
    }

    private static String single(String sql) throws SQLException {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<Map<String, Object>> listAll(int pageSize) {
        List<Map<String, Object>> items = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 1000; page++) {
            Map<String, Object> body = inventory.list(String.valueOf(pageSize), cursor, "tenant-inventory.v1", "it",
                    CALLER, call());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pageItems = (List<Map<String, Object>>) body.get("items");
            assertThat(pageItems).hasSizeLessThanOrEqualTo(pageSize);
            items.addAll(pageItems);
            cursor = (String) body.get("nextCursor");
            if (cursor == null) {
                return items;
            }
        }
        throw new AssertionError("the cursor never ended");
    }

    private static Map<String, Object> itemOf(List<Map<String, Object>> items, UUID organization) {
        return items.stream().filter(item -> organization.toString().equals(item.get("externalTenantId")))
                .findFirst().orElseThrow();
    }

    private static Map<String, Object> link(UUID controlPlaneTenantId, Object externalTenantId) {
        String body = "{\"externalTenantId\":\"" + externalTenantId + "\",\"requestId\":\"" + UUID.randomUUID() + "\"}";
        return inventory.link(controlPlaneTenantId.toString(), body.getBytes(StandardCharsets.UTF_8), true,
                "tenant-inventory.v1", "it", CALLER, call());
    }

    private static InventoryRejection.Code refusal(Runnable call) {
        try {
            call.run();
        } catch (InventoryRejection rejection) {
            return rejection.code();
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    @DisplayName("lists every organization once, by id, across pages; created tenants carry create's ids")
    void listsWithKeysetAndParity() throws SQLException {
        UUID legacy = legacy("INV-LEGACY", true, "INV-L-OLD", "INV-L-NEW");
        UUID archived = legacy("INV-ARCHIVED", false, "INV-A-1");
        UUID empty = legacy("INV-EMPTY", true);
        String cpt = "50000000-0000-4000-a000-0000000076c1";
        ProvisionedTenantView created = provisioning.create(CALLER, "ma-prov-v1-inventory-it-0001", "v1",
                GenericProvisioningFixtures.request(cpt, "inv-created", "inv-created@inventory.ebim.test"), "it");

        List<Map<String, Object>> items = listAll(2);
        List<String> ids = items.stream().map(item -> (String) item.get("externalTenantId")).toList();
        assertThat(ids).doesNotHaveDuplicates()
                .contains(legacy.toString(), archived.toString(), empty.toString(), created.externalTenantId());
        assertThat(ids).isEqualTo(listAll(500).stream().map(item -> (String) item.get("externalTenantId")).toList());
        // PostgreSQL's uuid order is the order of the canonical text form.
        assertThat(ids).isSorted();

        Map<String, Object> createdItem = itemOf(items, UUID.fromString(created.externalTenantId()));
        assertThat(createdItem).containsEntry("controlPlaneTenantId", cpt)
                .containsEntry("externalOrganizationId", created.externalOrganizationId())
                .containsEntry("externalCompanyId", created.externalCompanyId())
                .containsEntry("status", "ACTIVE");
        assertThat(itemOf(items, legacy)).containsEntry("controlPlaneTenantId", null)
                .containsEntry("externalCompanyId", companyOf(legacy, "INV-L-OLD").toString())
                .containsEntry("code", "INV-LEGACY").containsEntry("name", "Org INV-LEGACY")
                .containsEntry("status", "ACTIVE");
        assertThat(itemOf(items, archived)).containsEntry("status", "INACTIVE");
        assertThat(itemOf(items, empty)).containsEntry("externalCompanyId", null);
    }

    @Test
    @DisplayName("link adopts an organization: status, entitlements and lifecycle.v1 then resolve it")
    void linkedTenantResolvesEverywhere() throws SQLException {
        UUID organization = legacy("INV-ADOPT", true, "INV-AD-OLD", "INV-AD-NEW");
        UUID cpt = UUID.randomUUID();
        String organizationsBefore = single("SELECT count(*) FROM tms.organization");
        String companiesBefore = single("SELECT count(*) FROM tms.company");
        String usersBefore = single("SELECT count(*) FROM tms.app_user");
        String membershipsBefore = single("SELECT count(*) FROM tms.membership");

        assertThat(link(cpt, organization)).containsEntry("status", "LINKED")
                .containsEntry("externalTenantId", organization.toString())
                .containsEntry("controlPlaneTenantId", cpt.toString());
        assertThat(link(cpt, organization)).containsEntry("status", "ALREADY_LINKED");

        // Nothing of the tenant was created or changed.
        assertThat(single("SELECT count(*) FROM tms.organization")).isEqualTo(organizationsBefore);
        assertThat(single("SELECT count(*) FROM tms.company")).isEqualTo(companiesBefore);
        assertThat(single("SELECT count(*) FROM tms.app_user")).isEqualTo(usersBefore);
        assertThat(single("SELECT count(*) FROM tms.membership")).isEqualTo(membershipsBefore);
        assertThat(single("SELECT origin || '/' || coalesce(admin_app_user_id::text, '-') || '/' || environment "
                + "FROM tms.platform_provisioning_request WHERE control_plane_tenant_id = '" + cpt + "'"))
                .isEqualTo("LINKED/-/DEV");

        ProvisionedTenantView status = provisioning.status(CALLER, cpt.toString(), "it");
        assertThat(status.externalTenantId()).isEqualTo(organization.toString());
        assertThat(status.externalCompanyId()).isEqualTo(companyOf(organization, "INV-AD-OLD").toString());
        assertThat(status.resources()).containsEntry("linked", true).doesNotContainKey("adminStatus");

        assertThat(entitlements.provisioned(cpt)).isPresent();

        String suspend = """
                {"contract":"lifecycle.v1","controlPlaneTenantId":"%s","productCode":"tms","environment":"DEV",
                 "version":1,"state":"SUSPENDED","reason":"MANUAL","effectiveAt":"2026-11-16T11:00:00Z",
                 "idempotencyKey":"lifecycle:%s:tms:1","correlationId":"it"}
                """.formatted(cpt, cpt);
        assertThat(lifecycle.apply(cpt.toString(), suspend.getBytes(StandardCharsets.UTF_8), "lifecycle.v1", "it",
                call())).containsEntry("state", "SUSPENDED");
        Map<String, Object> item = itemOf(listAll(500), organization);
        assertThat(item).containsEntry("status", "SUSPENDED").containsEntry("controlPlaneTenantId", cpt.toString())
                .containsEntry("externalCompanyId", status.externalCompanyId());

        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            List<String> audit = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT result FROM tms.platform_provisioning_audit "
                    + "WHERE operation = 'LINK_TENANT' AND control_plane_tenant_id = '" + cpt + "' ORDER BY occurred_at")) {
                while (rs.next()) {
                    audit.add(rs.getString(1));
                }
            }
            assertThat(audit).containsExactly("LINKED", "ALREADY_LINKED");
        }
    }

    @Test
    @DisplayName("conflicts, unknown tenants and the guards of the table")
    void conflictsAndGuards() throws SQLException {
        UUID first = legacy("INV-FIRST", true, "INV-F-1");
        UUID second = legacy("INV-SECOND", true, "INV-S-1");
        UUID cpt = UUID.randomUUID();
        link(cpt, first);

        assertThat(refusal(() -> link(UUID.randomUUID(), first))).isEqualTo(InventoryRejection.Code.LINK_CONFLICT);
        assertThat(refusal(() -> link(cpt, second))).isEqualTo(InventoryRejection.Code.LINK_CONFLICT);
        assertThat(refusal(() -> link(UUID.randomUUID(), UUID.randomUUID())))
                .isEqualTo(InventoryRejection.Code.EXTERNAL_TENANT_NOT_FOUND);
        assertThat(single("SELECT count(*) FROM tms.platform_provisioning_request WHERE organization_id = '"
                + second + "'")).isEqualTo("0");
        assertThat(single("SELECT count(*) FROM tms.platform_provisioning_audit WHERE operation = 'LINK_TENANT' "
                + "AND result = 'CONFLICT' AND error_code = 'LINK_CONFLICT'")).isNotEqualTo("0");

        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            // Still write-once.
            assertThatThrownBy(() -> statement.execute("UPDATE tms.platform_provisioning_request SET origin = "
                    + "'PROVISIONED' WHERE control_plane_tenant_id = '" + cpt + "'"))
                    .hasMessageContaining("write-once");
            // A created row cannot drop its administrator; a linked one cannot name one.
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO tms.platform_provisioning_request (idempotency_key, request_hash,
                        control_plane_tenant_id, organization_id, company_id, product_code, contract_version,
                        tenant_code, m2m_subject, m2m_jti)
                    VALUES ('ma-prov-v1-noadmin-0001', '%s', '%s', '%s', '%s', 'tms', 'v1', 'INV-SECOND',
                        'masteradmin-provisioning', 'jti')
                    """.formatted("0".repeat(64), UUID.randomUUID(), second, companyOf(second, "INV-S-1"))))
                    .hasMessageContaining("ck_platform_provisioning_request_origin");
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO tms.platform_provisioning_request (idempotency_key, request_hash,
                        control_plane_tenant_id, organization_id, admin_profile_reused, product_code,
                        contract_version, tenant_code, m2m_subject, m2m_jti, origin)
                    VALUES ('link:x-0001', '%s', '%s', '%s', false, 'tms', 'v1', 'INV-SECOND',
                        'masteradmin-provisioning', 'jti', 'LINKED')
                    """.formatted("0".repeat(64), UUID.randomUUID(), second)))
                    .hasMessageContaining("ck_platform_provisioning_request_origin");
            assertThatThrownBy(() -> statement.execute("INSERT INTO tms.platform_provisioning_audit (operation, "
                    + "result, http_status) VALUES ('DELETE_TENANT', 'LINKED', 200)"))
                    .hasMessageContaining("ck_platform_provisioning_audit_operation");
        }
    }
}
