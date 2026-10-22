package com.ebim.tms.iam.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import java.sql.SQLException;
import java.util.UUID;
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
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code CompanyAccessView.defaultCountry} (D1, QAS 2026-10-22): a new location in an Ecuadorian
 * company was born {@code PE} because the form hard-coded it. The company's default country now
 * travels with {@code /me} and {@code /companies/current}, so the form starts from it without the
 * caller needing {@code iam.company:read}. Read on the owner connection for {@code /me} and under
 * {@code tms_app} with RLS for {@code /companies/current} - both are exercised here.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CompanyDefaultCountryIntegrationTest.JwtDecoderOverride.class)
class CompanyDefaultCountryIntegrationTest {

    private static final UUID ORGANIZATION = UUID.fromString("66666666-0000-4000-8000-000000000001");
    private static final UUID ECUADOR = UUID.fromString("66666666-0000-4000-8000-0000000000c1");
    private static final UUID NO_ROW = UUID.fromString("66666666-0000-4000-8000-0000000000c2");
    private static final UUID PLANNER_AUTH = UUID.fromString("66666666-0000-4000-8000-0000000000e1");

    private static String jdbcUrl;

    @Autowired
    private MockMvc mockMvc;

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_company_default_country");
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%1$s', 'DC-ORG', 'Default Country Org');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%2$s', '%1$s', 'DC-EC', 'Ecuador SA', 'America/Guayaquil'),
                    ('%3$s', '%1$s', 'DC-PE', 'Sin ajustes SAC', 'America/Lima');
                INSERT INTO tms.company_settings (company_id, default_country) VALUES ('%2$s', 'EC');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%4$s', 'dc.planner@example.invalid', 'DC Planner');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT u.id, '%1$s', c.id FROM tms.app_user u CROSS JOIN tms.company c
                 WHERE u.email = 'dc.planner@example.invalid' AND c.organization_id = '%1$s';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = 'dc.planner@example.invalid'
                JOIN tms.role r ON r.code = 'PLANNER';
                """.formatted(ORGANIZATION, ECUADOR, NO_ROW, PLANNER_AUTH));
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    @Test
    @DisplayName("/me carries each company's default country, PE when it has no settings row")
    void meCarriesTheDefaultCountry() throws Exception {
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwts.validFor(PLANNER_AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[?(@.code == 'DC-EC')].defaultCountry").value("EC"))
                .andExpect(jsonPath("$.companies[?(@.code == 'DC-EC')].timeZone").value("America/Guayaquil"))
                .andExpect(jsonPath("$.companies[?(@.code == 'DC-PE')].defaultCountry").value("PE"));
    }

    @Test
    @DisplayName("/companies/current reads it under the tenant's RLS scope too")
    void currentCompanyCarriesTheDefaultCountry() throws Exception {
        mockMvc.perform(get("/api/v1/companies/current")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwts.validFor(PLANNER_AUTH))
                        .header(ApiHeaders.COMPANY_ID, ECUADOR.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultCountry").value("EC"));
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not run " + sql, failed);
        }
    }
}
