package com.ebim.tms.shared.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.shared.config.ApplicationConfig;
import com.ebim.tms.shared.security.PublicApiPaths;
import com.ebim.tms.shared.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice: proves the system endpoint answers and, just as importantly, that the
 * baseline security chain denies everything else by default.
 */
@WebMvcTest(SystemInfoController.class)
@Import({ApplicationConfig.class, SecurityConfig.class, PublicApiPaths.class})
@ActiveProfiles("test")
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
    @DisplayName("business endpoints are denied by default, not silently open")
    void unknownBusinessEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());
    }
}
