package com.ebim.tms.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice: proves the system endpoint answers and, just as importantly, that the
 * baseline security chain denies everything else by default.
 */
@WebMvcTest(SystemInfoController.class)
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
    SecurityTestConfiguration.class
})
@EnableConfigurationProperties(TmsSecurityProperties.class)
@ActiveProfiles("test")
// A deployment stamps this as TMS_RELEASE_COMMIT (or Render supplies RENDER_GIT_COMMIT). Setting
// it here is what makes "the endpoint publishes the revision it is running" a checked claim
// rather than a comment. The value is a full-length SHA on purpose: the assertion below is that
// it comes back shortened.
@TestPropertySource(properties = "tms.release.commit=0123456789abcdef0123456789abcdef01234567")
class SystemInfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("system info is public and identifies the service")
    void systemInfoIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("TMS by EBIM"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("system info publishes the deployed commit, shortened")
    void systemInfoPublishesTheDeployedCommit() throws Exception {
        mockMvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                // Twelve characters of the configured SHA - enough to `git show`, and the length
                // scripts/ops/verify-deployment.sh compares against what was promoted.
                .andExpect(jsonPath("$.commit").value("0123456789ab"));
    }

    @Test
    @DisplayName("a build that recorded no commit reports none rather than a plausible substitute")
    void anAbsentCommitStaysAbsent() {
        // The whole value of the field is that an operator can tell "this build did not record
        // its revision" from "this build is the wrong revision". A default of "unknown", or of
        // the Maven version, would collapse those two into one answer. Asserted on the static
        // helper because a second Spring context per property combination costs more than the
        // claim is worth.
        assertThat(SystemInfoController.shorten(null)).isNull();
        assertThat(SystemInfoController.shorten("abc")).isEqualTo("abc");
    }

    @Test
    @DisplayName("business endpoints are denied by default, not silently open")
    void unknownBusinessEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());
    }
}
