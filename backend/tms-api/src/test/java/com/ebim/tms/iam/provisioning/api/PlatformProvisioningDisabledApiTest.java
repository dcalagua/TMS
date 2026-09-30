package com.ebim.tms.iam.provisioning.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.iam.provisioning.application.GenericProvisioningFixtures;
import com.ebim.tms.iam.provisioning.application.PlatformProvisioningService;
import com.ebim.tms.iam.provisioning.application.PlatformTenantProvisioner;
import com.ebim.tms.iam.provisioning.infrastructure.InMemoryPlatformProvisioningRepository;
import com.ebim.tms.iam.provisioning.security.PlatformM2mTestTokens;
import com.ebim.tms.iam.provisioning.security.PlatformProvisioningSecurityConfig;
import com.ebim.tms.iam.provisioning.security.PlatformScopes;
import com.ebim.tms.shared.api.ApiExceptionHandler;
import com.ebim.tms.shared.api.ApiExceptionResponder;
import com.ebim.tms.shared.config.ApplicationConfig;
import com.ebim.tms.shared.security.PublicApiPaths;
import com.ebim.tms.shared.security.SecurityConfig;
import com.ebim.tms.shared.security.SecurityTestConfiguration;
import com.ebim.tms.shared.security.TmsAccessDeniedHandler;
import com.ebim.tms.shared.security.TmsAuthenticationEntryPoint;
import com.ebim.tms.shared.security.TmsJwtAuthenticationConverter;
import com.ebim.tms.shared.security.TmsSecurityProperties;
import com.ebim.tms.shared.web.WebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The default: provisioning OFF. The chain is still registered and refuses everything - the prefix
 * is never handed to the user chain - and the probe tells MasterAdmin the surface is unavailable.
 */
@WebMvcTest(controllers = PlatformProvisioningController.class)
@Import({
    ApplicationConfig.class,
    SecurityConfig.class,
    PublicApiPaths.class,
    TmsJwtAuthenticationConverter.class,
    TmsAuthenticationEntryPoint.class,
    TmsAccessDeniedHandler.class,
    ApiExceptionResponder.class,
    ApiExceptionHandler.class,
    WebConfig.class,
    SecurityTestConfiguration.class,
    PlatformProvisioningSecurityConfig.class,
    PlatformProvisioningService.class,
    PlatformTenantProvisioner.class,
    PlatformProvisioningDisabledApiTest.Fakes.class
})
@EnableConfigurationProperties(TmsSecurityProperties.class)
@ActiveProfiles("test")
class PlatformProvisioningDisabledApiTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        InMemoryPlatformProvisioningRepository platformProvisioningRepository() {
            return new InMemoryPlatformProvisioningRepository();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return Mockito.mock(PlatformTransactionManager.class);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("health answers 503 {status: unavailable}")
    void healthUnavailable() throws Exception {
        mockMvc.perform(get("/internal/platform-provisioning/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("unavailable"));
    }

    @Test
    @DisplayName("create is refused with 503 M2M_NOT_CONFIGURED even with a well-formed token")
    void createRefused() throws Exception {
        mockMvc.perform(post("/internal/platform-provisioning/tenants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PlatformM2mTestTokens.valid(PlatformScopes.TENANT_CREATE))
                        .header("Idempotency-Key", "ma-prov-v1-disabled-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GenericProvisioningFixtures.json(GenericProvisioningFixtures.TENANT_A, "alpha-tms",
                                "admin@alpha.ebim.test", "Alpha")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("M2M_NOT_CONFIGURED"));
    }
}
