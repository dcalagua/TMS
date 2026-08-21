package com.ebim.tms.masterdata.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * The location/frequency service-calendar slice (migration V15, job 03 of the overnight V3
 * pack), exercised end to end through the real HTTP filter chain and a real, freshly migrated
 * PostgreSQL - the same proof {@code DestinationFrequencyApiIntegrationTest} gives V7's
 * destination/frequency slice, and {@code LocationApiIntegrationTest} gives the Location master
 * itself.
 *
 * <p>There is no single-association GET endpoint on {@code LocationController} (only list,
 * create, update, activate, deactivate, delete and the eligibility question), so "read back a
 * created association" is proven through the list endpoint throughout this suite.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocationFrequencyApiIntegrationTest.JwtDecoderOverride.class)
class LocationFrequencyApiIntegrationTest {

    private static final String LOCATIONS = "/api/v1/masterdata/locations";
    private static final String FREQUENCIES = "/api/v1/masterdata/frequencies";

    private static final UUID ORGANIZATION = UUID.fromString("55555555-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("55555555-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("55555555-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("55555555-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("55555555-0000-4000-8000-0000000000e2");

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_location_frequency_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES
                    ('%s', 'LF-ORG', 'Location Frequency Organization');

                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'LF-A', 'Company A', 'America/Lima'),
                    ('%s', '%s', 'LF-B', 'Company B', 'America/Lima');

                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'lf.admin@example.invalid', 'LF Admin'),
                    ('%s', 'lf.viewer@example.invalid', 'LF Viewer');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION, ADMIN_AUTH, VIEWER_AUTH));

        membership("lf.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("lf.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("lf.viewer@example.invalid", COMPANY_A, "VIEWER");
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
            throw new IllegalStateException("could not seed the location/frequency fixture", failed);
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

    private static String quoteOrNull(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String locationRequest(String code, String roles) {
        return """
                {"code":"%s","name":"%s Name","type":"STORE","roles":%s,
                 "country":"PE","timeZone":"America/Lima","serviceTimeMinutes":10}
                """.formatted(code, code, roles);
    }

    private static String frequencyRequest(String code, String weeklyRulesJson) {
        return """
                {"code":"%s","name":"%s Name","description":"a service calendar","weeklyRules":%s}
                """.formatted(code, code, weeklyRulesJson);
    }

    private static String associationRequest(String frequencyId, String effectiveFrom, String effectiveTo) {
        return """
                {"frequencyId":"%s","effectiveFrom":%s,"effectiveTo":%s}
                """.formatted(frequencyId, quoteOrNull(effectiveFrom), quoteOrNull(effectiveTo));
    }

    private static String dateRangeRequest(String effectiveFrom, String effectiveTo) {
        return """
                {"effectiveFrom":%s,"effectiveTo":%s}
                """.formatted(quoteOrNull(effectiveFrom), quoteOrNull(effectiveTo));
    }

    private static String frequenciesPath(String locationId) {
        return LOCATIONS + "/" + locationId + "/frequencies";
    }

    private String createLocation(UUID companyId, String code) throws Exception {
        return createLocation(companyId, code, "[\"SHIP_TO\"]");
    }

    private String createLocation(UUID companyId, String code, String roles) throws Exception {
        return idOf(mockMvc.perform(asAdmin(post(LOCATIONS), companyId)
                        .contentType(MediaType.APPLICATION_JSON).content(locationRequest(code, roles)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private String createFrequency(UUID companyId, String code, String weeklyRulesJson) throws Exception {
        return idOf(mockMvc.perform(asAdmin(post(FREQUENCIES), companyId)
                        .contentType(MediaType.APPLICATION_JSON).content(frequencyRequest(code, weeklyRulesJson)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private void addException(UUID companyId, String frequencyId, String exceptionDate, boolean serviceOverride)
            throws Exception {
        mockMvc.perform(asAdmin(post(FREQUENCIES + "/" + frequencyId + "/exceptions"), companyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"exceptionDate":"%s","serviceOverride":%s}
                                """.formatted(exceptionDate, serviceOverride)))
                .andExpect(status().isCreated());
    }

    private String createAssociation(UUID companyId, String locationId, String frequencyId, String effectiveFrom,
            String effectiveTo) throws Exception {
        return mockMvc.perform(asAdmin(post(frequenciesPath(locationId)), companyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(associationRequest(frequencyId, effectiveFrom, effectiveTo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Nested
    @DisplayName("associations")
    class Associations {

        @Test
        @DisplayName("create is reflected by list, with the frequency's code and name resolved")
        void createIsListedWithResolvedFrequency() throws Exception {
            String locationId = createLocation(COMPANY_A, "ASSOC-LOC");
            String frequencyId = createFrequency(COMPANY_A, "ASSOC-FREQ", "[]");

            String response = createAssociation(COMPANY_A, locationId, frequencyId, null, null);
            String associationId = idOf(response);

            mockMvc.perform(asAdmin(get(frequenciesPath(locationId)), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(associationId))
                    .andExpect(jsonPath("$[0].frequencyId").value(frequencyId))
                    .andExpect(jsonPath("$[0].frequencyCode").value("ASSOC-FREQ"))
                    .andExpect(jsonPath("$[0].frequencyName").value("ASSOC-FREQ Name"))
                    .andExpect(jsonPath("$[0].active").value(true));
        }

        @Test
        @DisplayName("create rejects a frequencyId that does not resolve in this company")
        void createRejectsUnknownFrequencyId() throws Exception {
            String locationId = createLocation(COMPANY_A, "UNKNOWN-FREQ-LOC");

            mockMvc.perform(asAdmin(post(frequenciesPath(locationId)), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(associationRequest(UUID.randomUUID().toString(), null, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("create rejects an effectiveTo before effectiveFrom")
        void createRejectsInvertedDateRange() throws Exception {
            String locationId = createLocation(COMPANY_A, "INVERTED-RANGE-LOC");
            String frequencyId = createFrequency(COMPANY_A, "INVERTED-RANGE-FREQ", "[]");

            mockMvc.perform(asAdmin(post(frequenciesPath(locationId)), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(associationRequest(frequencyId, "2026-06-01", "2026-01-01")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("effectiveTo equal to effectiveFrom is a valid one-day range")
        void createAcceptsEqualDateRange() throws Exception {
            String locationId = createLocation(COMPANY_A, "EQUAL-RANGE-LOC");
            String frequencyId = createFrequency(COMPANY_A, "EQUAL-RANGE-FREQ", "[]");

            mockMvc.perform(asAdmin(post(frequenciesPath(locationId)), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(associationRequest(frequencyId, "2026-06-01", "2026-06-01")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.effectiveFrom").value("2026-06-01"))
                    .andExpect(jsonPath("$.effectiveTo").value("2026-06-01"));
        }

        @Test
        @DisplayName("a second active association with the same frequency conflicts")
        void createTwiceWithSameActiveFrequencyConflicts() throws Exception {
            String locationId = createLocation(COMPANY_A, "DUP-ACTIVE-LOC");
            String frequencyId = createFrequency(COMPANY_A, "DUP-ACTIVE-FREQ", "[]");

            createAssociation(COMPANY_A, locationId, frequencyId, null, null);

            mockMvc.perform(asAdmin(post(frequenciesPath(locationId)), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(associationRequest(frequencyId, null, null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("conflict"));
        }

        @Test
        @DisplayName("update changes only the effective date range, not the frequency link")
        void updateChangesDateRangeOnly() throws Exception {
            String locationId = createLocation(COMPANY_A, "UPDATE-RANGE-LOC");
            String frequencyId = createFrequency(COMPANY_A, "UPDATE-RANGE-FREQ", "[]");
            String associationId = idOf(createAssociation(COMPANY_A, locationId, frequencyId, null, null));

            mockMvc.perform(asAdmin(put(frequenciesPath(locationId) + "/" + associationId), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(dateRangeRequest("2026-01-01", "2026-12-31")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(associationId))
                    .andExpect(jsonPath("$.frequencyId").value(frequencyId))
                    .andExpect(jsonPath("$.effectiveFrom").value("2026-01-01"))
                    .andExpect(jsonPath("$.effectiveTo").value("2026-12-31"));

            mockMvc.perform(asAdmin(get(frequenciesPath(locationId)), COMPANY_A))
                    .andExpect(jsonPath("$[0].effectiveFrom").value("2026-01-01"))
                    .andExpect(jsonPath("$[0].effectiveTo").value("2026-12-31"));
        }

        @Test
        @DisplayName("deactivate then reactivate toggles active, and reactivating into a collision conflicts")
        void deactivateReactivateAndCollision() throws Exception {
            String locationId = createLocation(COMPANY_A, "TOGGLE-LOC");
            String frequencyId = createFrequency(COMPANY_A, "TOGGLE-FREQ", "[]");

            String firstId = idOf(createAssociation(COMPANY_A, locationId, frequencyId, null, null));
            mockMvc.perform(asAdmin(post(frequenciesPath(locationId) + "/" + firstId + "/deactivate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));

            // Now that the first association is inactive, a second one with the same frequency is allowed.
            String secondId = idOf(createAssociation(COMPANY_A, locationId, frequencyId, null, null));
            mockMvc.perform(asAdmin(post(frequenciesPath(locationId) + "/" + secondId + "/deactivate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));

            mockMvc.perform(asAdmin(post(frequenciesPath(locationId) + "/" + firstId + "/activate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(true));

            mockMvc.perform(asAdmin(post(frequenciesPath(locationId) + "/" + secondId + "/activate"), COMPANY_A))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("conflict"));
        }

        @Test
        @DisplayName("delete removes the association from a subsequent list")
        void deleteRemovesAssociation() throws Exception {
            String locationId = createLocation(COMPANY_A, "DELETE-LOC");
            String frequencyId = createFrequency(COMPANY_A, "DELETE-FREQ", "[]");
            String associationId = idOf(createAssociation(COMPANY_A, locationId, frequencyId, null, null));

            mockMvc.perform(asAdmin(delete(frequenciesPath(locationId) + "/" + associationId), COMPANY_A))
                    .andExpect(status().isNoContent());

            mockMvc.perform(asAdmin(get(frequenciesPath(locationId)), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Nested
    @DisplayName("tenancy")
    class Tenancy {

        @Test
        @DisplayName("a company-B location's associations cannot be listed through company A's scope")
        void listAcrossCompaniesIsBlocked() throws Exception {
            String locationInB = createLocation(COMPANY_B, "TENANT-LIST-LOC-B");

            mockMvc.perform(asAdmin(get(frequenciesPath(locationInB)), COMPANY_A))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));
        }

        @Test
        @DisplayName("an association cannot be created against a company-B location through company A's scope")
        void createAcrossCompaniesIsBlocked() throws Exception {
            String locationInB = createLocation(COMPANY_B, "TENANT-CREATE-LOC-B");
            String frequencyInA = createFrequency(COMPANY_A, "TENANT-CREATE-FREQ-A", "[]");

            mockMvc.perform(asAdmin(post(frequenciesPath(locationInB)), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(associationRequest(frequencyInA, null, null)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));
        }

        @Test
        @DisplayName("an association cannot be updated, activated, deactivated or deleted through another company's scope")
        void manageAcrossCompaniesIsBlocked() throws Exception {
            String locationInB = createLocation(COMPANY_B, "TENANT-MANAGE-LOC-B");
            String frequencyInB = createFrequency(COMPANY_B, "TENANT-MANAGE-FREQ-B", "[]");
            String associationId = idOf(createAssociation(COMPANY_B, locationInB, frequencyInB, null, null));
            String associationPath = frequenciesPath(locationInB) + "/" + associationId;

            mockMvc.perform(asAdmin(put(associationPath), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(dateRangeRequest("2026-01-01", "2026-12-31")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));

            mockMvc.perform(asAdmin(post(associationPath + "/activate"), COMPANY_A))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));

            mockMvc.perform(asAdmin(post(associationPath + "/deactivate"), COMPANY_A))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));

            mockMvc.perform(asAdmin(delete(associationPath), COMPANY_A))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));
        }

        @Test
        @DisplayName("a company-A frequencyId cannot be attached to a company-B location")
        void companyAFrequencyCannotAttachToCompanyBLocation() throws Exception {
            String locationInB = createLocation(COMPANY_B, "CROSS-FREQ-LOC-B");
            String frequencyInA = createFrequency(COMPANY_A, "CROSS-FREQ-A", "[]");

            mockMvc.perform(asAdmin(post(frequenciesPath(locationInB)), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(associationRequest(frequencyInA, null, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("a company-B frequencyId cannot be attached to a company-A location")
        void companyBFrequencyCannotAttachToCompanyALocation() throws Exception {
            String locationInA = createLocation(COMPANY_A, "CROSS-FREQ-LOC-A");
            String frequencyInB = createFrequency(COMPANY_B, "CROSS-FREQ-B", "[]");

            mockMvc.perform(asAdmin(post(frequenciesPath(locationInA)), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(associationRequest(frequencyInB, null, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed-request"));
        }

        @Test
        @DisplayName("eligibility cannot be evaluated for a company-B location through company A's scope")
        void eligibilityAcrossCompaniesIsBlocked() throws Exception {
            String locationInB = createLocation(COMPANY_B, "TENANT-ELIGIBILITY-LOC-B");

            mockMvc.perform(asAdmin(get(LOCATIONS + "/" + locationInB + "/eligibility"), COMPANY_A)
                            .param("date", "2026-08-24"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("resource-not-found"));
        }
    }

    @Nested
    @DisplayName("eligibility")
    class Eligibility {

        private static final String MONDAY_RULE = """
                [{"dayOfWeek":1,"enabled":true,"cutoffTime":"14:00:00","leadTimeDays":1}]
                """;

        private String eligibilityPath(String locationId) {
            return LOCATIONS + "/" + locationId + "/eligibility";
        }

        @Test
        @DisplayName("eligible when an associated frequency's weekly rule runs that date")
        void eligibleWhenWeeklyRuleRunsOnDate() throws Exception {
            String locationId = createLocation(COMPANY_A, "ELIG-RUNS-LOC");
            String frequencyId = createFrequency(COMPANY_A, "ELIG-RUNS-FREQ", MONDAY_RULE);
            createAssociation(COMPANY_A, locationId, frequencyId, null, null);

            mockMvc.perform(asAdmin(get(eligibilityPath(locationId)), COMPANY_A).param("date", "2026-08-24"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.date").value("2026-08-24"))
                    .andExpect(jsonPath("$.eligible").value(true))
                    .andExpect(jsonPath("$.frequencyId").value(frequencyId))
                    .andExpect(jsonPath("$.cutoffTime").value("14:00:00"))
                    .andExpect(jsonPath("$.leadTimeDays").value(1));
        }

        @Test
        @DisplayName("not eligible, with a reason, when the location has no association at all")
        void notEligibleWhenNoAssociationExists() throws Exception {
            String locationId = createLocation(COMPANY_A, "ELIG-NONE-LOC");

            mockMvc.perform(asAdmin(get(eligibilityPath(locationId)), COMPANY_A).param("date", "2026-08-24"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eligible").value(false))
                    .andExpect(jsonPath("$.reason").isNotEmpty())
                    .andExpect(jsonPath("$.frequencyId").doesNotExist());
        }

        @Test
        @DisplayName("not eligible when the associated frequency simply does not run that day of week")
        void notEligibleWhenFrequencyDoesNotRunThatDay() throws Exception {
            String locationId = createLocation(COMPANY_A, "ELIG-MISMATCH-LOC");
            String frequencyId = createFrequency(COMPANY_A, "ELIG-MISMATCH-FREQ", MONDAY_RULE);
            createAssociation(COMPANY_A, locationId, frequencyId, null, null);

            // 2026-08-25 is a Tuesday; the frequency only runs Mondays.
            mockMvc.perform(asAdmin(get(eligibilityPath(locationId)), COMPANY_A).param("date", "2026-08-25"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eligible").value(false))
                    .andExpect(jsonPath("$.reason").isNotEmpty());
        }

        @Test
        @DisplayName("not eligible when the location itself is inactive, even with a matching frequency")
        void notEligibleWhenLocationInactive() throws Exception {
            String locationId = createLocation(COMPANY_A, "ELIG-INACTIVE-LOC");
            String frequencyId = createFrequency(COMPANY_A, "ELIG-INACTIVE-FREQ", MONDAY_RULE);
            createAssociation(COMPANY_A, locationId, frequencyId, null, null);

            mockMvc.perform(asAdmin(post(LOCATIONS + "/" + locationId + "/deactivate"), COMPANY_A))
                    .andExpect(status().isOk());

            mockMvc.perform(asAdmin(get(eligibilityPath(locationId)), COMPANY_A).param("date", "2026-08-24"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eligible").value(false))
                    .andExpect(jsonPath("$.reason").value("Location is inactive."));
        }

        @Test
        @DisplayName("a blackout exception overrides an otherwise-running weekly rule")
        void blackoutExceptionOverridesWeeklyRule() throws Exception {
            String locationId = createLocation(COMPANY_A, "ELIG-BLACKOUT-LOC");
            String frequencyId = createFrequency(COMPANY_A, "ELIG-BLACKOUT-FREQ", MONDAY_RULE);
            createAssociation(COMPANY_A, locationId, frequencyId, null, null);
            addException(COMPANY_A, frequencyId, "2026-08-24", false);

            mockMvc.perform(asAdmin(get(eligibilityPath(locationId)), COMPANY_A).param("date", "2026-08-24"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eligible").value(false));
        }

        @Test
        @DisplayName("an extra-service exception overrides an otherwise-silent weekly rule")
        void extraServiceExceptionOverridesWeeklyRule() throws Exception {
            String locationId = createLocation(COMPANY_A, "ELIG-EXTRA-LOC");
            String frequencyId = createFrequency(COMPANY_A, "ELIG-EXTRA-FREQ", MONDAY_RULE);
            createAssociation(COMPANY_A, locationId, frequencyId, null, null);
            // 2026-08-25 is a Tuesday, not covered by the Monday-only weekly rule.
            addException(COMPANY_A, frequencyId, "2026-08-25", true);

            mockMvc.perform(asAdmin(get(eligibilityPath(locationId)), COMPANY_A).param("date", "2026-08-25"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eligible").value(true))
                    .andExpect(jsonPath("$.frequencyId").value(frequencyId));
        }
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @DisplayName("a viewer may list, and read eligibility, but not create, update, activate, deactivate or delete")
        void viewerCanReadButNotManage() throws Exception {
            String locationId = createLocation(COMPANY_A, "VIEWER-LOC");
            String frequencyId = createFrequency(COMPANY_A, "VIEWER-FREQ", "[]");
            String associationId = idOf(createAssociation(COMPANY_A, locationId, frequencyId, null, null));
            String associationPath = frequenciesPath(locationId) + "/" + associationId;

            mockMvc.perform(asViewer(get(frequenciesPath(locationId)), COMPANY_A)).andExpect(status().isOk());
            mockMvc.perform(asViewer(get(LOCATIONS + "/" + locationId + "/eligibility"), COMPANY_A)
                            .param("date", "2026-08-24"))
                    .andExpect(status().isOk());

            mockMvc.perform(asViewer(post(frequenciesPath(locationId)), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(associationRequest(frequencyId, null, null)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));

            mockMvc.perform(asViewer(put(associationPath), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(dateRangeRequest("2026-01-01", "2026-12-31")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));

            mockMvc.perform(asViewer(post(associationPath + "/activate"), COMPANY_A))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));

            mockMvc.perform(asViewer(post(associationPath + "/deactivate"), COMPANY_A))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));

            mockMvc.perform(asViewer(delete(associationPath), COMPANY_A))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("access-denied"));
        }
    }
}
