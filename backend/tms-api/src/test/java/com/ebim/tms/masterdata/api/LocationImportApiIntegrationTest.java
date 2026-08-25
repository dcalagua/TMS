package com.ebim.tms.masterdata.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
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
 * The bulk Location import end to end: real HTTP, real security filters, real PostgreSQL.
 * Mirrors {@code com.ebim.tms.orders.api.OrderImportApiIntegrationTest}, adapted for one row = one
 * location and for the absence of an {@code externalSource} request parameter.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocationImportApiIntegrationTest.JwtDecoderOverride.class)
class LocationImportApiIntegrationTest {

    private static final String BASE = "/api/v1/masterdata/locations/import";

    private static final UUID ORGANIZATION = UUID.fromString("77777777-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("77777777-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("77777777-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e2");

    private static final String HEADER = "code,name,type,roles,address,addressReference,district,province,"
            + "department,country,timeZone,latitude,longitude,zoneCode,serviceTimeMinutes,externalSystem,"
            + "externalReference";

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_location_import_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'LOC-ORG', 'Location Import Organization');

                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'LOC-A', 'Company A', 'America/Lima'),
                    ('%s', '%s', 'LOC-B', 'Company B', 'America/Lima');

                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'loc.admin@example.invalid', 'LOC Admin'),
                    ('%s', 'loc.viewer@example.invalid', 'LOC Viewer');

                -- Company A's own zone, and Company B's own zone so a real cross-tenant zone code exists.
                INSERT INTO tms.zone (company_id, code, name) VALUES
                    ('%s', 'NORTH', 'North zone'),
                    ('%s', 'ZONE-B', 'Company B zone');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION, ADMIN_AUTH, VIEWER_AUTH,
                COMPANY_A, COMPANY_B));

        membership("loc.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("loc.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("loc.viewer@example.invalid", COMPANY_A, "VIEWER");
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
            throw new IllegalStateException("could not seed the location import fixture", failed);
        }
    }

    private static long count(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the location import fixture", failed);
        }
    }

    private static String scalar(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return null;
            }
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the location import fixture", failed);
        }
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
    }

    // --- request helpers --------------------------------------------------------------

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "locations.csv", "text/csv",
                (HEADER + "\n" + body).getBytes(StandardCharsets.UTF_8));
    }

    /** code,name,type,roles,...(9 blanks)...,zoneCode,serviceTimeMinutes,externalSystem,externalReference */
    private static String location(String code, String roles, String zoneCode) {
        String[] fields = new String[17];
        fields[0] = code;
        fields[1] = "Location " + code;
        fields[2] = "WAREHOUSE";
        fields[3] = roles;
        for (int index = 4; index <= 12; index++) {
            fields[index] = "";
        }
        fields[13] = zoneCode == null ? "" : zoneCode;
        fields[14] = "";
        fields[15] = "";
        fields[16] = "";
        return String.join(",", fields) + "\n";
    }

    private static String location(String code) {
        return location(code, "ORIGIN", null);
    }

    /** A row with no name at all, to prove the whole file is refused. */
    private static String locationWithoutName(String code) {
        String[] fields = new String[17];
        fields[0] = code;
        fields[1] = "";
        fields[2] = "WAREHOUSE";
        fields[3] = "ORIGIN";
        for (int index = 4; index <= 16; index++) {
            fields[index] = "";
        }
        return String.join(",", fields) + "\n";
    }

    private RequestBuilder preview(MockMultipartFile file, UUID companyId, String token) {
        return multipart(BASE + "/preview").file(file)
                .header("Authorization", "Bearer " + token)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private RequestBuilder apply(MockMultipartFile file, UUID companyId, String token) {
        return multipart(BASE).file(file)
                .header("Authorization", "Bearer " + token)
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
                                org.hamcrest.Matchers.containsString("tms-locations-import-template.xlsx")));

        mockMvc.perform(get(BASE + "/template").param("format", "CSV")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(ApiHeaders.COMPANY_ID, COMPANY_A.toString()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the template still needs authentication - it is not a quiet second API")
    void templateIsNotPublic() throws Exception {
        mockMvc.perform(get(BASE + "/template")).andExpect(status().isUnauthorized());
    }

    // --- preview writes nothing -----------------------------------------------------------

    @Test
    @DisplayName("a preview reports what would happen and writes nothing at all")
    void previewWritesNothing() throws Exception {
        long before = count("SELECT count(*) FROM tms.location WHERE company_id = '" + COMPANY_A + "'");

        mockMvc.perform(preview(csv(location("PRV-1")), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.items[0].code").value("PRV-1"));

        assertThat(count("SELECT count(*) FROM tms.location WHERE company_id = '" + COMPANY_A + "'"))
                .isEqualTo(before);
        assertThat(count("SELECT count(*) FROM tms.import_batch WHERE entity_type = 'LOCATION'")).isZero();
    }

    @Test
    @DisplayName("a viewer may preview but may not apply")
    void previewIsReadPermissionAndApplyIsManage() throws Exception {
        mockMvc.perform(preview(csv(location("PERM-1")), COMPANY_A, viewerToken))
                .andExpect(status().isOk());

        mockMvc.perform(apply(csv(location("PERM-1")), COMPANY_A, viewerToken))
                .andExpect(status().isForbidden());
    }

    // --- apply --------------------------------------------------------------------------

    @Test
    @DisplayName("applying creates the locations and records a batch")
    void applyCreatesLocationsAndAnAuditRow() throws Exception {
        String body = location("APP-1") + location("APP-2");

        mockMvc.perform(apply(csv(body), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.createdCount").value(2))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.batchId").exists());

        assertThat(count("SELECT count(*) FROM tms.location WHERE company_id = '" + COMPANY_A
                + "' AND code IN ('APP-1', 'APP-2')")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM tms.import_batch WHERE company_id = '" + COMPANY_A
                + "' AND entity_type = 'LOCATION' AND created_count = 2")).isEqualTo(1);
    }

    @Test
    @DisplayName("re-applying the same file creates nothing and reports the location as already imported")
    void reapplyingIsIdempotent() throws Exception {
        MockMultipartFile file = csv(location("IDEM-1"));

        mockMvc.perform(apply(file, COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1));

        mockMvc.perform(apply(file, COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.items[0].outcome").value("SKIPPED_DUPLICATE"));

        assertThat(count("SELECT count(*) FROM tms.location WHERE company_id = '" + COMPANY_A
                + "' AND code = 'IDEM-1'")).isEqualTo(1);
    }

    // --- refusals write nothing ------------------------------------------------------------

    @Test
    @DisplayName("a file with one bad row imports none of its good ones either")
    void oneBadRowRejectsTheWholeFile() throws Exception {
        long before = count("SELECT count(*) FROM tms.location WHERE company_id = '" + COMPANY_A + "'");
        String body = location("GOOD-1") + locationWithoutName("BAD-1");

        mockMvc.perform(apply(csv(body), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.issues[0].rowNumber").value(3))
                .andExpect(jsonPath("$.issues[0].column").value("name"));

        assertThat(count("SELECT count(*) FROM tms.location WHERE company_id = '" + COMPANY_A + "'"))
                .isEqualTo(before);
        assertThat(count("SELECT count(*) FROM tms.import_batch WHERE entity_type = 'LOCATION'"
                + " AND company_id = '" + COMPANY_A + "'")).isZero();
    }

    @Test
    @DisplayName("another company's zone code cannot be reached, and is reported like any unknown code")
    void crossTenantZoneCodeIsRefused() throws Exception {
        // ZONE-B is a real, active zone - of Company B. Company A must not be able to name it.
        String body = location("XTEN-1", "ORIGIN", "ZONE-B");

        mockMvc.perform(apply(csv(body), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.issues[0].column").value("zoneCode"))
                .andExpect(jsonPath("$.issues[0].message")
                        .value("'ZONE-B' does not match a zone in this company."));

        assertThat(count("SELECT count(*) FROM tms.location WHERE code = 'XTEN-1'")).isZero();
    }

    @Test
    @DisplayName("a zone code that resolves in this company is accepted and the location's zone is set")
    void zoneCodeResolvesInCompany() throws Exception {
        mockMvc.perform(apply(csv(location("ZONED-1", "ORIGIN", "NORTH")), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.items[0].zoneCode").value("NORTH"));

        String zoneId = scalar("SELECT zone_id FROM tms.location WHERE company_id = '" + COMPANY_A
                + "' AND code = 'ZONED-1'");
        assertThat(zoneId).isNotNull();
    }

    @Test
    @DisplayName("a location with more than one role is accepted")
    void multipleRolesAccepted() throws Exception {
        mockMvc.perform(apply(csv(location("MULTI-1", "ORIGIN,DESTINATION", null)), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.createdCount").value(1));

        assertThat(count("SELECT count(*) FROM tms.location_role lr JOIN tms.location l ON l.id = lr.location_id"
                + " WHERE l.company_id = '" + COMPANY_A + "' AND l.code = 'MULTI-1'")).isEqualTo(2);
    }
}
