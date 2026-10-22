package com.ebim.tms.fleet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * Fleet compliance documents end to end (migration V72), against a real PostgreSQL.
 *
 * <p>What the unit tests cannot show: that the API runs under RLS, that another company sees none of
 * it, that a viewer reads and cannot write, and - the reason the feature exists - that <b>a truck
 * whose blocking paper expired cannot be put on a trip</b> while one whose paper is only unknown is
 * put on it with a warning, the same answer on the trip, the dispatch gate and the day's work.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FleetComplianceApiIntegrationTest.JwtDecoderOverride.class)
class FleetComplianceApiIntegrationTest {

    private static final String DOCUMENTS = "/api/v1/fleet/documents";
    private static final String POLICY = "/api/v1/fleet/document-policy";
    private static final String PLANNING = "/api/v1/planning";

    private static final UUID ORGANIZATION = UUID.fromString("77777777-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("77777777-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("77777777-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e2");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String jdbcUrl;
    private static String originA;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_fleet_compliance_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("INSERT INTO tms.organization (id, code, name) VALUES ('" + ORGANIZATION
                + "', 'FC-ORG', 'Fleet Compliance Org')");
        execute("INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES ('"
                + COMPANY_A + "', '" + ORGANIZATION + "', 'FC-A', 'Company A', 'America/Guayaquil'), ('"
                + COMPANY_B + "', '" + ORGANIZATION + "', 'FC-B', 'Company B', 'America/Guayaquil')");
        execute("INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES ('" + ADMIN_AUTH
                + "', 'fc.admin@example.invalid', 'FC Admin'), ('" + VIEWER_AUTH
                + "', 'fc.viewer@example.invalid', 'FC Viewer')");
        membership("fc.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("fc.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("fc.viewer@example.invalid", COMPANY_A, "VIEWER");
        originA = idOf("INSERT INTO tms.location (company_id, code, name) VALUES ('" + COMPANY_A
                + "', 'FC-ORIGIN', 'Origin') RETURNING id");
        execute("INSERT INTO tms.location_role (location_id, role) VALUES ('" + originA + "', 'ORIGIN')");
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
    }

    // --- recording and reading -------------------------------------------------------

    @Nested
    @DisplayName("the documents")
    class Documents {

        @Test
        @DisplayName("a document is recorded, its status derived on read, and filtered by that status")
        void recordAndFilter() throws Exception {
            String vehicle = vehicle(COMPANY_A, "REC");
            LocalDate today = LocalDate.now(ZoneId.of("America/Guayaquil"));
            String expired = document(vehicle, "TECHNICAL_INSPECTION", today.minusDays(3));
            String soon = document(vehicle, "MANDATORY_INSURANCE", today.plusDays(10));
            document(vehicle, "REGISTRATION", today.plusDays(200));

            mockMvc.perform(asAdmin(get(DOCUMENTS + "/" + expired), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("EXPIRED"))
                    .andExpect(jsonPath("$.daysToExpiry").value(-3))
                    .andExpect(jsonPath("$.subjectCode").value("PLT-REC"))
                    .andExpect(jsonPath("$.enforcement").value("BLOCK"));

            mockMvc.perform(asAdmin(get(DOCUMENTS).param("vehicleId", vehicle).param("status", "EXPIRING_SOON"),
                            COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(soon));

            // Soonest expiry first by default: the paper that runs out next is the one to chase.
            mockMvc.perform(asAdmin(get(DOCUMENTS).param("vehicleId", vehicle), COMPANY_A))
                    .andExpect(jsonPath("$.content[0].id").value(expired))
                    .andExpect(jsonPath("$.content[1].id").value(soon));

            // An undated document is unrecorded, never expired.
            String undated = document(vehicle, "OPERATING_PERMIT", null);
            mockMvc.perform(asAdmin(get(DOCUMENTS + "/" + undated), COMPANY_A))
                    .andExpect(jsonPath("$.status").value("UNRECORDED"));
        }

        @Test
        @DisplayName("a driver's paper cannot be recorded on a vehicle, nor a vehicle's on a driver")
        void typeMustMatchSubject() throws Exception {
            String vehicle = vehicle(COMPANY_A, "MISMATCH");
            mockMvc.perform(asAdmin(post(DOCUMENTS), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content(documentBody("VEHICLE", vehicle, "MEDICAL_CERTIFICATE", null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("medical certificate")));
        }

        @Test
        @DisplayName("another company sees nothing of it, and cannot record against this company's vehicle")
        void tenantIsolation() throws Exception {
            String vehicle = vehicle(COMPANY_A, "ISO");
            String id = document(vehicle, "REGISTRATION", LocalDate.of(2030, 1, 1));

            mockMvc.perform(asAdmin(get(DOCUMENTS + "/" + id), COMPANY_B)).andExpect(status().isNotFound());
            mockMvc.perform(asAdmin(get(DOCUMENTS).param("vehicleId", vehicle), COMPANY_B))
                    .andExpect(jsonPath("$.content.length()").value(0));
            mockMvc.perform(asAdmin(post(DOCUMENTS), COMPANY_B).contentType(MediaType.APPLICATION_JSON)
                            .content(documentBody("VEHICLE", vehicle, "REGISTRATION", "2030-01-01")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a viewer reads the documents and the policy, and writes neither")
        void viewerReadsOnly() throws Exception {
            String vehicle = vehicle(COMPANY_A, "VIEW");
            document(vehicle, "REGISTRATION", LocalDate.of(2030, 1, 1));

            mockMvc.perform(asViewer(get(DOCUMENTS).param("vehicleId", vehicle), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1));
            mockMvc.perform(asViewer(get(POLICY), COMPANY_A)).andExpect(status().isOk());
            mockMvc.perform(asViewer(post(DOCUMENTS), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content(documentBody("VEHICLE", vehicle, "REGISTRATION", "2030-01-01")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(asViewer(put(POLICY), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"alertDays\":15,\"types\":[]}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a deactivated paper stops counting and is audited")
        void deactivate() throws Exception {
            String vehicle = vehicle(COMPANY_A, "DEACT");
            String id = document(vehicle, "TECHNICAL_INSPECTION", LocalDate.of(2020, 1, 1));

            mockMvc.perform(asAdmin(post(DOCUMENTS + "/" + id + "/deactivate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
            mockMvc.perform(asAdmin(get(DOCUMENTS + "/subjects/VEHICLE/" + vehicle), COMPANY_A))
                    .andExpect(jsonPath("$.documents.length()").value(1))
                    .andExpect(jsonPath("$.findings[?(@.status == 'EXPIRED')]").isEmpty());
            assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_type = 'FLEET_DOCUMENT'"
                    + " AND aggregate_id = '" + id + "'")).isEqualTo(2);
        }
    }

    // --- the policy, the summary and what is missing --------------------------------------

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @DisplayName("the policy")
    class Policy {

        @Test
        @Order(1)
        @DisplayName("nothing is mandatory by default, and the legal papers of a vehicle block when expired")
        void defaults() throws Exception {
            mockMvc.perform(asAdmin(get(POLICY), COMPANY_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.alertDays").value(30))
                    .andExpect(jsonPath("$.types[?(@.documentType == 'TECHNICAL_INSPECTION')].enforcement")
                            .value("BLOCK"))
                    .andExpect(jsonPath("$.types[?(@.documentType == 'CARGO_INSURANCE')].enforcement").value("WARN"))
                    .andExpect(jsonPath("$.types[?(@.mandatory == true)]").isEmpty());
        }

        @Test
        @Order(2)
        @DisplayName("a mandatory type makes every active vehicle without it 'missing', and the window moves the summary")
        void mandatoryAndWindow() throws Exception {
            String vehicle = vehicle(COMPANY_B, "MAND");
            LocalDate today = LocalDate.now(ZoneId.of("America/Guayaquil"));
            documentIn(COMPANY_B, vehicle, "MANDATORY_INSURANCE", today.plusDays(20));

            mockMvc.perform(asAdmin(put(POLICY), COMPANY_B).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"alertDays":15,"types":[
                                      {"documentType":"TECHNICAL_INSPECTION","mandatory":true,"enforcement":"BLOCK"}]}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.alertDays").value(15))
                    .andExpect(jsonPath("$.types[?(@.documentType == 'TECHNICAL_INSPECTION')].configured").value(true));

            mockMvc.perform(asAdmin(get(DOCUMENTS + "/missing"), COMPANY_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.subjectId == '" + vehicle + "')].documentType")
                            .value("TECHNICAL_INSPECTION"));
            // 20 days out with a 15-day window: valid, not about to expire.
            mockMvc.perform(asAdmin(get(DOCUMENTS + "/summary"), COMPANY_B))
                    .andExpect(jsonPath("$.alertDays").value(15))
                    .andExpect(jsonPath("$.valid").value(1))
                    .andExpect(jsonPath("$.expiringSoon").value(0))
                    .andExpect(jsonPath("$.missingMandatory").value(1));
            assertThat(count("SELECT count(*) FROM tms.audit_event WHERE aggregate_type = 'FLEET_DOCUMENT_POLICY'"
                    + " AND company_id = '" + COMPANY_B + "'")).isEqualTo(1);
        }
    }

    // --- the integration with planning and the day's work -------------------------------------

    @Nested
    @DisplayName("planning and the day's work")
    class Enforcement {

        @Test
        @DisplayName("a vehicle with an expired technical inspection cannot be put on a trip; the message names the paper")
        void expiredBlockingPaperRefusesTheVehicle() throws Exception {
            LocalDate date = nextDate();
            String vehicle = vehicle(COMPANY_A, "BLOCKED");
            document(vehicle, "TECHNICAL_INSPECTION", date.minusDays(1));
            String run = newRun(date);

            mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run + "/trips"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tripBody(vehicle, date)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("technical inspection")))
                    .andExpect(jsonPath("$.detail").value(containsString("PLT-BLOCKED")));

            // Renewed: a second, valid paper of the same type wins over the expired one.
            document(vehicle, "TECHNICAL_INSPECTION", date.plusDays(365));
            mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run + "/trips"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tripBody(vehicle, date)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.compliance[?(@.blocking == true)]").isEmpty());
        }

        @Test
        @DisplayName("an expired paper whose policy only warns is assigned, and the trip shows the warning")
        void warningPaperIsShownNotRefused() throws Exception {
            LocalDate date = nextDate();
            String vehicle = vehicle(COMPANY_A, "WARNED");
            document(vehicle, "CARGO_INSURANCE", date.minusDays(5));
            String run = newRun(date);

            mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run + "/trips"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tripBody(vehicle, date)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.compliance[?(@.documentType == 'CARGO_INSURANCE')].status").value("EXPIRED"))
                    .andExpect(jsonPath("$.compliance[?(@.documentType == 'CARGO_INSURANCE')].blocking").value(false))
                    .andExpect(jsonPath("$.compliance[?(@.blocking == true)]").isEmpty());
        }

        @Test
        @DisplayName("a driver whose medical certificate expired under a BLOCK policy cannot be named on a trip")
        void driverPaperBlocks() throws Exception {
            LocalDate date = nextDate();
            String vehicle = vehicle(COMPANY_A, "DRVTRUCK");
            String driver = driver(COMPANY_A, "DRV-MED");
            mockMvc.perform(asAdmin(put(POLICY), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"alertDays":30,"types":[
                                      {"documentType":"MEDICAL_CERTIFICATE","mandatory":false,"enforcement":"BLOCK"}]}
                                    """))
                    .andExpect(status().isOk());
            documentFor("DRIVER", driver, "MEDICAL_CERTIFICATE", date.minusDays(2));
            String run = newRun(date);
            String created = mockMvc.perform(asAdmin(post(PLANNING + "/runs/" + run + "/trips"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content(tripBody(vehicle, date)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String trip = JsonPath.read(created, "$.trip.id");
            Number version = JsonPath.read(created, "$.trip.version");

            mockMvc.perform(asAdmin(put(PLANNING + "/trips/" + trip + "/driver"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"driverId\":\"" + driver + "\",\"version\":" + version + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("medical certificate")));
        }

        @Test
        @DisplayName("in the day's work an expired blocking paper is a blocking conflict; an unknown one only warns")
        void workAssignment() throws Exception {
            LocalDate date = nextDate();
            String blocked = vehicle(COMPANY_A, "WA-BLOCK");
            document(blocked, "MANDATORY_INSURANCE", date.minusDays(1));
            String blockedDay = workDay(date, blocked);
            mockMvc.perform(asAdmin(get("/api/v1/fleet/work-assignments/" + blockedDay), COMPANY_A))
                    .andExpect(jsonPath("$.feasible").value(false))
                    .andExpect(jsonPath("$.conflicts[?(@.reason == 'COMPLIANCE_DOCUMENT_EXPIRED')].blocking")
                            .value(true));
            mockMvc.perform(asAdmin(post("/api/v1/fleet/work-assignments/" + blockedDay + "/confirm"), COMPANY_A))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value(containsString("mandatory insurance")));

            String unknown = vehicle(COMPANY_A, "WA-UNK");
            document(unknown, "REGISTRATION", null);
            mockMvc.perform(asAdmin(put(POLICY), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"alertDays":30,"types":[
                                      {"documentType":"REGISTRATION","mandatory":true,"enforcement":"BLOCK"}]}
                                    """))
                    .andExpect(status().isOk());
            String unknownDay = workDay(date, unknown);
            mockMvc.perform(asAdmin(get("/api/v1/fleet/work-assignments/" + unknownDay), COMPANY_A))
                    .andExpect(jsonPath("$.feasible").value(true))
                    .andExpect(jsonPath("$.conflicts[?(@.reason == 'COMPLIANCE_DOCUMENT_UNRECORDED')].blocking")
                            .value(false));
            mockMvc.perform(asAdmin(post("/api/v1/fleet/work-assignments/" + unknownDay + "/confirm"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"));
        }
    }

    // --- plumbing --------------------------------------------------------------------

    private String document(String vehicleId, String type, LocalDate expiresOn) throws Exception {
        return documentIn(COMPANY_A, vehicleId, type, expiresOn);
    }

    private String documentIn(UUID companyId, String vehicleId, String type, LocalDate expiresOn) throws Exception {
        String response = mockMvc.perform(asAdmin(post(DOCUMENTS), companyId).contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody("VEHICLE", vehicleId, type,
                                expiresOn == null ? null : expiresOn.toString())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private void documentFor(String subject, String subjectId, String type, LocalDate expiresOn) throws Exception {
        mockMvc.perform(asAdmin(post(DOCUMENTS), COMPANY_A).contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody(subject, subjectId, type, expiresOn.toString())))
                .andExpect(status().isCreated());
    }

    private static String documentBody(String subject, String subjectId, String type, String expiresOn) {
        return "{\"subjectType\":\"" + subject + "\",\"subjectId\":\"" + subjectId + "\",\"documentType\":\"" + type
                + "\",\"documentNumber\":\"N-" + SEQUENCE.incrementAndGet() + "\",\"issuer\":\"ANT\""
                + (expiresOn == null ? "" : ",\"expiresOn\":\"" + expiresOn + "\"") + "}";
    }

    private String newRun(LocalDate date) throws Exception {
        String response = mockMvc.perform(asAdmin(post(PLANNING + "/runs"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originId\":\"" + originA + "\",\"planningDate\":\"" + date + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.run.id");
    }

    private static String tripBody(String vehicleId, LocalDate date) {
        String departure = date.atTime(8, 0).atZone(ZoneId.of("America/Guayaquil")).toOffsetDateTime().toString();
        return "{\"vehicleId\":\"" + vehicleId + "\",\"plannedDepartureAt\":\"" + departure + "\",\"version\":0}";
    }

    private String workDay(LocalDate date, String vehicleId) throws Exception {
        String response = mockMvc.perform(asAdmin(post("/api/v1/fleet/work-assignments"), COMPANY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationalDate\":\"" + date + "\",\"vehicleId\":\"" + vehicleId
                                + "\",\"tripIds\":[]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private static LocalDate nextDate() {
        return LocalDate.of(2027, 3, 1).plusDays(SEQUENCE.incrementAndGet());
    }

    private static String vehicle(UUID companyId, String code) {
        String type = idOf("INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg,"
                + " max_volume_m3, max_pallets) VALUES ('" + companyId + "', 'VT-" + code
                + "', 'Rigid', 10000, 40, 20) RETURNING id");
        return idOf("INSERT INTO tms.vehicle (company_id, vehicle_type_id, code, license_plate)"
                + " VALUES ('" + companyId + "', '" + type + "', '" + code + "', 'PLT-" + code
                + "') RETURNING id");
    }

    private static String driver(UUID companyId, String code) {
        return idOf("INSERT INTO tms.driver (company_id, code, first_name, last_name, document_type,"
                + " document_number, license_number) VALUES ('" + companyId + "', '" + code + "', 'A', 'B',"
                + " 'DNI', '" + Math.abs(code.hashCode()) % 100000000 + "', 'L-" + code + "') RETURNING id");
    }

    private static void membership(String email, UUID companyId, String roleCode) {
        execute("INSERT INTO tms.membership (app_user_id, organization_id, company_id)"
                + " SELECT id, '" + ORGANIZATION + "', '" + companyId + "' FROM tms.app_user WHERE email = '"
                + email + "'");
        execute("INSERT INTO tms.membership_role (membership_id, role_id) SELECT m.id, r.id"
                + " FROM tms.membership m JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = '"
                + email + "' JOIN tms.role r ON r.code = '" + roleCode + "' WHERE m.company_id = '"
                + companyId + "'");
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + adminToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + viewerToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private static String idOf(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the fleet compliance fixture", failed);
        }
    }

    private static long count(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the fleet compliance fixture", failed);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the fleet compliance fixture", failed);
        }
    }
}
