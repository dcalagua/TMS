package com.ebim.tms.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * The bulk order import end to end: real HTTP, real security filters, real PostgreSQL.
 *
 * <p>{@code OrderImportValidatorTest} already proves the rules as pure functions and runs
 * everywhere. What only a database can prove is here: that a rejected file writes <em>nothing</em>,
 * that re-uploading is idempotent against rows that are actually committed, that the audit row is
 * written in the same transaction, and that a file naming another company's master cannot reach it.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OrderImportApiIntegrationTest.JwtDecoderOverride.class)
class OrderImportApiIntegrationTest {

    private static final String BASE = "/api/v1/orders/import";
    private static final String ORDERS = "/api/v1/orders";
    private static final String SOURCE = "ACME-ERP";

    private static final UUID ORGANIZATION = UUID.fromString("66666666-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("66666666-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("66666666-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("66666666-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("66666666-0000-4000-8000-0000000000e2");

    private static final String HEADER = "externalReference,originCode,destinationCode,serviceDate,priority,"
            + "declaredWeightKg,declaredVolumeM3,declaredPallets,materialCode,materialDescription,quantity,uom,"
            + "unitWeightKg,unitVolumeM3,palletQuantity";

    private static String jdbcUrl;

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;
    private String viewerToken;

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtDecoderOverride {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_order_import_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'IMP-ORG', 'Import Organization');

                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'IMP-A', 'Company A', 'America/Lima'),
                    ('%s', '%s', 'IMP-B', 'Company B', 'America/Lima');

                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'imp.admin@example.invalid', 'IMP Admin'),
                    ('%s', 'imp.viewer@example.invalid', 'IMP Viewer');

                -- Company A's masters, plus one deactivated origin so "inactive is refused" is
                -- provable, and Company B's own STORE-B so a cross-tenant code exists to try.
                INSERT INTO tms.origin (company_id, code, name) VALUES ('%s', 'LIM-01', 'Lima DC');
                INSERT INTO tms.origin (company_id, code, name, active)
                     VALUES ('%s', 'LIM-OLD', 'Retired DC', false);
                INSERT INTO tms.destination (company_id, code, name, country)
                     VALUES ('%s', 'STORE-1', 'Miraflores store', 'PE');
                INSERT INTO tms.origin (company_id, code, name) VALUES ('%s', 'LIM-B', 'B origin');
                INSERT INTO tms.destination (company_id, code, name, country)
                     VALUES ('%s', 'STORE-B', 'B store', 'PE');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION, ADMIN_AUTH, VIEWER_AUTH,
                COMPANY_A, COMPANY_A, COMPANY_A, COMPANY_B, COMPANY_B));

        membership("imp.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("imp.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("imp.viewer@example.invalid", COMPANY_A, "VIEWER");
    }

    private static void membership(String email, UUID companyId, String roleCode) {
        execute("""
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = '%s';

                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id
                FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = '%s'
                JOIN tms.role r ON r.code = '%s'
                WHERE m.company_id = '%s';
                """.formatted(ORGANIZATION, companyId, email, email, roleCode, companyId));
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the order import fixture", failed);
        }
    }

    private static long count(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the order import fixture", failed);
        }
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
    }

    // --- request helpers --------------------------------------------------------------

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "orders.csv", "text/csv",
                (HEADER + "\n" + body).getBytes(StandardCharsets.UTF_8));
    }

    /** externalReference,origin,destination,date,priority,dW,dV,dP,material,desc,qty,uom,uW,uV,pal */
    private static String line(String reference, String material, String quantity, String unitWeight) {
        return reference + ",LIM-01,STORE-1,2026-03-01,NORMAL,,,," + material + "," + material + " description,"
                + quantity + ",EA," + unitWeight + ",,\n";
    }

    /**
     * The multipart builders are not interchangeable with the plain one in Spring 7, so these
     * two build their request end to end rather than sharing an "add the auth headers" helper.
     */
    private RequestBuilder preview(MockMultipartFile file, UUID companyId) {
        return multipart(BASE + "/preview").file(file)
                .param("externalSource", SOURCE)
                .header("Authorization", "Bearer " + adminToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private RequestBuilder apply(MockMultipartFile file, UUID companyId) {
        return multipart(BASE).file(file)
                .param("externalSource", SOURCE)
                .header("Authorization", "Bearer " + adminToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    // --- template ----------------------------------------------------------------------

    @Test
    @DisplayName("the template downloads as an attachment in both formats")
    void templateDownloads() throws Exception {
        mockMvc.perform(get(BASE + "/template").param("format", "XLSX")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition",
                                org.hamcrest.Matchers.containsString("tms-order-import-template.xlsx")));

        mockMvc.perform(get(BASE + "/template").param("format", "CSV")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the template still needs authentication and a company - it is not a quiet second API")
    void templateIsNotPublic() throws Exception {
        mockMvc.perform(get(BASE + "/template")).andExpect(status().isUnauthorized());
    }

    // --- preview writes nothing -----------------------------------------------------------

    @Test
    @DisplayName("a preview reports what would happen and writes nothing at all")
    void previewWritesNothing() throws Exception {
        long before = count("SELECT count(*) FROM tms.transport_order WHERE company_id = '" + COMPANY_A + "'");

        mockMvc.perform(preview(csv(line("PRV-1", "SKU-1", "2", "10")), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.orders[0].totalWeightKg").value(20))
                .andExpect(jsonPath("$.orders[0].totalsSource").value("CALCULATED"))
                .andExpect(jsonPath("$.orders[0].orderNumber").doesNotExist());

        assertThat(count("SELECT count(*) FROM tms.transport_order WHERE company_id = '" + COMPANY_A + "'"))
                .isEqualTo(before);
        assertThat(count("SELECT count(*) FROM tms.order_import_batch")).isZero();
    }

    @Test
    @DisplayName("a viewer may preview but may not apply")
    void previewIsReadPermissionAndApplyIsManage() throws Exception {
        mockMvc.perform(multipart(BASE + "/preview").file(csv(line("PERM-1", "SKU-1", "2", "10")))
                        .param("externalSource", SOURCE)
                        .header("Authorization", "Bearer " + viewerToken)
                        .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString()))
                .andExpect(status().isOk());

        mockMvc.perform(multipart(BASE).file(csv(line("PERM-1", "SKU-1", "2", "10")))
                        .param("externalSource", SOURCE)
                        .header("Authorization", "Bearer " + viewerToken)
                        .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString()))
                .andExpect(status().isForbidden());
    }

    // --- apply --------------------------------------------------------------------------

    @Test
    @DisplayName("applying creates the orders, records a batch, and the orders are readable through the list")
    void applyCreatesOrdersAndAnAuditRow() throws Exception {
        String body = line("APP-1", "SKU-1", "2", "10") + line("APP-1", "SKU-2", "3", "4")
                + "APP-2,LIM-01,STORE-1,2026-03-01,HIGH,1200,3.4,2,,,,,,,\n";

        String response = mockMvc.perform(apply(csv(body), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.createdCount").value(2))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.batchId").exists())
                .andExpect(jsonPath("$.orders[0].orderNumber").value(org.hamcrest.Matchers.startsWith("TO-")))
                .andReturn().getResponse().getContentAsString();

        // The order with lines took its totals from them; the header-only one from its declaration.
        assertThat(JsonPath.<String>read(response, "$.orders[0].totalsSource")).isEqualTo("CALCULATED");
        assertThat(JsonPath.<String>read(response, "$.orders[1].totalsSource")).isEqualTo("DECLARED");

        mockMvc.perform(get(ORDERS).param("orderNumber", "TO-")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString()))
                .andExpect(status().isOk());

        assertThat(count("SELECT count(*) FROM tms.transport_order WHERE company_id = '" + COMPANY_A
                + "' AND external_reference IN ('APP-1', 'APP-2')")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM tms.transport_order_line l JOIN tms.transport_order o"
                + " ON o.id = l.order_id WHERE o.external_reference = 'APP-1'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM tms.order_import_batch WHERE company_id = '" + COMPANY_A
                + "' AND created_count = 2")).isEqualTo(1);
    }

    @Test
    @DisplayName("re-applying the same file creates nothing and reports every order as already imported")
    void reapplyingIsIdempotent() throws Exception {
        MockMultipartFile file = csv(line("IDEM-1", "SKU-1", "2", "10"));

        mockMvc.perform(apply(file, COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1));

        mockMvc.perform(apply(file, COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.orders[0].outcome").value("SKIPPED_DUPLICATE"));

        assertThat(count("SELECT count(*) FROM tms.transport_order WHERE company_id = '" + COMPANY_A
                + "' AND external_reference = 'IDEM-1'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the same external reference under a different source is a different order")
    void identityIncludesTheSource() throws Exception {
        MockMultipartFile file = csv(line("SRC-1", "SKU-1", "2", "10"));

        mockMvc.perform(apply(file, COMPANY_A)).andExpect(jsonPath("$.createdCount").value(1));
        mockMvc.perform(multipart(BASE).file(file).param("externalSource", "OTHER-ERP")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString()))
                .andExpect(jsonPath("$.createdCount").value(1));

        assertThat(count("SELECT count(*) FROM tms.transport_order WHERE company_id = '" + COMPANY_A
                + "' AND external_reference = 'SRC-1'")).isEqualTo(2);
    }

    @Test
    @DisplayName("the same external reference in another company is a different order")
    void identityIsCompanyScoped() throws Exception {
        // Company B's file names its own masters; the reference text is identical to A's.
        MockMultipartFile fileForB = new MockMultipartFile("file", "orders.csv", "text/csv",
                (HEADER + "\nTENANT-1,LIM-B,STORE-B,2026-03-01,NORMAL,,,,SKU-1,SKU-1 description,2,EA,10,,\n")
                        .getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(apply(csv(line("TENANT-1", "SKU-1", "2", "10")), COMPANY_A))
                .andExpect(jsonPath("$.createdCount").value(1));
        mockMvc.perform(apply(fileForB, COMPANY_B))
                .andExpect(jsonPath("$.createdCount").value(1));

        assertThat(count("SELECT count(*) FROM tms.transport_order WHERE external_reference = 'TENANT-1'"))
                .isEqualTo(2);
    }

    // --- refusals write nothing ------------------------------------------------------------

    @Test
    @DisplayName("a file with one bad row imports none of its good ones either")
    void oneBadRowRejectsTheWholeFile() throws Exception {
        long before = count("SELECT count(*) FROM tms.transport_order WHERE company_id = '" + COMPANY_A + "'");
        String body = line("GOOD-1", "SKU-1", "2", "10")
                + "BAD-1,LIM-01,STORE-1,2026-03-01,NORMAL,,,,SKU-2,SKU-2 description,twelve,EA,,,\n";

        mockMvc.perform(apply(csv(body), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.issues[0].rowNumber").value(3))
                .andExpect(jsonPath("$.issues[0].column").value("quantity"));

        // Not "the good one landed and the bad one did not" - nothing landed. That is the whole
        // point of applying in one transaction.
        assertThat(count("SELECT count(*) FROM tms.transport_order WHERE company_id = '" + COMPANY_A + "'"))
                .isEqualTo(before);
        assertThat(count("SELECT count(*) FROM tms.order_import_batch WHERE created_count > 0"
                + " AND company_id = '" + COMPANY_A + "' AND external_source = '" + SOURCE
                + "' AND row_count = 2")).isZero();
    }

    @Test
    @DisplayName("another company's origin code cannot be reached, and is reported like any unknown code")
    void crossTenantMasterIsRefused() throws Exception {
        // LIM-B is a real, active origin - of Company B. Company A must not be able to name it,
        // and must not learn from the message that it exists.
        String body = "XTEN-1,LIM-B,STORE-1,2026-03-01,NORMAL,,,,SKU-1,SKU-1 description,2,EA,10,,\n";

        mockMvc.perform(apply(csv(body), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.issues[0].column").value("originCode"))
                .andExpect(jsonPath("$.issues[0].message")
                        .value("'LIM-B' does not match an active origin in this company."));

        assertThat(count("SELECT count(*) FROM tms.transport_order WHERE external_reference = 'XTEN-1'")).isZero();
    }

    @Test
    @DisplayName("an inactive origin is refused, exactly as the manual API refuses it")
    void inactiveMasterIsRefused() throws Exception {
        String body = "INACT-1,LIM-OLD,STORE-1,2026-03-01,NORMAL,,,,SKU-1,SKU-1 description,2,EA,10,,\n";

        mockMvc.perform(apply(csv(body), COMPANY_A))
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.issues[0].column").value("originCode"));
    }

    @Test
    @DisplayName("declared totals that contradict the lines reject the file with a row-level explanation")
    void contradictingTotalsAreReported() throws Exception {
        String body = "TOT-1,LIM-01,STORE-1,2026-03-01,NORMAL,1200,,,SKU-1,SKU-1 description,2,EA,10,,\n";

        mockMvc.perform(apply(csv(body), COMPANY_A))
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.issues[0].column").value("declaredWeightKg"))
                .andExpect(jsonPath("$.rejectedCount").value(1));
    }

    @Test
    @DisplayName("a file that is neither .xlsx nor .csv is refused before anything is parsed")
    void unsupportedFile() throws Exception {
        MockMultipartFile pdf = new MockMultipartFile("file", "orders.pdf", "application/pdf",
                "%PDF-1.7".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(apply(pdf, COMPANY_A)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an import with no external source is refused: without one nothing is idempotent")
    void externalSourceIsRequired() throws Exception {
        mockMvc.perform(multipart(BASE).file(csv(line("NOSRC-1", "SKU-1", "2", "10")))
                        .param("externalSource", "  ")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an import without a company header is refused by the scope filter, like every endpoint")
    void companyScopeIsRequired() throws Exception {
        mockMvc.perform(multipart(BASE + "/preview").file(csv(line("SCOPE-1", "SKU-1", "2", "10")))
                        .param("externalSource", SOURCE)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an imported header-only order carries declared totals and can be marked ready for planning")
    void importedDeclaredOrderIsPlannable() throws Exception {
        String body = "PLAN-1,LIM-01,STORE-1,2026-03-01,HIGH,1200,3.4,2,,,,,,,\n";

        String response = mockMvc.perform(apply(csv(body), COMPANY_A))
                .andExpect(jsonPath("$.applied").value(true))
                .andReturn().getResponse().getContentAsString();
        String orderNumber = JsonPath.read(response, "$.orders[0].orderNumber");

        String listed = mockMvc.perform(get(ORDERS).param("orderNumber", orderNumber)
                        .header("Authorization", "Bearer " + adminToken)
                        .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].totalsSource").value("DECLARED"))
                .andExpect(jsonPath("$.content[0].totalWeightKg").value(1200.0))
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(listed, "$.content[0].id");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(ORDERS + "/" + id + "/mark-ready")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_PLANNING"));
    }

    @Test
    @DisplayName("the downloaded template can be filled in and imported without editing its shape")
    void templateRoundTripsThroughApply() throws Exception {
        // The operator's actual first journey: download, replace the example rows, upload. The
        // header row is used exactly as generated.
        byte[] template = mockMvc.perform(get(BASE + "/template").param("format", "CSV")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        String text = new String(template, StandardCharsets.UTF_8);
        String templateHeader = text.substring(0, text.indexOf("\r\n"));
        String filled = templateHeader + "\r\n"
                + "TPL-1,LIM-01,STORE-1,,,2026-03-01,NORMAL,,,,,,SKU-1,Water,10,CS,2,,\r\n";

        mockMvc.perform(apply(new MockMultipartFile("file", "orders.csv", "text/csv",
                        filled.getBytes(StandardCharsets.UTF_8)), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.orders[0].totalWeightKg").value(20));
    }
}
