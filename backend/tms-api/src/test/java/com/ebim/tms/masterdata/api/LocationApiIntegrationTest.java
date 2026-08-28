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
    @DisplayName("the geofence (V43)")
    class Geofence {

        @Test
        @DisplayName("a location starts with no geofence, and null is not zero")
        void noneByDefault() throws Exception {
            String body = create(COMPANY_A, locationRequest("GEO-NONE", "STORE", "[\"DESTINATION\"]", null, null, null));

            assertThat((Integer) JsonPath.read(body, "$.geofenceRadiusM")).isNull();
        }

        @Test
        @DisplayName("a radius is set through its own endpoint and read back on the location")
        void setAndRead() throws Exception {
            String id = JsonPath.read(
                    create(COMPANY_A, locationRequest("GEO-SET", "STORE", "[\"DESTINATION\"]", null, null, null)),
                    "$.id");

            mockMvc.perform(asAdmin(put(BASE + "/" + id + "/geofence"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"radiusMetres\":250}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.geofenceRadiusM").value(250));

            mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_A))
                    .andExpect(jsonPath("$.geofenceRadiusM").value(250));
        }

        /** Null clears it - which is why the radius travels in a body and not as a query parameter. */
        @Test
        @DisplayName("null clears the geofence rather than being read as 'not supplied'")
        void nullClears() throws Exception {
            String id = JsonPath.read(
                    create(COMPANY_A, locationRequest("GEO-CLEAR", "STORE", "[\"DESTINATION\"]", null, null, null)),
                    "$.id");
            mockMvc.perform(asAdmin(put(BASE + "/" + id + "/geofence"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"radiusMetres\":250}"))
                    .andExpect(status().isOk());

            mockMvc.perform(asAdmin(put(BASE + "/" + id + "/geofence"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"radiusMetres\":null}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.geofenceRadiusM").doesNotExist());
        }

        /**
         * Consumer GPS is not accurate below 25 metres, so a tighter circle would be a feature that
         * never fires; over 20km it stops distinguishing this site from the next town.
         */
        @Test
        @DisplayName("a radius outside the usable range is refused")
        void boundsAreEnforced() throws Exception {
            String id = JsonPath.read(
                    create(COMPANY_A, locationRequest("GEO-BOUNDS", "STORE", "[\"DESTINATION\"]", null, null, null)),
                    "$.id");

            mockMvc.perform(asAdmin(put(BASE + "/" + id + "/geofence"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"radiusMetres\":5}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(asAdmin(put(BASE + "/" + id + "/geofence"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"radiusMetres\":50000}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a viewer cannot draw a circle round a warehouse")
        void requiresManage() throws Exception {
            String id = JsonPath.read(
                    create(COMPANY_A, locationRequest("GEO-AUTH", "STORE", "[\"DESTINATION\"]", null, null, null)),
                    "$.id");

            mockMvc.perform(asViewer(put(BASE + "/" + id + "/geofence"), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"radiusMetres\":250}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("another company cannot set a geofence on this company's location")
        void isCompanyScoped() throws Exception {
            String id = JsonPath.read(
                    create(COMPANY_A, locationRequest("GEO-TENANT", "STORE", "[\"DESTINATION\"]", null, null, null)),
                    "$.id");

            mockMvc.perform(asAdmin(put(BASE + "/" + id + "/geofence"), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"radiusMetres\":250}"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("tenancy and authorization")
    class Tenancy {

        @Test
        @DisplayName("a location of one company cannot be read through another company's scope")
        void crossCompanyReadIsBlocked() throws Exception {
            String id = JsonPath.read(
                    create(COMPANY_A, locationRequest("ISOLATED", "STORE", "[\"DESTINATION\"]", null, null, null)),
                    "$.id");

            mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_B))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));
        }

        @Test
        @DisplayName("a location of one company never appears in another company's list")
        void crossCompanyListIsBlocked() throws Exception {
            create(COMPANY_A, locationRequest("ONLY-IN-A", "STORE", "[\"DESTINATION\"]", null, null, null));

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
                            .content(locationRequest("VIEWER-TRY", "STORE", "[\"DESTINATION\"]", null, null, null)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));
        }

        @Test
        @DisplayName("a zone from another company is refused even though it is a real zone")
        void zoneMustBelongToCallersCompany() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("CROSS-ZONE", "STORE", "[\"DESTINATION\"]",
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
            create(COMPANY_A, locationRequest("SHARED", "STORE", "[\"DESTINATION\"]", null, null, null));
            create(COMPANY_B, locationRequest("SHARED", "STORE", "[\"DESTINATION\"]", null, null, null));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("SHARED", "STORE", "[\"DESTINATION\"]", null, null, null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("conflict"));
        }

        @Test
        @DisplayName("an external reference is an idempotency key: unique per company, reusable across them")
        void externalReferenceIsUniquePerCompany() throws Exception {
            create(COMPANY_A, locationRequest("EXT-1", "STORE", "[\"DESTINATION\"]", null, "EWM", "STORE-77"));
            create(COMPANY_B, locationRequest("EXT-1", "STORE", "[\"DESTINATION\"]", null, "EWM", "STORE-77"));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("EXT-2", "STORE", "[\"DESTINATION\"]", null, "EWM", "STORE-77")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("conflict"));
        }

        @Test
        @DisplayName("the same reference from a different system is a different identity")
        void externalReferenceIsScopedToItsSystem() throws Exception {
            create(COMPANY_A, locationRequest("SYS-1", "STORE", "[\"DESTINATION\"]", null, "EWM", "SHARED-REF"));

            create(COMPANY_A, locationRequest("SYS-2", "STORE", "[\"DESTINATION\"]", null, "ERP", "SHARED-REF"));
        }

        @Test
        @DisplayName("half an external identity is refused: a reference with no system deduplicates nothing")
        void externalIdentityMustBeComplete() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("HALF-EXT", "STORE", "[\"DESTINATION\"]", null, null, "ORPHAN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("the code is normalized on the way in, so 'lima-01' and 'LIMA-01' are one code")
        void codeIsNormalized() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("lower-code", "STORE", "[\"DESTINATION\"]", null, null, null)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("LOWER-CODE"));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("LOWER-CODE", "STORE", "[\"DESTINATION\"]", null, null, null)))
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
                                    {"code":"BAD-LAT","name":"Bad","type":"STORE","roles":["DESTINATION"],
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
                                    {"code":"BAD-PAIR","name":"Bad","type":"STORE","roles":["DESTINATION"],
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
                                    {"code":"BAD-TZ","name":"Bad","type":"STORE","roles":["DESTINATION"],
                                     "country":"PE","timeZone":"Mars/Olympus","serviceTimeMinutes":0}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        @DisplayName("the search box matches code, name and external reference")
        void searchSpansTheThreeIdentifiers() throws Exception {
            create(COMPANY_A, locationRequest("FIND-ME", "STORE", "[\"DESTINATION\"]", null, "EWM", "FINDABLE-REF"));

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
                    "[\"ORIGIN\",\"DESTINATION\"]", null, null, null));

            mockMvc.perform(asAdmin(get(BASE + "?role=ORIGIN&search=MULTI-ROLE"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content.length()").value(1));
        }

        @Test
        @DisplayName("the role filter is what the Origins and Destinations screens are")
        void roleFilterIsTheOriginsAndDestinationsView() throws Exception {
            // One store that receives deliveries and ships its own returns, one plant that only
            // ships. There is one physical record each: the two screens are two queries over it.
            create(COMPANY_A, locationRequest("VIEW-STORE", "STORE",
                    "[\"ORIGIN\",\"DESTINATION\"]", null, null, null));
            create(COMPANY_A, locationRequest("VIEW-PLANT", "PLANT", "[\"ORIGIN\"]", null, null, null));

            mockMvc.perform(asAdmin(get(BASE + "?role=ORIGIN&search=VIEW-"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.code == 'VIEW-STORE')]").exists())
                    .andExpect(jsonPath("$.content[?(@.code == 'VIEW-PLANT')]").exists());

            mockMvc.perform(asAdmin(get(BASE + "?role=DESTINATION&search=VIEW-"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.code == 'VIEW-STORE')]").exists())
                    .andExpect(jsonPath("$.content[?(@.code == 'VIEW-PLANT')]")
                            .doesNotExist());
        }

        @Test
        @DisplayName("a retired V14 role is refused rather than silently dropped")
        void retiredRolesAreRejected() throws Exception {
            // DC is one of the five V14 role values V23 dropped, and the only one that is not
            // also a LocationType - so this asserts the retirement itself, and the case below
            // asserts the separate rule that a type may not be spelled as a role.
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("OLD-ROLE", "STORE", "[\"DC\"]", null, null, null)))
                    .andExpect(status().isBadRequest());

            // A kind of place is what type says. Accepting it as a role too is the Type/Roles
            // duplication V23 removed, so the enum no longer parses it.
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(locationRequest("TYPE-AS-ROLE", "STORE", "[\"STORE\"]", null, null, null)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("an unknown sort property is refused rather than silently ignored")
        void sortIsAllowListed() throws Exception {
            mockMvc.perform(asAdmin(get(BASE + "?sort=passwordHash,asc"), COMPANY_A))
                    .andExpect(status().isBadRequest());
        }
    }
}
