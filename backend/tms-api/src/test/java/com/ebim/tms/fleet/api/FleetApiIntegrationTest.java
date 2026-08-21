package com.ebim.tms.fleet.api;

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
import org.junit.jupiter.api.Nested;
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
 * The carrier/vehicle-type/vehicle vertical slice, exercised end to end through the real HTTP
 * filter chain and a real, freshly migrated PostgreSQL - the same proof
 * {@code OriginZoneApiIntegrationTest} (masterdata module) gives its own slice, extended to the
 * fleet module's controller/service/repository code this step adds.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FleetApiIntegrationTest.JwtDecoderOverride.class)
class FleetApiIntegrationTest {

    private static final UUID ORGANIZATION = UUID.fromString("33333333-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("33333333-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("33333333-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("33333333-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("33333333-0000-4000-8000-0000000000e2");

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

    /**
     * A company administrator with a membership (and {@code COMPANY_ADMIN}) in <em>both</em>
     * companies, and a read-only viewer with a membership (and {@code VIEWER}) in company A
     * only - the same fixture shape {@code OriginZoneApiIntegrationTest} uses, enough to prove
     * every case the step brief asks for.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_fleet_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES
                    ('%s', 'FL-ORG', 'Fleet Organization');

                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'FL-A', 'Company A', 'America/Lima'),
                    ('%s', '%s', 'FL-B', 'Company B', 'America/Lima');

                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'fl.admin@example.invalid', 'FL Admin'),
                    ('%s', 'fl.viewer@example.invalid', 'FL Viewer');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION, ADMIN_AUTH, VIEWER_AUTH));

        membership("fl.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("fl.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("fl.viewer@example.invalid", COMPANY_A, "VIEWER");
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
            throw new IllegalStateException("could not seed the fleet fixture", failed);
        }
    }

    @BeforeEach
    void mintTokens() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        viewerToken = TestJwts.validFor(VIEWER_AUTH);
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + adminToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder builder, UUID companyId) {
        return builder.header("Authorization", "Bearer " + viewerToken)
                .header(ApiHeaders.COMPANY_ID, companyId.toString());
    }

    private static String idOf(String jsonResponse) {
        return JsonPath.read(jsonResponse, "$.id");
    }

    @Nested
    @DisplayName("carriers")
    class Carriers {

        private static final String BASE = "/api/v1/fleet/carriers";

        private String carrierRequest(String code, String taxIdValue) {
            return """
                    {"code":"%s","businessName":"%s Transport S.A.","taxIdType":"RUC","taxIdValue":"%s",
                     "contactName":"Jane Doe","phone":"+51 999 999 999","email":"ops@%s.example.test"}
                    """.formatted(code, code, taxIdValue, code.toLowerCase(java.util.Locale.ROOT));
        }

        @Test
        @DisplayName("create normalizes the code and is readable back through list and get")
        void createNormalizesCodeAndIsListed() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(carrierRequest("acme-transport", "20100000001")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("ACME-TRANSPORT"))
                    .andExpect(jsonPath("$.taxIdType").value("RUC"))
                    .andExpect(jsonPath("$.email").value("ops@acme-transport.example.test"))
                    .andExpect(jsonPath("$.active").value(true));

            mockMvc.perform(asAdmin(get(BASE), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.code == 'ACME-TRANSPORT')]").exists());
        }

        @Test
        @DisplayName("external reference is optional, round-trips when present, and is rejected when blank")
        void externalReferenceRoundTrips() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"extref-carrier","businessName":"Extref Transport S.A.","taxIdType":"RUC",
                                     "taxIdValue":"20100000020","externalReference":"ERP-CARR-77"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.externalReference").value("ERP-CARR-77"));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(carrierRequest("no-extref-carrier", "20100000021")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.externalReference").doesNotExist());
        }

        @Test
        @DisplayName("the same code and tax id are allowed in a different company but conflict inside the same one")
        void duplicateCodeAndTaxIdAreScoped() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(carrierRequest("dup-carrier", "20100000002")))
                    .andExpect(status().isCreated());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(carrierRequest("dup-carrier", "20100000002")))
                    .andExpect(status().isCreated());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(carrierRequest("dup-carrier", "20100000099")))
                    .andExpect(status().isConflict());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(carrierRequest("different-code", "20100000002")))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("a malformed email is rejected with a field-level error")
        void malformedEmailIsRejected() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"bad-email","businessName":"Bad Email SA","taxIdType":"RUC",
                                     "taxIdValue":"20100000003","email":"not-an-email"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("a carrier of one company cannot be read through another company's scope")
        void crossCompanyAccessIsBlocked() throws Exception {
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(carrierRequest("isolated", "20100000004")))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = idOf(response);

            mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_B))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));
        }

        @Test
        @DisplayName("a read-only role may list but not create")
        void readOnlyRoleCannotManage() throws Exception {
            mockMvc.perform(asViewer(get(BASE), COMPANY_A)).andExpect(status().isOk());

            mockMvc.perform(asViewer(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(carrierRequest("forbidden", "20100000005")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));
        }

        @Test
        @DisplayName("deactivate and activate toggle state and are reflected in the active filter")
        void deactivateAndActivate() throws Exception {
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(carrierRequest("togglable", "20100000006")))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = idOf(response);

            mockMvc.perform(asAdmin(post(BASE + "/" + id + "/deactivate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
            mockMvc.perform(asAdmin(get(BASE), COMPANY_A).param("active", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.code == 'TOGGLABLE')]").doesNotExist());

            mockMvc.perform(asAdmin(post(BASE + "/" + id + "/activate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(true));
        }
    }

    @Nested
    @DisplayName("vehicle types")
    class VehicleTypes {

        private static final String BASE = "/api/v1/fleet/vehicle-types";

        private String vehicleTypeRequest(String code, double maxWeightKg, double maxVolumeM3, int maxPallets) {
            return """
                    {"code":"%s","name":"%s Type","maxWeightKg":%s,"maxVolumeM3":%s,"maxPallets":%s,
                     "temperatureControlled":false}
                    """.formatted(code, code, maxWeightKg, maxVolumeM3, maxPallets);
        }

        @Test
        @DisplayName("create normalizes the code and is readable back through list and get")
        void createNormalizesCodeAndIsListed() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleTypeRequest("truck-10t", 10000, 40, 20)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("TRUCK-10T"))
                    .andExpect(jsonPath("$.maxWeightKg").value(10000))
                    .andExpect(jsonPath("$.maxPallets").value(20));
        }

        @Test
        @DisplayName("zero or negative weight/volume is rejected with a field-level error")
        void zeroOrNegativeCapacityIsRejected() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleTypeRequest("zero-weight", 0, 10, 0)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation-failed"))
                    .andExpect(jsonPath("$.errors[?(@.field == 'maxWeightKg')]").exists());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleTypeRequest("neg-pallets", 1000, 10, -1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation-failed"))
                    .andExpect(jsonPath("$.errors[?(@.field == 'maxPallets')]").exists());
        }

        @Test
        @DisplayName("zero pallets is a legitimate capacity, not rejected")
        void zeroPalletsIsAccepted() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleTypeRequest("tanker", 15000, 20, 0)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.maxPallets").value(0));
        }

        @Test
        @DisplayName("a temperature range without temperatureControlled is rejected as malformed")
        void temperatureRangeRequiresControlledFlag() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"not-controlled","name":"Not controlled","maxWeightKg":1000,
                                     "maxVolumeM3":10,"maxPallets":0,"temperatureControlled":false,
                                     "minTemperatureCelsius":2}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"inverted","name":"Inverted","maxWeightKg":1000,
                                     "maxVolumeM3":10,"maxPallets":0,"temperatureControlled":true,
                                     "minTemperatureCelsius":5,"maxTemperatureCelsius":2}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("a read-only role may list but not create")
        void readOnlyRoleCannotManage() throws Exception {
            mockMvc.perform(asViewer(get(BASE), COMPANY_A)).andExpect(status().isOk());

            mockMvc.perform(asViewer(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleTypeRequest("forbidden", 1000, 10, 0)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("vehicles")
    class Vehicles {

        private static final String BASE = "/api/v1/fleet/vehicles";
        private static final String TYPES_BASE = "/api/v1/fleet/vehicle-types";
        private static final String CARRIERS_BASE = "/api/v1/fleet/carriers";

        private String createVehicleType(UUID companyId, String code, double maxWeightKg, double maxVolumeM3,
                int maxPallets) throws Exception {
            String response = mockMvc.perform(asAdmin(post(TYPES_BASE), companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"%s","name":"%s Type","maxWeightKg":%s,"maxVolumeM3":%s,
                                     "maxPallets":%s,"temperatureControlled":false}
                                    """.formatted(code, code, maxWeightKg, maxVolumeM3, maxPallets)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return idOf(response);
        }

        private String createCarrier(UUID companyId, String code, String taxIdValue) throws Exception {
            String response = mockMvc.perform(asAdmin(post(CARRIERS_BASE), companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"%s","businessName":"%s SA","taxIdType":"RUC","taxIdValue":"%s"}
                                    """.formatted(code, code, taxIdValue)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return idOf(response);
        }

        private String vehicleRequest(String code, String plate, String vehicleTypeId, String carrierId) {
            String carrierField = carrierId == null ? "null" : "\"" + carrierId + "\"";
            return """
                    {"code":"%s","licensePlate":"%s","carrierId":%s,"vehicleTypeId":"%s",
                     "availabilityStatus":"AVAILABLE"}
                    """.formatted(code, plate, carrierField, vehicleTypeId);
        }

        @Test
        @DisplayName("create resolves carrier/type names and computes the effective capacity from the type when no override is set")
        void createResolvesEffectiveCapacityFromType() throws Exception {
            String typeId = createVehicleType(COMPANY_A, "type-10t", 10000, 40, 20);
            String carrierId = createCarrier(COMPANY_A, "carrier-1", "20100000010");

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleRequest("truck-1", "abc-123", typeId, carrierId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("TRUCK-1"))
                    .andExpect(jsonPath("$.licensePlate").value("ABC-123"))
                    .andExpect(jsonPath("$.vehicleTypeCode").value("TYPE-10T"))
                    .andExpect(jsonPath("$.carrierCode").value("CARRIER-1"))
                    .andExpect(jsonPath("$.effectiveMaxWeightKg").value(10000))
                    .andExpect(jsonPath("$.effectiveMaxVolumeM3").value(40))
                    .andExpect(jsonPath("$.effectiveMaxPallets").value(20))
                    .andExpect(jsonPath("$.maxWeightOverrideKg").doesNotExist());
        }

        @Test
        @DisplayName("external reference is optional and round-trips when present")
        void externalReferenceRoundTrips() throws Exception {
            String typeId = createVehicleType(COMPANY_A, "type-extref", 10000, 40, 20);

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"extref-truck","licensePlate":"EXT-001","vehicleTypeId":"%s",
                                     "availabilityStatus":"AVAILABLE","externalReference":"ERP-VEH-77"}
                                    """.formatted(typeId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.externalReference").value("ERP-VEH-77"));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleRequest("no-extref-truck", "ext-002", typeId, null)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.externalReference").doesNotExist());
        }

        @Test
        @DisplayName("a per-field override takes precedence over the type default, leaving the other fields at the type default")
        void effectiveCapacityPrefersVehicleOverridePerField() throws Exception {
            String typeId = createVehicleType(COMPANY_A, "type-8t", 8000, 30, 16);

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"override-truck","licensePlate":"OVR-001","vehicleTypeId":"%s",
                                     "maxWeightOverrideKg":9500,"availabilityStatus":"AVAILABLE"}
                                    """.formatted(typeId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.maxWeightOverrideKg").value(9500))
                    .andExpect(jsonPath("$.effectiveMaxWeightKg").value(9500))
                    // volume and pallets were not overridden: still the type's defaults
                    .andExpect(jsonPath("$.effectiveMaxVolumeM3").value(30))
                    .andExpect(jsonPath("$.effectiveMaxPallets").value(16));
        }

        @Test
        @DisplayName("duplicate code and duplicate license plate are each rejected as a conflict, scoped per company")
        void duplicateCodeAndPlateAreScoped() throws Exception {
            String typeA = createVehicleType(COMPANY_A, "type-a", 10000, 40, 20);
            String typeB = createVehicleType(COMPANY_B, "type-b", 10000, 40, 20);

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleRequest("dup-veh", "dup-123", typeA, null)))
                    .andExpect(status().isCreated());

            // same code and plate in a different company: allowed
            mockMvc.perform(asAdmin(post(BASE), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleRequest("dup-veh", "dup-123", typeB, null)))
                    .andExpect(status().isCreated());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleRequest("dup-veh", "other-999", typeA, null)))
                    .andExpect(status().isConflict());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleRequest("other-code", "dup-123", typeA, null)))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("a vehicle type or carrier from another company is rejected as malformed, never silently cross-assigned")
        void crossCompanyAssignmentIsRejected() throws Exception {
            String typeB = createVehicleType(COMPANY_B, "type-b2", 10000, 40, 20);
            String carrierB = createCarrier(COMPANY_B, "carrier-b2", "20100000011");
            String typeA = createVehicleType(COMPANY_A, "type-a2", 10000, 40, 20);

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleRequest("cross-type", "crt-001", typeB, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleRequest("cross-carrier", "crc-001", typeA, carrierB)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("a vehicle of one company cannot be read through another company's scope")
        void crossCompanyAccessIsBlocked() throws Exception {
            String typeId = createVehicleType(COMPANY_A, "type-iso", 10000, 40, 20);
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleRequest("isolated", "iso-001", typeId, null)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = idOf(response);

            mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_B))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a read-only role may list but not create")
        void readOnlyRoleCannotManage() throws Exception {
            mockMvc.perform(asViewer(get(BASE), COMPANY_A)).andExpect(status().isOk());

            String typeId = createVehicleType(COMPANY_A, "type-perm", 10000, 40, 20);
            mockMvc.perform(asViewer(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleRequest("forbidden", "frb-001", typeId, null)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));
        }

        @Test
        @DisplayName("update re-resolves the effective capacity when the override changes")
        void updateRecomputesEffectiveCapacity() throws Exception {
            String typeId = createVehicleType(COMPANY_A, "type-upd", 12000, 45, 24);
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(vehicleRequest("updatable", "upd-001", typeId, null)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = idOf(response);

            mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"updatable","licensePlate":"UPD-001","vehicleTypeId":"%s",
                                     "maxPalletsOverride":5,"availabilityStatus":"OUT_OF_SERVICE"}
                                    """.formatted(typeId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.effectiveMaxPallets").value(5))
                    .andExpect(jsonPath("$.effectiveMaxWeightKg").value(12000))
                    .andExpect(jsonPath("$.availabilityStatus").value("OUT_OF_SERVICE"));
        }

        @Test
        @DisplayName("pagination reports the size actually applied and the total within the company")
        void paginationIsServerSide() throws Exception {
            String typeId = createVehicleType(COMPANY_A, "type-page", 10000, 40, 20);
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(vehicleRequest("page-" + i, "PAG-00" + i, typeId, null)))
                        .andExpect(status().isCreated());
            }

            mockMvc.perform(asAdmin(get(BASE), COMPANY_A).param("page", "0").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(2))
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
        }
    }

    /**
     * The driver master (migration V26), proved against the real schema: normalization and the
     * three uniqueness rules that depend on it, the derived licence status, tenant isolation, and
     * the {@code fleet.driver:*} permission split.
     */
    @Nested
    @DisplayName("drivers")
    class Drivers {

        private static final String BASE = "/api/v1/fleet/drivers";

        private String driverRequest(String code, String documentNumber, String licenseNumber, String expiresOn) {
            return """
                    {"code":"%s","firstName":"Ana","lastName":"Quispe","documentType":"dni",
                     "documentNumber":"%s","phone":"+51 999 111 222","licenseNumber":"%s",
                     "licenseCategory":"a-iib"%s}
                    """.formatted(code, documentNumber, licenseNumber,
                    expiresOn == null ? "" : ",\"licenseExpiresOn\":\"" + expiresOn + "\"");
        }

        @Test
        @DisplayName("create normalizes code, document and licence, and derives fullName")
        void createNormalizesAndDerives() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(driverRequest("dr-ana", "10000001", "q-100001", null)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("DR-ANA"))
                    .andExpect(jsonPath("$.documentType").value("DNI"))
                    .andExpect(jsonPath("$.licenseNumber").value("Q-100001"))
                    .andExpect(jsonPath("$.licenseCategory").value("A-IIB"))
                    // Names are trimmed, never upper-cased: they are printed on a manifest.
                    .andExpect(jsonPath("$.firstName").value("Ana"))
                    .andExpect(jsonPath("$.fullName").value("Quispe, Ana"))
                    // No expiry on file is UNRECORDED, and that never blocks anything.
                    .andExpect(jsonPath("$.licenseStatus").value("UNRECORDED"))
                    .andExpect(jsonPath("$.active").value(true));

            mockMvc.perform(asAdmin(get(BASE), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.code == 'DR-ANA')]").exists());
        }

        @Test
        @DisplayName("licence status is derived from the expiry date, with the last valid day inclusive")
        void licenceStatusIsDerived() throws Exception {
            java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("America/Lima"));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(driverRequest("dr-expired", "10000002", "q-100002", today.minusDays(1).toString())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.licenseStatus").value("EXPIRED"));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(driverRequest("dr-today", "10000003", "q-100003", today.toString())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.licenseStatus").value("EXPIRING_SOON"));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(driverRequest("dr-valid", "10000004", "q-100004", today.plusYears(2).toString())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.licenseStatus").value("VALID"));

            mockMvc.perform(asAdmin(get(BASE), COMPANY_A).param("licenseStatus", "EXPIRED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.code == 'DR-EXPIRED')]").exists())
                    .andExpect(jsonPath("$.content[?(@.code == 'DR-VALID')]").doesNotExist());
        }

        @Test
        @DisplayName("code, document and licence number are unique per company and free in another one")
        void uniquenessIsCompanyScoped() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(driverRequest("dr-dup", "10000010", "q-100010", null)))
                    .andExpect(status().isCreated());

            // The same person driving for two tenant companies is two rows (ADR-003).
            mockMvc.perform(asAdmin(post(BASE), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(driverRequest("dr-dup", "10000010", "q-100010", null)))
                    .andExpect(status().isCreated());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(driverRequest("dr-dup", "10000011", "q-100011", null)))
                    .andExpect(status().isConflict());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(driverRequest("dr-other", "10000010", "q-100012", null)))
                    .andExpect(status().isConflict());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(driverRequest("dr-third", "10000013", "q-100010", null)))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("a driver of one company cannot be read through another company's scope")
        void crossCompanyAccessIsBlocked() throws Exception {
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(driverRequest("dr-isolated", "10000020", "q-100020", null)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            mockMvc.perform(asAdmin(get(BASE + "/" + idOf(response)), COMPANY_B))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));
        }

        @Test
        @DisplayName("a carrier of another company cannot be attached to a driver")
        void carrierMustBeInTheSameCompany() throws Exception {
            String carrier = mockMvc.perform(asAdmin(post("/api/v1/fleet/carriers"), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"dr-foreign-carrier","businessName":"Foreign Transport S.A.",
                                     "taxIdType":"RUC","taxIdValue":"20100000700"}
                                    """))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"dr-cross","firstName":"Ana","lastName":"Quispe","documentType":"DNI",
                                     "documentNumber":"10000030","licenseNumber":"Q-100030","carrierId":"%s"}
                                    """.formatted(idOf(carrier))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("deactivation keeps the row readable - a retired driver still renders on their trips")
        void deactivationKeepsTheRecord() throws Exception {
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(driverRequest("dr-retired", "10000040", "q-100040", null)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = idOf(response);

            mockMvc.perform(asAdmin(post(BASE + "/" + id + "/deactivate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));

            mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fullName").value("Quispe, Ana"));

            mockMvc.perform(asAdmin(post(BASE + "/" + id + "/activate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        @DisplayName("a read-only role may list but not create")
        void readOnlyRoleCannotManage() throws Exception {
            mockMvc.perform(asViewer(get(BASE), COMPANY_A)).andExpect(status().isOk());

            mockMvc.perform(asViewer(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(driverRequest("dr-forbidden", "10000050", "q-100050", null)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));
        }
    }
}
