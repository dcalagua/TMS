package com.ebim.tms.fleet.api;

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
 * The bulk Vehicle import end to end: real HTTP, real security filters, real PostgreSQL. Mirrors
 * {@code com.ebim.tms.orders.api.OrderImportApiIntegrationTest}, adapted for one row = one vehicle,
 * for the absence of an {@code externalSource} request parameter, and for the two independent
 * idempotency keys ({@code code} or {@code licensePlate}) and the two master references
 * ({@code carrierCode}, {@code vehicleTypeCode}) this import resolves.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(VehicleImportApiIntegrationTest.JwtDecoderOverride.class)
class VehicleImportApiIntegrationTest {

    private static final String BASE = "/api/v1/fleet/vehicles/import";

    private static final UUID ORGANIZATION = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000e2");

    private static final String HEADER = "code,licensePlate,carrierCode,vehicleTypeCode,maxWeightOverrideKg,"
            + "maxVolumeOverrideM3,maxPalletsOverride,availabilityStatus,externalReference";

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_vehicle_import_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'VEH-ORG', 'Vehicle Import Organization');

                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'VEH-A', 'Company A', 'America/Lima'),
                    ('%s', '%s', 'VEH-B', 'Company B', 'America/Lima');

                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'veh.admin@example.invalid', 'VEH Admin'),
                    ('%s', 'veh.viewer@example.invalid', 'VEH Viewer');

                -- Company A's own vehicle type and carrier, plus Company B's own of each, so a
                -- real cross-tenant code exists for both references this import resolves.
                INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3) VALUES
                    ('%s', 'VT-A', 'Type A', 1000, 10),
                    ('%s', 'VT-B', 'Type B', 1000, 10);
                INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value) VALUES
                    ('%s', 'CARR-A', 'Carrier A', 'RUC', '10000000001'),
                    ('%s', 'CARR-B', 'Carrier B', 'RUC', '10000000002');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION, ADMIN_AUTH,
                VIEWER_AUTH, COMPANY_A, COMPANY_B, COMPANY_A, COMPANY_B));

        membership("veh.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("veh.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("veh.viewer@example.invalid", COMPANY_A, "VIEWER");
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
            throw new IllegalStateException("could not seed the vehicle import fixture", failed);
        }
    }

    private static long count(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not read the vehicle import fixture", failed);
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
            throw new IllegalStateException("could not read the vehicle import fixture", failed);
        }
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
    }

    // --- request helpers --------------------------------------------------------------

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "vehicles.csv", "text/csv",
                (HEADER + "\n" + body).getBytes(StandardCharsets.UTF_8));
    }

    /** code,licensePlate,carrierCode,vehicleTypeCode,maxWeightOverrideKg,maxVolumeOverrideM3,
     * maxPalletsOverride,availabilityStatus,externalReference */
    private static String vehicle(String code, String plate, String carrierCode, String vehicleTypeCode) {
        String[] fields = new String[9];
        fields[0] = code;
        fields[1] = plate;
        fields[2] = carrierCode == null ? "" : carrierCode;
        fields[3] = vehicleTypeCode == null ? "" : vehicleTypeCode;
        fields[4] = "";
        fields[5] = "";
        fields[6] = "";
        fields[7] = "";
        fields[8] = "";
        return String.join(",", fields) + "\n";
    }

    private static String vehicle(String code, String plate) {
        return vehicle(code, plate, "CARR-A", "VT-A");
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
                                org.hamcrest.Matchers.containsString("tms-vehicles-import-template.xlsx")));

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
        long before = count("SELECT count(*) FROM tms.vehicle WHERE company_id = '" + COMPANY_A + "'");

        mockMvc.perform(preview(csv(vehicle("PRV-1", "PRV-0001")), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.items[0].code").value("PRV-1"));

        assertThat(count("SELECT count(*) FROM tms.vehicle WHERE company_id = '" + COMPANY_A + "'"))
                .isEqualTo(before);
        assertThat(count("SELECT count(*) FROM tms.import_batch WHERE entity_type = 'VEHICLE'")).isZero();
    }

    @Test
    @DisplayName("a viewer may preview but may not apply")
    void previewIsReadPermissionAndApplyIsManage() throws Exception {
        mockMvc.perform(preview(csv(vehicle("PERM-1", "PERM-0001")), COMPANY_A, viewerToken))
                .andExpect(status().isOk());

        mockMvc.perform(apply(csv(vehicle("PERM-1", "PERM-0001")), COMPANY_A, viewerToken))
                .andExpect(status().isForbidden());
    }

    // --- apply --------------------------------------------------------------------------

    @Test
    @DisplayName("applying creates the vehicles and records a batch")
    void applyCreatesVehiclesAndAnAuditRow() throws Exception {
        String body = vehicle("APP-1", "APP-0001") + vehicle("APP-2", "APP-0002");

        mockMvc.perform(apply(csv(body), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.createdCount").value(2))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.batchId").exists());

        assertThat(count("SELECT count(*) FROM tms.vehicle WHERE company_id = '" + COMPANY_A
                + "' AND code IN ('APP-1', 'APP-2')")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM tms.import_batch WHERE company_id = '" + COMPANY_A
                + "' AND entity_type = 'VEHICLE' AND created_count = 2")).isEqualTo(1);
    }

    @Test
    @DisplayName("re-applying the same file creates nothing and reports the vehicle as already imported")
    void reapplyingIsIdempotent() throws Exception {
        MockMultipartFile file = csv(vehicle("IDEM-1", "IDEM-0001"));

        mockMvc.perform(apply(file, COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1));

        mockMvc.perform(apply(file, COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.items[0].outcome").value("SKIPPED_DUPLICATE"));

        assertThat(count("SELECT count(*) FROM tms.vehicle WHERE company_id = '" + COMPANY_A
                + "' AND code = 'IDEM-1'")).isEqualTo(1);
    }

    @Test
    @DisplayName("re-applying with the same license plate under a new code is also idempotent")
    void reapplyingByLicensePlateIsIdempotent() throws Exception {
        mockMvc.perform(apply(csv(vehicle("PLATE-1", "PLATE-0001")), COMPANY_A, adminToken))
                .andExpect(jsonPath("$.createdCount").value(1));

        // Same plate, different code: still matched as a duplicate by the plate, the second
        // independent idempotency key.
        mockMvc.perform(apply(csv(vehicle("PLATE-2", "PLATE-0001")), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.items[0].outcome").value("SKIPPED_DUPLICATE"));

        assertThat(count("SELECT count(*) FROM tms.vehicle WHERE company_id = '" + COMPANY_A
                + "' AND license_plate = 'PLATE-0001'")).isEqualTo(1);
    }

    // --- refusals write nothing ------------------------------------------------------------

    @Test
    @DisplayName("a file with one bad row imports none of its good ones either")
    void oneBadRowRejectsTheWholeFile() throws Exception {
        long before = count("SELECT count(*) FROM tms.vehicle WHERE company_id = '" + COMPANY_A + "'");
        String body = vehicle("GOOD-1", "GOOD-0001") + vehicle("BAD-1", "BAD-0001", "CARR-A", null);

        mockMvc.perform(apply(csv(body), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.issues[0].rowNumber").value(3))
                .andExpect(jsonPath("$.issues[0].column").value("vehicleTypeCode"));

        assertThat(count("SELECT count(*) FROM tms.vehicle WHERE company_id = '" + COMPANY_A + "'"))
                .isEqualTo(before);
        assertThat(count("SELECT count(*) FROM tms.import_batch WHERE entity_type = 'VEHICLE'"
                + " AND company_id = '" + COMPANY_A + "'")).isZero();
    }

    @Test
    @DisplayName("another company's carrier code cannot be reached, and is reported like any unknown code")
    void crossTenantCarrierCodeIsRefused() throws Exception {
        // CARR-B is a real, active carrier - of Company B. Company A must not be able to name it.
        String body = vehicle("XTEN-1", "XTEN-0001", "CARR-B", "VT-A");

        mockMvc.perform(apply(csv(body), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.issues[0].column").value("carrierCode"))
                .andExpect(jsonPath("$.issues[0].message")
                        .value("'CARR-B' does not match a carrier in this company."));

        assertThat(count("SELECT count(*) FROM tms.vehicle WHERE code = 'XTEN-1'")).isZero();
    }

    @Test
    @DisplayName("another company's vehicle type code cannot be reached, and is reported like any unknown code")
    void crossTenantVehicleTypeCodeIsRefused() throws Exception {
        // VT-B is a real, active vehicle type - of Company B. Company A must not be able to name it.
        String body = vehicle("XTEN-2", "XTEN-0002", "CARR-A", "VT-B");

        mockMvc.perform(apply(csv(body), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.issues[0].column").value("vehicleTypeCode"))
                .andExpect(jsonPath("$.issues[0].message")
                        .value("'VT-B' does not match a vehicle type in this company."));

        assertThat(count("SELECT count(*) FROM tms.vehicle WHERE code = 'XTEN-2'")).isZero();
    }

    @Test
    @DisplayName("a vehicle type code that simply does not exist anywhere is rejected the same way")
    void unknownVehicleTypeCodeIsRejected() throws Exception {
        String body = vehicle("NOPE-1", "NOPE-0001", "CARR-A", "NOT-A-REAL-TYPE");

        mockMvc.perform(apply(csv(body), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.issues[0].column").value("vehicleTypeCode"))
                .andExpect(jsonPath("$.issues[0].message")
                        .value("'NOT-A-REAL-TYPE' does not match a vehicle type in this company."));
    }

    @Test
    @DisplayName("a blank carrier code is accepted as an owned-fleet vehicle")
    void blankCarrierCodeIsAcceptedAsOwnedFleet() throws Exception {
        String body = vehicle("OWNED-1", "OWNED-0001", null, "VT-A");

        mockMvc.perform(apply(csv(body), COMPANY_A, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.items[0].carrierCode").value(org.hamcrest.Matchers.nullValue()));

        String carrierId = scalar("SELECT carrier_id FROM tms.vehicle WHERE company_id = '" + COMPANY_A
                + "' AND code = 'OWNED-1'");
        assertThat(carrierId).isNull();
    }
}
