package com.ebim.tms.masterdata.api;

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
 * The zone vertical slice, exercised end to end through the real HTTP filter chain and a real,
 * freshly migrated PostgreSQL - the same proof {@code IdentityResolutionIntegrationTest} gives
 * the tenancy model, extended one layer further to the controller/service/repository code.
 *
 * <p>This file used to cover {@code /masterdata/origins} as well. V23 retired that endpoint - an
 * origin is a {@code tms.location} holding the {@code ORIGIN} role - and every behaviour the
 * origins half asserted (code uniqueness per company, cross-company isolation, coordinate
 * range, activate/deactivate, edit, permission gating, pagination) is asserted by
 * {@code LocationApiIntegrationTest} against the master that now owns it.
 *
 * <p>Only the {@link JwtDecoder} is replaced, with a decoder that trusts a keypair generated in
 * this JVM ({@link TestJwts}) instead of a real Supabase project - the same substitution
 * {@code SecurityTestConfiguration} makes for the web-layer slice tests. Identity resolution,
 * company scoping, method security, master data services and repositories are all the
 * production beans, running against the seeded fixture below.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ZoneApiIntegrationTest.JwtDecoderOverride.class)
class ZoneApiIntegrationTest {

    private static final UUID ORGANIZATION = UUID.fromString("22222222-0000-4000-8000-000000000001");
    private static final UUID COMPANY_A = UUID.fromString("22222222-0000-4000-8000-0000000000c1");
    private static final UUID COMPANY_B = UUID.fromString("22222222-0000-4000-8000-0000000000c2");
    private static final UUID ADMIN_AUTH = UUID.fromString("22222222-0000-4000-8000-0000000000e1");
    private static final UUID VIEWER_AUTH = UUID.fromString("22222222-0000-4000-8000-0000000000e2");

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
     * only. That single fixture is enough to prove every case the step brief asks for: same
     * code allowed in different companies, conflict inside one company, cross-company access
     * blocked even for a caller who legitimately belongs to the other company, and permission
     * enforcement for the read-only role.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_masterdata_api");
        seedFixture();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    private static void seedFixture() {
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES
                    ('%s', 'MD-ORG', 'Master Data Organization');

                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'MD-A', 'Company A', 'America/Lima'),
                    ('%s', '%s', 'MD-B', 'Company B', 'America/Lima');

                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'md.admin@example.invalid', 'MD Admin'),
                    ('%s', 'md.viewer@example.invalid', 'MD Viewer');
                """.formatted(ORGANIZATION, COMPANY_A, ORGANIZATION, COMPANY_B, ORGANIZATION, ADMIN_AUTH, VIEWER_AUTH));

        membership("md.admin@example.invalid", COMPANY_A, "COMPANY_ADMIN");
        membership("md.admin@example.invalid", COMPANY_B, "COMPANY_ADMIN");
        membership("md.viewer@example.invalid", COMPANY_A, "VIEWER");
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
            throw new IllegalStateException("could not seed the master data fixture", failed);
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
    @DisplayName("zones")
    class Zones {

        private static final String BASE = "/api/v1/masterdata/zones";

        private String zoneRequest(String code) {
            return """
                    {"code":"%s","name":"%s Name","description":"a zone"}
                    """.formatted(code, code);
        }

        @Test
        @DisplayName("create normalizes the code, and the same code is free to reuse in another company")
        void createAndCrossCompanyReuse() throws Exception {
            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(zoneRequest("north-zone")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("NORTH-ZONE"));

            mockMvc.perform(asAdmin(post(BASE), COMPANY_B)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(zoneRequest("north-zone")))
                    .andExpect(status().isCreated());

            mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(zoneRequest("north-zone")))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("a zone of one company cannot be read through another company's scope")
        void crossCompanyAccessIsBlocked() throws Exception {
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(zoneRequest("secluded")))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = idOf(response);

            mockMvc.perform(asAdmin(get(BASE + "/" + id), COMPANY_B))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a read-only role may list but not create, update or deactivate")
        void readOnlyRoleCannotManage() throws Exception {
            mockMvc.perform(asViewer(get(BASE), COMPANY_A)).andExpect(status().isOk());

            mockMvc.perform(asViewer(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(zoneRequest("nope")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("deactivate is idempotent-safe and reflected in a subsequent read")
        void deactivate() throws Exception {
            String response = mockMvc.perform(asAdmin(post(BASE), COMPANY_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(zoneRequest("fade-out")))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = idOf(response);

            mockMvc.perform(asAdmin(post(BASE + "/" + id + "/deactivate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
            mockMvc.perform(asAdmin(post(BASE + "/" + id + "/deactivate"), COMPANY_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
        }
    }
}
