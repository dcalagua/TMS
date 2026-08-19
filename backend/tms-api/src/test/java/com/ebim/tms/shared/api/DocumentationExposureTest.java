package com.ebim.tms.shared.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Whether the API description is readable without a token is a deployment decision, so it is
 * tested as one.
 *
 * <p>In this slice springdoc's own endpoints are not registered, so the assertion is about the
 * security chain rather than about the document: 401 means "the chain refused it", and 404
 * means "the chain let it through and there was simply no handler here". That is exactly the
 * distinction that matters.
 */
class DocumentationExposureTest {

    @Nested
    @DisplayName("with public documentation (development default)")
    @WebMvcTest(controllers = SystemInfoController.class)
    @Import({ApplicationConfig.class, SecurityConfig.class, PublicApiPaths.class,
        TmsJwtAuthenticationConverter.class, TmsAuthenticationEntryPoint.class, TmsAccessDeniedHandler.class,
        ApiExceptionResponder.class, ApiExceptionHandler.class, WebConfig.class, SecurityTestConfiguration.class})
    @EnableConfigurationProperties(TmsSecurityProperties.class)
    @ActiveProfiles("test")
    class PublicDocumentation {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("the OpenAPI description is not blocked by the security chain")
        void apiDocsArePermitted() throws Exception {
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
            mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("with documentation closed (production)")
    @WebMvcTest(controllers = SystemInfoController.class,
            properties = "tms.api.public-documentation=false")
    @Import({ApplicationConfig.class, SecurityConfig.class, PublicApiPaths.class,
        TmsJwtAuthenticationConverter.class, TmsAuthenticationEntryPoint.class, TmsAccessDeniedHandler.class,
        ApiExceptionResponder.class, ApiExceptionHandler.class, WebConfig.class, SecurityTestConfiguration.class})
    @EnableConfigurationProperties(TmsSecurityProperties.class)
    @ActiveProfiles("test")
    class ClosedDocumentation {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("the whole API surface is not published to anonymous callers")
        void apiDocsRequireAuthentication() throws Exception {
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("closing the documentation does not close the health probe")
        void healthStaysPublic() throws Exception {
            mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        }
    }
}
