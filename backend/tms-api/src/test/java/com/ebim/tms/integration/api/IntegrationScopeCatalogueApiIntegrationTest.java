package com.ebim.tms.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.integration.domain.IntegrationScope;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
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
 * {@code GET /api/v1/integration-clients/scopes}: the catalogue the credentials form is drawn from
 * (D4, QAS 2026-10-22 - the form hard-coded 5 of the 12 scopes, so the EWM could not be connected
 * from the UI). The list is the enum, in order, and reading it takes {@code integration.client:read}.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationScopeCatalogueApiIntegrationTest.JwtDecoderOverride.class)
class IntegrationScopeCatalogueApiIntegrationTest {

    private static final String SCOPES = "/api/v1/integration-clients/scopes";

    private static final UUID ORGANIZATION = UUID.fromString("77777777-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("77777777-0000-4000-8000-0000000000c1");
    private static final UUID ADMIN_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e1");
    private static final UUID PLANNER_AUTH = UUID.fromString("77777777-0000-4000-8000-0000000000e2");

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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_integration_scope_catalogue");
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%1$s', 'SCOPE-ORG', 'Scope Organization');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%2$s', '%1$s', 'SCOPE-A', 'Scope Company', 'America/Guayaquil');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%3$s', 'scope.admin@example.invalid', 'Scope Admin'),
                    ('%4$s', 'scope.planner@example.invalid', 'Scope Planner');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%1$s', '%2$s' FROM tms.app_user WHERE email LIKE 'scope.%%@example.invalid';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id
                JOIN tms.role r ON r.code = CASE u.email WHEN 'scope.admin@example.invalid' THEN 'COMPANY_ADMIN'
                                                         ELSE 'PLANNER' END
                WHERE m.company_id = '%2$s';
                """.formatted(ORGANIZATION, COMPANY, ADMIN_AUTH, PLANNER_AUTH));
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    @Test
    @DisplayName("an administrator reads all twelve scopes, in enum order, with group and carrier flag")
    void administratorReadsTheWholeCatalogue() throws Exception {
        String body = mockMvc.perform(get(SCOPES)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwts.validFor(ADMIN_AUTH))
                        .header(ApiHeaders.COMPANY_ID, COMPANY.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> codes = JsonPath.read(body, "$[*].code");
        assertThat(codes).containsExactlyElementsOf(
                Arrays.stream(IntegrationScope.values()).map(IntegrationScope::code).toList());
        assertThat(codes).hasSize(12).contains("integration.dispatch:write", "integration.warehouse-milestone:write");

        List<String> warehouse = JsonPath.read(body, "$[?(@.group == 'WAREHOUSE')].code");
        assertThat(warehouse).containsExactlyInAnyOrder("integration.shipment:read", "integration.dispatch:write",
                "integration.warehouse-milestone:write", "integration.material:write", "integration.order:read",
                "integration.warehouse-order-event:write");
        List<String> carrierBound = JsonPath.read(body, "$[?(@.requiresCarrier == true)].code");
        assertThat(carrierBound).containsExactly("integration.tender:respond");
    }

    @Test
    @DisplayName("a planner, who holds no integration.client:read, is refused")
    void plannerIsRefused() throws Exception {
        mockMvc.perform(get(SCOPES)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwts.validFor(PLANNER_AUTH))
                        .header(ApiHeaders.COMPANY_ID, COMPANY.toString()))
                .andExpect(status().isForbidden());
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
