package com.ebim.tms.iam.entitlements;

import com.ebim.tms.iam.entitlements.api.PlatformEntitlementsController;
import com.ebim.tms.iam.entitlements.application.ApplyEntitlementSnapshotService;
import com.ebim.tms.iam.entitlements.application.CommercialEntitlementService;
import com.ebim.tms.iam.entitlements.application.EntitlementApplyWriter;
import com.ebim.tms.iam.entitlements.application.EntitlementQueryService;
import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.application.PlatformEntitlementsConfig;
import com.ebim.tms.iam.entitlements.domain.CommercialAccess;
import com.ebim.tms.iam.entitlements.domain.EnforcementMode;
import com.ebim.tms.iam.provisioning.api.PlatformProvisioningController;
import com.ebim.tms.iam.provisioning.application.PlatformProvisioningService;
import com.ebim.tms.iam.provisioning.application.PlatformTenantProvisioner;
import com.ebim.tms.iam.provisioning.infrastructure.InMemoryPlatformProvisioningRepository;
import com.ebim.tms.iam.provisioning.security.PlatformProvisioningSecurityConfig;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CCP phase 12, X-07: a FILE mailbox between MasterAdmin's real M2M client and TMS's real receiver,
 * for environments where no local port can be opened (sandbox). Started by
 * {@code masteradmin/scripts/ccp/tms-x07-e2e.mts}; disabled on its own.
 *
 * <p>Each {@code *.req.json} in the mailbox runs through TMS's REAL MasterAdmin chain (ES256 decoder
 * with the public key the script left there), the real controller and the real services over an
 * in-memory store; the answer is written to {@code *.res.json}. It also answers the commercial access
 * decision and mode changes, so the scenario can check the TMS side.
 */
@EnabledIfEnvironmentVariable(named = "CCP_X07_MAILBOX", matches = ".+")
@WebMvcTest(controllers = {PlatformEntitlementsController.class, PlatformProvisioningController.class})
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
    PlatformEntitlementsConfig.class,
    ApplyEntitlementSnapshotService.class,
    EntitlementApplyWriter.class,
    EntitlementQueryService.class,
    CommercialEntitlementService.class,
    JtiGuard.class,
    MasterAdminMailboxBridgeTest.Fakes.class
})
@EnableConfigurationProperties(TmsSecurityProperties.class)
@ActiveProfiles("test")
class MasterAdminMailboxBridgeTest {

    private static final Path MAILBOX = Path.of(System.getenv().getOrDefault("CCP_X07_MAILBOX", "."));

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        InMemoryEntitlementStore entitlementStore() {
            return new InMemoryEntitlementStore(EnforcementMode.SHADOW);
        }

        @Bean
        InMemoryPlatformProvisioningRepository platformProvisioningRepository() {
            return new InMemoryPlatformProvisioningRepository();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return Mockito.mock(PlatformTransactionManager.class);
        }
    }

    @DynamicPropertySource
    static void enable(DynamicPropertyRegistry registry) {
        registry.add("tms.platform-provisioning.enabled", () -> "true");
        registry.add("tms.platform-provisioning.public-key", () -> {
            try {
                return Files.readString(MAILBOX.resolve("public.pem"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        registry.add("tms.platform-entitlements.environment", () -> "DEV");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryEntitlementStore store;

    @Autowired
    private CommercialEntitlementService commercial;

    @Test
    void serveTheMailbox() throws Exception {
        JsonNode setup = ContractFixtures.JSON.readTree(MAILBOX.resolve("setup.json").toFile());
        store.provision(UUID.fromString(setup.get("controlPlaneTenantId").stringValue()),
                UUID.fromString(setup.get("organizationId").stringValue()));
        Files.writeString(MAILBOX.resolve("ready"), "ok");

        Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
        while (Instant.now().isBefore(deadline) && !Files.exists(MAILBOX.resolve("stop"))) {
            List<Path> requests;
            try (Stream<Path> files = Files.list(MAILBOX)) {
                requests = files.filter(p -> p.getFileName().toString().endsWith(".req.json"))
                        .sorted(Comparator.comparing(Path::toString)).toList();
            }
            for (Path request : requests) {
                ObjectNode answer = serve(ContractFixtures.JSON.readTree(request.toFile()));
                String name = request.getFileName().toString().replace(".req.json", "");
                Path tmp = MAILBOX.resolve(name + ".res.tmp");
                Files.writeString(tmp, answer.toString());
                Files.move(tmp, MAILBOX.resolve(name + ".res.json"), StandardCopyOption.ATOMIC_MOVE);
                Files.delete(request);
            }
            Thread.sleep(15);
        }
    }

    private ObjectNode serve(JsonNode request) throws Exception {
        ObjectNode answer = ContractFixtures.JSON.createObjectNode();
        switch (request.get("kind").stringValue()) {
            case "http" -> {
                MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(
                        HttpMethod.valueOf(request.get("method").stringValue()), request.get("path").stringValue());
                for (Map.Entry<String, JsonNode> header : request.get("headers").properties()) {
                    builder.header(header.getKey(), header.getValue().stringValue());
                }
                if (request.hasNonNull("body")) {
                    builder.content(request.get("body").stringValue().getBytes(StandardCharsets.UTF_8));
                }
                MockHttpServletResponse response = mockMvc.perform(builder).andReturn().getResponse();
                answer.put("status", response.getStatus());
                answer.put("contentType", response.getContentType());
                answer.put("body", response.getContentAsString(StandardCharsets.UTF_8));
            }
            case "access" -> {
                CommercialAccess access = commercial.access(UUID.fromString(request.get("organizationId").stringValue()));
                answer.put("allowed", access.allowed());
                answer.put("reason", access.reason().name());
            }
            case "mode" -> {
                // Operator step, as the documented UPDATE/INSERT would do (one step at a time is the
                // database trigger's rule, proven by PlatformEntitlementsIntegrationTest).
                store.setMode(request.get("scope").stringValue(),
                        EnforcementMode.valueOf(request.get("mode").stringValue()));
                answer.put("value", store.mode(UUID.fromString(request.get("tenant").stringValue())).name());
            }
            default -> answer.put("error", "unknown kind");
        }
        return answer;
    }
}
