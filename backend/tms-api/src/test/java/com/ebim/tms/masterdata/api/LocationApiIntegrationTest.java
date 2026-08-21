package com.ebim.tms.masterdata.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
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
 * The canonical Location slice (migration V14), exercised end to end through the real HTTP filter
 * chain and a real, freshly migrated PostgreSQL - the same proof
 * {@code DestinationFrequencyApiIntegrationTest} gives V7's slice.
 *
 * <p>Beyond the usual tenancy and validation contract, this suite exists to prove the part of
 * {@code docs/architecture/ADR_LOCATION_MODEL.md} that only a database can settle: that the
 * compatibility projections really are created, really are linked, really are kept in step by a
 * write through <em>either</em> API, and really are deactivated rather than deleted.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocationApiIntegrationTest.JwtDecoderOverride.class)
class LocationApiIntegrationTest {

    private static final String BASE = "/api/v1/masterdata/locations";
    private static final String ORIGINS = "/api/v1/masterdata/origins";
    private static final String DESTINATIONS = "/api/v1/masterdata/destinations";

    private static final UUID ORGANIZATION = UUID.fromString("44444444-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("44444444-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("44444444-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("44444444-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("44444444-0000-4000-8000-0000000000e2");

    private static String jdbcUrl;
    private static String zoneInCompanyA;
    private static String zoneInCompanyB;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_location_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES
                    ('%s', 'LOC-ORG', 'Location Organization');

                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'LOC-A', 'Company A', 'America/Lima'),
                    ('%s', '%s', 'LOC-B', 'Company B', 'America/Lima');

                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'loc.admin@example.invalid', 'Location Admin'),
                    ('%s', 'loc.viewer@example.invalid', 'Location Viewer');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION, ADMIN_AUTH, VIEWER_AUTH));

        membership("loc.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("loc.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("loc.viewer@example.invalid", COMPANY_A, "VIEWER");

        zoneInCompanyA = insertZone(COMPANY_A, "LZONE-A");
        zoneInCompanyB = insertZone(COMPANY_B, "LZONE-B");
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

    private static String insertZone(UUID companyId, String code) {
        return singleValue("INSERT INTO tms.zone (company_id, code, name) VALUES ('"
                + companyId + "', '" + code + "', '" + code + " name') RETURNING id");
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed the location fixture", failed);
        }
    }

    private static String singleValue(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        } catch (SQLException failed) {
            throw new IllegalStateException("query failed: " + sql, failed);
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

    private static String locationRequest(String code, String type, String roles, String zoneId,
            String externalSystem, String externalReference) {
        return """
                {"code":"%s","name":"%s Name","type":"%s","roles":%s,
                 "address":"Av. Argentina 1234","addressReference":"Puerta azul",
                 "district":"Callao","province":"Callao","department":"Callao","country":"PE",
                 "timeZone":"America/Lima","latitude":-12.0456,"longitude":-77.0317,
                 "zoneId":%s,"serviceTimeMinutes":25,
                 "externalSystem":%s,"externalReference":%s}
                """.formatted(code, code, type, roles,
                        zoneId == null ? "null" : "\"" + zoneId + "\"",
                        externalSystem == null ? "null" : "\"" + externalSystem + "\"",
                        externalReference == null ? "null" : "\"" + externalReference + "\"");
    }

    private String create(UUID companyId, String body) throws Exception {
        return mockMvc.perform(asAdmin(post(BASE), companyId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Nested
    @DisplayName("tenancy and authorization")
    class Tenancy {

        @Test
        @DisplayName("a location of one company cannot be read through another company's scope")
        void crossCompanyReadIsBlocked() throws Exception {
            String id = JsonPath.read(
                    create(COMPANY_A, locationRequest("ISOLATED", "STORE", "[\"SHIP_TO\"]", null, null, null)),
                    "$.id");

            mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_B))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));
        }

        @Test
        @DisplayName("a location of one company never appears in another company's list")
        void crossCompanyListIsBlocked() throws Exception {
            create(COMPANY_A, locationRequest("ONLY-IN-A", "STORE", "[\"SHIP_TO\"]", null, null, null));

            mockMvc.perform(asAdmin(get(BASE), COMPANY_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.code == 'ONLY-IN-A')]").doesNotExist());
        }

        @Test
        @DisplayName("a viewer may read locations but not create one")
        void viewerCannotManage() throws Exception {
            mockMvc.perform(asViewer(get(BASE), COMPANY_A)).andExpect(status().isOk());

            mockMvc.perform(asViewer(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("VIEWER-TRY", "STORE", "[\"SHIP_TO\"]", null, null, null)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));
        }

        @Test
        @DisplayName("a zone from another company is refused even though it is a real zone")
        void zoneMustBelongToCallersCompany() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("CROSS-ZONE", "STORE", "[\"SHIP_TO\"]",
                                    zoneInCompanyB, null, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        @DisplayName("the same code is allowed in a different company but conflicts inside the same one")
        void codeIsUniquePerCompany() throws Exception {
            create(COMPANY_A, locationRequest("SHARED", "STORE", "[\"SHIP_TO\"]", null, null, null));
            create(COMPANY_B, locationRequest("SHARED", "STORE", "[\"SHIP_TO\"]", null, null, null));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("SHARED", "STORE", "[\"SHIP_TO\"]", null, null, null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("conflict"));
        }

        @Test
        @DisplayName("an external reference is an idempotency key: unique per company, reusable across them")
        void externalReferenceIsUniquePerCompany() throws Exception {
            create(COMPANY_A, locationRequest("EXT-1", "STORE", "[\"SHIP_TO\"]", null, "EWM", "STORE-77"));
            create(COMPANY_B, locationRequest("EXT-1", "STORE", "[\"SHIP_TO\"]", null, "EWM", "STORE-77"));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("EXT-2", "STORE", "[\"SHIP_TO\"]", null, "EWM", "STORE-77")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("conflict"));
        }

        @Test
        @DisplayName("the same reference from a different system is a different identity")
        void externalReferenceIsScopedToItsSystem() throws Exception {
            create(COMPANY_A, locationRequest("SYS-1", "STORE", "[\"SHIP_TO\"]", null, "EWM", "SHARED-REF"));

            create(COMPANY_A, locationRequest("SYS-2", "STORE", "[\"SHIP_TO\"]", null, "ERP", "SHARED-REF"));
        }

        @Test
        @DisplayName("half an external identity is refused: a reference with no system deduplicates nothing")
        void externalIdentityMustBeComplete() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("HALF-EXT", "STORE", "[\"SHIP_TO\"]", null, null, "ORPHAN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("the code is normalized on the way in, so 'lima-01' and 'LIMA-01' are one code")
        void codeIsNormalized() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("lower-code", "STORE", "[\"SHIP_TO\"]", null, null, null)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("LOWER-CODE"));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("LOWER-CODE", "STORE", "[\"SHIP_TO\"]", null, null, null)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("an out-of-range coordinate is a field-level error")
        void coordinateRangeIsEnforced() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"BAD-LAT","name":"Bad","type":"STORE","roles":["SHIP_TO"],
                                     "country":"PE","timeZone":"America/Lima","latitude":200.0,
                                     "longitude":0.0,"serviceTimeMinutes":0}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation-failed"))
                    .andExpect(jsonPath("$.errors[?(@.field == 'latitude')]").exists());
        }

        @Test
        @DisplayName("half a coordinate pair is refused: one number cannot describe a point")
        void coordinatePairIsEnforced() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"BAD-PAIR","name":"Bad","type":"STORE","roles":["SHIP_TO"],
                                     "country":"PE","timeZone":"America/Lima","latitude":10.0,
                                     "serviceTimeMinutes":0}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("a location with no role is refused: a place that may do nothing is a typo")
        void rolesAreRequired() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("NO-ROLE", "STORE", "[]", null, null, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation-failed"))
                    .andExpect(jsonPath("$.errors[?(@.field == 'roles')]").exists());
        }

        @Test
        @DisplayName("a time zone that is not an IANA identifier is refused")
        void timeZoneIsValidated() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"BAD-TZ","name":"Bad","type":"STORE","roles":["SHIP_TO"],
                                     "country":"PE","timeZone":"Mars/Olympus","serviceTimeMinutes":0}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }
    }

    @Nested
    @DisplayName("compatibility projections")
    class Projections {

        @Test
        @DisplayName("a location with both roles gets one origin and one destination, both linked to it")
        void bothRolesProjectBothWays() throws Exception {
            String response = create(COMPANY_A,
                    locationRequest("DUAL-DC", "DISTRIBUTION_CENTER", "[\"ORIGIN\",\"SHIP_TO\"]",
                            zoneInCompanyA, null, null));
            String id = JsonPath.read(response, "$.id");
            String originId = JsonPath.read(response, "$.originId");
            String destinationId = JsonPath.read(response, "$.destinationId");

            assertThat(originId).isNotNull();
            assertThat(destinationId).isNotNull();

            mockMvc.perform(asAdmin(get(ORIGINS + "/" + originId), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("DUAL-DC"))
                    .andExpect(jsonPath("$.type").value("DISTRIBUTION_CENTER"));

            mockMvc.perform(asAdmin(get(DESTINATIONS + "/" + destinationId), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("DUAL-DC"))
                    .andExpect(jsonPath("$.serviceTimeMinutes").value(25));

            assertThat(singleValue("SELECT location_id::text FROM tms.origin WHERE id = '" + originId + "'"))
                    .isEqualTo(id);
            assertThat(singleValue(
                    "SELECT location_id::text FROM tms.destination WHERE id = '" + destinationId + "'"))
                    .isEqualTo(id);
        }

        @Test
        @DisplayName("a canonical type the legacy enum cannot express narrows to that side's catch-all")
        void narrowingUsesTheLegacyCatchAll() throws Exception {
            String response = create(COMPANY_A,
                    locationRequest("STORE-ORIGIN", "STORE", "[\"ORIGIN\",\"SHIP_TO\"]", null, null, null));

            mockMvc.perform(asAdmin(get(ORIGINS + "/" + JsonPath.read(response, "$.originId")), COMPANY_A))
                    .andExpect(jsonPath("$.type").value("OTHER"));
            mockMvc.perform(asAdmin(get(DESTINATIONS + "/" + JsonPath.read(response, "$.destinationId")), COMPANY_A))
                    .andExpect(jsonPath("$.type").value("STORE"));
        }

        @Test
        @DisplayName("dropping the ORIGIN role deactivates the origin instead of deleting it")
        void droppingARoleRetiresTheProjection() throws Exception {
            String response = create(COMPANY_A,
                    locationRequest("RETIRE-ME", "HUB", "[\"ORIGIN\",\"SHIP_TO\"]", null, null, null));
            String id = JsonPath.read(response, "$.id");
            String originId = JsonPath.read(response, "$.originId");

            mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("RETIRE-ME", "HUB", "[\"SHIP_TO\"]", null, null, null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles").value(List.of("SHIP_TO")));

            mockMvc.perform(asAdmin(get(ORIGINS + "/" + originId), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
        }

        @Test
        @DisplayName("deactivating a location deactivates its projections too")
        void deactivationFlowsDown() throws Exception {
            String response = create(COMPANY_A,
                    locationRequest("OFF-SITE", "STORE", "[\"SHIP_TO\"]", null, null, null));
            String id = JsonPath.read(response, "$.id");
            String destinationId = JsonPath.read(response, "$.destinationId");

            mockMvc.perform(asAdmin(post(BASE + "/" + id + "/deactivate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));

            mockMvc.perform(asAdmin(get(DESTINATIONS + "/" + destinationId), COMPANY_A))
                    .andExpect(jsonPath("$.active").value(false));

            mockMvc.perform(asAdmin(post(BASE + "/" + id + "/activate"), COMPANY_A))
                    .andExpect(status().isOk());
            mockMvc.perform(asAdmin(get(DESTINATIONS + "/" + destinationId), COMPANY_A))
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        @DisplayName("editing the location updates the projection, so the two models cannot drift")
        void canonicalEditFlowsDown() throws Exception {
            String response = create(COMPANY_A,
                    locationRequest("EDIT-DOWN", "STORE", "[\"SHIP_TO\"]", null, null, null));
            String id = JsonPath.read(response, "$.id");
            String destinationId = JsonPath.read(response, "$.destinationId");

            mockMvc.perform(asAdmin(put(BASE + "/" + id), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"EDIT-DOWN","name":"Renamed Store","type":"BRANCH",
                                     "roles":["SHIP_TO"],"address":"Nueva 99","country":"PE",
                                     "timeZone":"America/Lima","serviceTimeMinutes":40}
                                    """))
                    .andExpect(status().isOk());

            mockMvc.perform(asAdmin(get(DESTINATIONS + "/" + destinationId), COMPANY_A))
                    .andExpect(jsonPath("$.name").value("Renamed Store"))
                    .andExpect(jsonPath("$.type").value("BRANCH"))
                    .andExpect(jsonPath("$.serviceTimeMinutes").value(40));
        }
    }

    @Nested
    @DisplayName("the legacy APIs keep working and stay in step")
    class LegacyCompatibility {

        @Test
        @DisplayName("creating an origin the old way also creates the canonical location it projects")
        void legacyOriginCreateAdoptsALocation() throws Exception {
            mockMvc.perform(asAdmin(post(ORIGINS), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"LEGACY-ORG","name":"Legacy Origin","type":"WAREHOUSE",
                                     "address":"Av. Legacy 1","timeZone":"America/Lima"}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(asAdmin(get(BASE + "?search=LEGACY-ORG"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].code").value("LEGACY-ORG"))
                    .andExpect(jsonPath("$.content[0].type").value("WAREHOUSE"))
                    .andExpect(jsonPath("$.content[0].roles").value(List.of("ORIGIN")));
        }

        @Test
        @DisplayName("creating a destination the old way also creates the canonical location it projects")
        void legacyDestinationCreateAdoptsALocation() throws Exception {
            mockMvc.perform(asAdmin(post(DESTINATIONS), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"LEGACY-DST","name":"Legacy Destination","type":"CUSTOMER",
                                     "address":"Av. Legacy 2","country":"PE","serviceTimeMinutes":10}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(asAdmin(get(BASE + "?search=LEGACY-DST"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].type").value("CUSTOMER"))
                    .andExpect(jsonPath("$.content[0].serviceTimeMinutes").value(10))
                    .andExpect(jsonPath("$.content[0].roles").value(List.of("SHIP_TO")));
        }

        @Test
        @DisplayName("editing a projection the old way is pushed up to the canonical location")
        void legacyEditFlowsUp() throws Exception {
            String response = create(COMPANY_A,
                    locationRequest("UP-SYNC", "STORE", "[\"SHIP_TO\"]", null, null, null));
            String id = JsonPath.read(response, "$.id");
            String destinationId = JsonPath.read(response, "$.destinationId");

            mockMvc.perform(asAdmin(put(DESTINATIONS + "/" + destinationId), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"UP-SYNC","name":"Renamed Through Legacy","type":"STORE",
                                     "address":"Otra 55","country":"PE","serviceTimeMinutes":55}
                                    """))
                    .andExpect(status().isOk());

            mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_A))
                    .andExpect(jsonPath("$.name").value("Renamed Through Legacy"))
                    .andExpect(jsonPath("$.serviceTimeMinutes").value(55))
                    .andExpect(jsonPath("$.timeZone")
                            .value("America/Lima"));
        }

        @Test
        @DisplayName("renaming an origin onto a code another location holds is refused, not silently dropped")
        void legacyRenameOntoATakenCodeConflicts() throws Exception {
            create(COMPANY_A, locationRequest("TAKEN-CODE", "STORE", "[\"SHIP_TO\"]", null, null, null));
            String origin = mockMvc.perform(asAdmin(post(ORIGINS), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"FREE-CODE","name":"Free","type":"WAREHOUSE",
                                     "timeZone":"America/Lima"}
                                    """))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            mockMvc.perform(asAdmin(put(ORIGINS + "/" + JsonPath.read(origin, "$.id")), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"TAKEN-CODE","name":"Free","type":"WAREHOUSE",
                                     "timeZone":"America/Lima"}
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("conflict"));
        }
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        @DisplayName("the search box matches code, name and external reference")
        void searchSpansTheThreeIdentifiers() throws Exception {
            create(COMPANY_A, locationRequest("FIND-ME", "STORE", "[\"SHIP_TO\"]", null, "EWM", "FINDABLE-REF"));

            mockMvc.perform(asAdmin(get(BASE + "?search=find-me"), COMPANY_A))
                    .andExpect(jsonPath("$.content[?(@.code == 'FIND-ME')]").exists());
            mockMvc.perform(asAdmin(get(BASE + "?search=FIND-ME Name"), COMPANY_A))
                    .andExpect(jsonPath("$.content[?(@.code == 'FIND-ME')]").exists());
            mockMvc.perform(asAdmin(get(BASE + "?search=FINDABLE-REF"), COMPANY_A))
                    .andExpect(jsonPath("$.content[?(@.code == 'FIND-ME')]").exists());
        }

        @Test
        @DisplayName("filtering by role returns each location once, however many roles it holds")
        void roleFilterDoesNotDuplicateRows() throws Exception {
            create(COMPANY_A, locationRequest("MULTI-ROLE", "HUB",
                    "[\"ORIGIN\",\"SHIP_TO\",\"HUB\",\"DC\"]", null, null, null));

            mockMvc.perform(asAdmin(get(BASE + "?role=ORIGIN&search=MULTI-ROLE"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content.length()").value(1));
        }

        @Test
        @DisplayName("an unknown sort property is refused rather than silently ignored")
        void sortIsAllowListed() throws Exception {
            mockMvc.perform(asAdmin(get(BASE + "?sort=passwordHash,asc"), COMPANY_A))
                    .andExpect(status().isBadRequest());
        }
    }
}
