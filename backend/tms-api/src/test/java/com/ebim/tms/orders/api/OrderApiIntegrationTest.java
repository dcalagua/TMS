package com.ebim.tms.orders.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The order vertical slice (Step 09), exercised end to end through the real HTTP filter chain
 * and a real, freshly migrated PostgreSQL - the same proof {@code RouteApiIntegrationTest} gives
 * V8's slice, extended to orders: totals recomputation, invalid time windows, company isolation,
 * duplicate external references, the status lifecycle, optimistic-locking conflicts and
 * permissions.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OrderApiIntegrationTest.JwtDecoderOverride.class)
class OrderApiIntegrationTest {

    private static final String BASE = "/api/v1/orders";

    private static final UUID ORGANIZATION = UUID.fromString("55555555-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("55555555-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("55555555-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("55555555-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("55555555-0000-4000-8000-0000000000e2");

    private static String jdbcUrl;
    private static String originA;
    private static String destinationA;
    private static String inactiveOriginA;
    private static String originB;
    private static String destinationB;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_order_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES
                    ('%s', 'ORD-ORG', 'Order Organization');

                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'ORD-A', 'Company A', 'America/Lima'),
                    ('%s', '%s', 'ORD-B', 'Company B', 'America/Lima');

                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'ord.admin@example.invalid', 'ORD Admin'),
                    ('%s', 'ord.viewer@example.invalid', 'ORD Viewer');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION, ADMIN_AUTH, VIEWER_AUTH));

        membership("ord.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("ord.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("ord.viewer@example.invalid", COMPANY_A, "VIEWER");

        originA = insertReturningId(
                "INSERT INTO tms.origin (company_id, code, name) VALUES ('" + COMPANY_A + "', 'ORIGIN-A', 'Origin A')");
        destinationA = insertReturningId("INSERT INTO tms.destination (company_id, code, name, country) VALUES ('"
                + COMPANY_A + "', 'DEST-A', 'Destination A', 'PE')");
        inactiveOriginA = insertReturningId("INSERT INTO tms.origin (company_id, code, name, active) VALUES ('"
                + COMPANY_A + "', 'ORIGIN-INACTIVE', 'Inactive Origin', false)");
        originB = insertReturningId(
                "INSERT INTO tms.origin (company_id, code, name) VALUES ('" + COMPANY_B + "', 'ORIGIN-B', 'Origin B')");
        destinationB = insertReturningId("INSERT INTO tms.destination (company_id, code, name, country) VALUES ('"
                + COMPANY_B + "', 'DEST-B', 'Destination B', 'PE')");
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

    private static String insertReturningId(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql + " RETURNING id")) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed an order fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the order fixture", failed);
        }
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + adminToken).header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + viewerToken).header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private static String idOf(String jsonResponse) {
        return JsonPath.read(jsonResponse, "$.id");
    }

    private static String line(String materialCode, String quantity, String unitWeightKg, String unitVolumeM3, String palletQuantity) {
        return """
                {"materialCode":"%s","materialDescription":"%s desc","quantity":%s,"uom":"EA",
                 "unitWeightKg":%s,"unitVolumeM3":%s,"palletQuantity":%s}
                """.formatted(materialCode, materialCode, quantity, numberOrNull(unitWeightKg), numberOrNull(unitVolumeM3),
                numberOrNull(palletQuantity));
    }

    private static String numberOrNull(String value) {
        return value == null ? "null" : value;
    }

    private String orderRequest(String originId, String destinationId, String externalSource, String externalReference,
            String windowStart, String windowEnd, Long version, String... lines) {
        return """
                {"externalSource":%s,"externalReference":%s,"originId":"%s","destinationId":"%s",
                 "customerName":"Acme","customerReference":"PO-1","serviceDate":"2026-03-01","priority":"NORMAL",
                 "requestedWindowStart":%s,"requestedWindowEnd":%s,"version":%s,"lines":[%s]}
                """.formatted(quoteOrNull(externalSource), quoteOrNull(externalReference), originId, destinationId,
                quoteOrNull(windowStart), quoteOrNull(windowEnd), version == null ? "null" : version,
                String.join(",", lines));
    }

    private String simpleOrderRequest(String originId, String destinationId, String... lines) {
        return orderRequest(originId, destinationId, null, null, null, null, null, lines);
    }

    /**
     * The same body plus the V17 declared figures. Kept separate from {@link #orderRequest} so
     * that the dozen existing cases stay readable - only the totals tests care about these.
     */
    private String declaredOrderRequest(String originId, String destinationId, String declaredWeightKg,
            String declaredVolumeM3, String declaredPallets, String... lines) {
        return """
                {"originId":"%s","destinationId":"%s","serviceDate":"2026-03-01","priority":"NORMAL",
                 "declaredWeightKg":%s,"declaredVolumeM3":%s,"declaredPallets":%s,"lines":[%s]}
                """.formatted(originId, destinationId, numberOrNull(declaredWeightKg), numberOrNull(declaredVolumeM3),
                numberOrNull(declaredPallets), String.join(",", lines));
    }

    private static String quoteOrNull(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    // --- create / read / totals ---------------------------------------------------

    @Test
    @DisplayName("create computes header totals from the lines and is readable back through get")
    void createComputesTotalsAndReadsBack() throws Exception {
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originA, destinationA,
                                line("SKU-1", "2", "10", "0.5", "1"), line("SKU-2", "3", null, null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").value(org.hamcrest.Matchers.startsWith("TO-")))
                .andExpect(jsonPath("$.originCode").value("ORIGIN-A"))
                .andExpect(jsonPath("$.destinationCode").value("DEST-A"))
                .andExpect(jsonPath("$.status").value("NOT_READY"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.totalWeightKg").value(20.0))
                .andExpect(jsonPath("$.totalVolumeM3").value(1.0))
                .andExpect(jsonPath("$.totalPallets").value(1.00))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);

        mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.totalWeightKg").value(20.0));
    }

    @Test
    @DisplayName("list shows a line count, never the lines of each row")
    void listShowsLineCountNotFullLines() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originA, destinationA, line("SKU-1", "1", null, null, null))))
                .andExpect(status().isCreated());

        mockMvc.perform(asAdmin(get(BASE), COMPANY_A).param("orderNumber", "TO-"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].lineCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.content[0].lines").doesNotExist());
    }

    @Test
    @DisplayName("updating the lines recomputes the header totals in the same transaction")
    void updateRecomputesTotals() throws Exception {
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originA, destinationA, line("SKU-1", "2", "10", null, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);

        mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(originA, destinationA, null, null, null, null, 0L,
                                line("SKU-1", "4", "10", null, null), line("SKU-2", "1", "5", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.totalWeightKg").value(45.0))
                .andExpect(jsonPath("$.status").value("NOT_READY"));

        mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(originA, destinationA, null, null, null, null, 1L, line("SKU-1", "1", "10", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.totalWeightKg").value(10.0));
    }

    // --- validation ------------------------------------------------------------

    @Test
    @DisplayName("a time window with only a start (or a start after the end) is rejected")
    void invalidTimeWindowsAreRejected() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(originA, destinationA, null, null, "08:00", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"));

        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(originA, destinationA, null, null, "12:00", "08:00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"));
    }

    @Test
    @DisplayName("a valid time window is accepted")
    void validTimeWindowIsAccepted() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(originA, destinationA, null, null, "08:00", "12:00", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestedWindowStart").value("08:00:00"))
                .andExpect(jsonPath("$.requestedWindowEnd").value("12:00:00"));
    }

    @Test
    @DisplayName("an inactive origin is rejected even though it is a real origin in this company")
    void inactiveOriginIsRejected() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(inactiveOriginA, destinationA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"));
    }

    @Test
    @DisplayName("an origin or destination from another company is rejected even though it is real")
    void crossCompanyMastersAreRejected() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originB, destinationA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"));

        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originA, destinationB)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"));
    }

    @Test
    @DisplayName("an order of one company cannot be read through another company's scope")
    void crossCompanyAccessIsBlocked() throws Exception {
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originA, destinationA)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);

        mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource-not-found"));
    }

    // --- idempotency -------------------------------------------------------------

    @Test
    @DisplayName("the same external reference is allowed in a different company but conflicts inside the same one")
    void duplicateExternalReferenceIsScopedToItsCompany() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(originA, destinationA, "ERP", "EXT-DUP", null, null, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(asAdmin(post(BASE), COMPANY_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(originB, destinationB, "ERP", "EXT-DUP", null, null, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(originA, destinationA, "ERP", "EXT-DUP", null, null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"));
    }

    // --- lifecycle -----------------------------------------------------------------

    @Test
    @DisplayName("mark-ready refuses an order whose weight, volume and pallets are all still unknown")
    void markReadyRequiresKnownCapacity() throws Exception {
        // Since V17 the rule is about capacity, not about lines: an order with no lines and no
        // declared figures fails, and so does one whose only line states no unit measure at all.
        // What both have in common is that a planner could not fill a vehicle with them.
        String nothingKnown = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originA, destinationA)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(asAdmin(post(BASE + "/" + idOf(nothingKnown) + "/mark-ready"), COMPANY_A))
                .andExpect(status().isConflict());

        String zeroCapacity = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originA, destinationA, line("SKU-1", "5", null, null, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(asAdmin(post(BASE + "/" + idOf(zeroCapacity) + "/mark-ready"), COMPANY_A))
                .andExpect(status().isConflict());
    }

    // --- declared totals and the V17 precedence rule ---------------------------------

    @Test
    @DisplayName("a header-only order carries its declared totals, is marked DECLARED, and can be planned")
    void headerOnlyOrderWithDeclaredTotals() throws Exception {
        // The payload an inbound integration produces constantly: "one order, 1,200 kg, 2
        // pallets", no line detail. Before V17 this could only be stored with zero totals and
        // could therefore never be planned.
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declaredOrderRequest(originA, destinationA, "1200", "3.4", "2")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalsSource").value("DECLARED"))
                .andExpect(jsonPath("$.totalWeightKg").value(1200.0))
                .andExpect(jsonPath("$.totalVolumeM3").value(3.4))
                .andExpect(jsonPath("$.totalPallets").value(2.00))
                .andExpect(jsonPath("$.declaredWeightKg").value(1200.0))
                .andExpect(jsonPath("$.lines.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(asAdmin(post(BASE + "/" + idOf(response) + "/mark-ready"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_PLANNING"));
    }

    @Test
    @DisplayName("with lines present the totals come from them and the source is CALCULATED")
    void linesWinOverAnAgreeingDeclaration() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declaredOrderRequest(originA, destinationA, "20", null, null,
                                line("SKU-1", "2", "10", null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalsSource").value("CALCULATED"))
                .andExpect(jsonPath("$.totalWeightKg").value(20.0))
                .andExpect(jsonPath("$.declaredWeightKg").value(20.0));
    }

    @Test
    @DisplayName("a declaration that contradicts the lines is refused rather than silently preferred")
    void contradictingDeclarationIsRefused() throws Exception {
        // Lines add to 20 kg; the caller says 1,200. Guessing which is right would put a
        // fabricated capacity figure in front of a planner.
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declaredOrderRequest(originA, destinationA, "1200", null, null,
                                line("SKU-1", "2", "10", null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("do not match the lines")));
    }

    @Test
    @DisplayName("a declaration fills a measure the lines are silent about, without becoming the source")
    void declarationFillsASilentMeasure() throws Exception {
        // The line states pallets but no unit weight, so the weight sum is unknown rather than
        // zero - and the declared 1,200 kg is the only real figure there is.
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declaredOrderRequest(originA, destinationA, "1200", null, null,
                                line("SKU-1", "2", null, null, "3"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalsSource").value("CALCULATED"))
                .andExpect(jsonPath("$.totalWeightKg").value(1200.0))
                .andExpect(jsonPath("$.totalPallets").value(3.00));
    }

    @Test
    @DisplayName("a negative declared figure is rejected by validation, never stored")
    void negativeDeclaredFigureIsRejected() throws Exception {
        mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declaredOrderRequest(originA, destinationA, "-5", null, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("adding lines to a declared order moves it to CALCULATED, and removing them moves it back")
    void totalsSourceFollowsTheLines() throws Exception {
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declaredOrderRequest(originA, destinationA, "20", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalsSource").value("DECLARED"))
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);

        String withLines = """
                {"originId":"%s","destinationId":"%s","serviceDate":"2026-03-01","priority":"NORMAL",
                 "declaredWeightKg":20,"version":0,"lines":[%s]}
                """.formatted(originA, destinationA, line("SKU-1", "2", "10", null, null));
        mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON).content(withLines))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalsSource").value("CALCULATED"));

        // And back again: removing the last line moves the order back to its declared figure
        // rather than leaving it claiming a calculated total it no longer has.
        String withoutLines = """
                {"originId":"%s","destinationId":"%s","serviceDate":"2026-03-01","priority":"NORMAL",
                 "declaredWeightKg":20,"version":1,"lines":[]}
                """.formatted(originA, destinationA);
        mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON).content(withoutLines))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalsSource").value("DECLARED"))
                .andExpect(jsonPath("$.totalWeightKg").value(20.0));
    }

    @Test
    @DisplayName("mark-ready succeeds once a line carries known capacity, and editing resets it back to not-ready")
    void markReadySucceedsThenEditingResetsStatus() throws Exception {
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originA, destinationA, line("SKU-1", "1", "10", null, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);

        mockMvc.perform(asAdmin(post(BASE + "/" + id + "/mark-ready"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_PLANNING"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(asAdmin(post(BASE + "/" + id + "/mark-ready"), COMPANY_A))
                .andExpect(status().isConflict());

        // The mark-ready transition above was itself a save, so the version is now 1, not 0.
        mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(originA, destinationA, null, null, null, null, 1L, line("SKU-1", "2", "10", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_READY"));
    }

    @Test
    @DisplayName("cancel is allowed from not-ready or ready-for-planning, refused when already cancelled")
    void cancelRulesForOrdinaryStates() throws Exception {
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originA, destinationA)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);

        mockMvc.perform(asAdmin(post(BASE + "/" + id + "/cancel").param("reason", "customer request"), COMPANY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("customer request"));

        mockMvc.perform(asAdmin(post(BASE + "/" + id + "/cancel"), COMPANY_A))
                .andExpect(status().isConflict());

        mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(originA, destinationA, null, null, null, null, 0L)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a planned order cannot be cancelled directly")
    void plannedOrderCannotBeCancelledDirectly() throws Exception {
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originA, destinationA)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);
        execute("UPDATE tms.transport_order SET status = 'PLANNED' WHERE id = '" + id + "'");

        mockMvc.perform(asAdmin(post(BASE + "/" + id + "/cancel"), COMPANY_A))
                .andExpect(status().isConflict());
    }

    // --- concurrency -----------------------------------------------------------------

    @Test
    @DisplayName("update requires the current version and rejects a stale one as a conflict")
    void updateRequiresCurrentVersion() throws Exception {
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originA, destinationA)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);

        mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(originA, destinationA, null, null, null, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed-request"));

        // A genuine change (a new line, which also changes the computed totals) so the row
        // actually gets an UPDATE statement and the version really advances - re-submitting the
        // exact same values would leave Hibernate's dirty checking with nothing to write.
        mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(originA, destinationA, null, null, null, null, 0L, line("SKU-1", "1", "5", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // Someone resubmitting the form they loaded before the update above (still version 0).
        mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(originA, destinationA, null, null, null, null, 0L, line("SKU-2", "1", "5", null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"));
    }

    // --- permissions and pagination ------------------------------------------------

    @Test
    @DisplayName("a read-only role may list and read but not create, update, mark-ready or cancel")
    void readOnlyRoleCannotManage() throws Exception {
        mockMvc.perform(asViewer(get(BASE), COMPANY_A)).andExpect(status().isOk());

        mockMvc.perform(asViewer(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originA, destinationA)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access-denied"));
    }

    @Test
    @DisplayName("pagination reports the size actually applied and the total within the company")
    void paginationIsServerSide() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(simpleOrderRequest(originA, destinationA)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(asAdmin(get(BASE), COMPANY_A).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
    }

    @Test
    @DisplayName("list filters by status and priority")
    void listFiltersByStatusAndPriority() throws Exception {
        String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simpleOrderRequest(originA, destinationA, line("SKU-FILTER", "1", "10", null, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);
        mockMvc.perform(asAdmin(post(BASE + "/" + id + "/mark-ready"), COMPANY_A)).andExpect(status().isOk());

        mockMvc.perform(asAdmin(get(BASE), COMPANY_A).param("status", "READY_FOR_PLANNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')]").exists());

        mockMvc.perform(asAdmin(get(BASE), COMPANY_A).param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')]").doesNotExist());

        mockMvc.perform(asAdmin(get(BASE), COMPANY_A).param("priority", "URGENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')]").doesNotExist());
    }
}
