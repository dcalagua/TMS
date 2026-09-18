package com.ebim.tms.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Control-plane provisioning end to end, against a real PostgreSQL (migration V51).
 *
 * <p>What only a test like this can show: that the whole tenant appears in one transaction or not at
 * all, that a retried call creates nothing the second time, that two simultaneous calls produce one
 * tenant, and that a human session cannot reach this API no matter what it carries.
 *
 * <p>The signing key is generated here and discarded with the JVM. The QAS key is never read.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(PlatformProvisioningApiIntegrationTest.JwtDecoderOverride.class)
class PlatformProvisioningApiIntegrationTest {

    /**
     * The human chain needs a decoder that answers rather than one that tries to fetch a JWKS from
     * the test profile's unroutable host. Without it, "a control-plane token opens no door on the
     * human API" would pass for the wrong reason - a network error, not a rejected token.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class JwtDecoderOverride {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        org.springframework.security.oauth2.jwt.JwtDecoder testJwtDecoder() {
            return com.ebim.tms.shared.security.TestJwts.decoder();
        }
    }

    private static final String TENANTS = PlatformApiPaths.V1 + "/tenants";
    private static final String ISSUER = "masteradmin.ebim";
    private static final String AUDIENCE = "tms.ebim";
    private static final String CREATE = "tms:tenant:create";
    private static final String READ = "tms:tenant:read";

    private static final KeyPair CONTROL_PLANE_KEY = ecKeyPair();
    private static String jdbcUrl;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_platform_api");
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
        registry.add("tms.platform.m2m.enabled", () -> "true");
        registry.add("tms.platform.m2m.issuer", () -> ISSUER);
        registry.add("tms.platform.m2m.audience", () -> AUDIENCE);
        registry.add("tms.platform.m2m.public-key-base64", PlatformProvisioningApiIntegrationTest::publicKeyBase64);
    }

    @Nested
    @DisplayName("provisioning a tenant")
    class Create {

        @Test
        @DisplayName("creates the organization, the company and an administrator who can be signed in as")
        void createsTheTenant() throws Exception {
            UUID tenant = UUID.randomUUID();
            Payload payload = Payload.of(tenant, "cliente-norte");

            String body = mockMvc.perform(provision(payload, key(), token(CREATE)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.replayed").value(false))
                    .andExpect(jsonPath("$.controlPlaneTenantId").value(tenant.toString()))
                    .andExpect(jsonPath("$.externalOrganizationId").value(payload.organizationId.toString()))
                    .andExpect(jsonPath("$.externalCompanyId").value(payload.companyId.toString()))
                    .andExpect(jsonPath("$.adminProvisioningStatus").value("PREPROVISIONED"))
                    .andExpect(jsonPath("$.deploymentMode").value("SHARED"))
                    // The slug became a TMS code, upper-cased, and the response says so rather than
                    // leaving the control plane to guess what it can quote back to a customer.
                    .andExpect(jsonPath("$.resources.organizationCode").value("CLIENTE-NORTE"))
                    .andExpect(jsonPath("$.resources.timeZone").value("America/Lima"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("password");

            assertThat(count("SELECT count(*) FROM tms.organization WHERE id = '" + payload.organizationId + "'"))
                    .isEqualTo(1);
            assertThat(count("SELECT count(*) FROM tms.company WHERE id = '" + payload.companyId + "'"))
                    .isEqualTo(1);

            // The administrator exists with no Supabase identity yet: TMS invents no password and
            // creates no auth user. The person claims the row by signing in.
            assertThat(count("SELECT count(*) FROM tms.app_user WHERE email = '" + payload.adminEmail
                    + "' AND auth_user_id IS NULL")).isEqualTo(1);

            // Organization-wide membership, because a company-scoped one would discard an
            // ORGANIZATION-level role and leave the administrator with no permissions at all.
            assertThat(count("""
                    SELECT count(*) FROM tms.membership m
                    JOIN tms.app_user u ON u.id = m.app_user_id
                    JOIN tms.membership_role mr ON mr.membership_id = m.id
                    JOIN tms.role r ON r.id = mr.role_id
                    WHERE u.email = '""" + payload.adminEmail + "' AND m.company_id IS NULL"
                    + " AND r.code = 'ORGANIZATION_ADMIN'")).isEqualTo(1);

            // The audit trail: who asked, with which token, under which correlation id.
            assertThat(count("SELECT count(*) FROM tms.platform_provisioning WHERE control_plane_tenant_id = '"
                    + tenant + "' AND status = 'SUCCEEDED' AND m2m_subject = 'masteradmin-service'"
                    + " AND actor_id = 'u-42' AND m2m_jti IS NOT NULL AND correlation_id IS NOT NULL"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("reads back through GET without ever exposing business data")
        void readsBack() throws Exception {
            UUID tenant = UUID.randomUUID();
            mockMvc.perform(provision(Payload.of(tenant, "cliente-sur"), key(), token(CREATE)))
                    .andExpect(status().isCreated());

            mockMvc.perform(get(TENANTS + "/" + tenant).header("Authorization", "Bearer " + token(READ)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.controlPlaneTenantId").value(tenant.toString()))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.adminProvisioningStatus").value("PREPROVISIONED"))
                    .andExpect(jsonPath("$.resources.companyCode").value("CLIENTE-SUR"))
                    .andExpect(jsonPath("$.orders").doesNotExist())
                    .andExpect(jsonPath("$.vehicles").doesNotExist());
        }

        @Test
        @DisplayName("a tenant nobody provisioned is not found")
        void unknownTenant() throws Exception {
            mockMvc.perform(get(TENANTS + "/" + UUID.randomUUID())
                            .header("Authorization", "Bearer " + token(READ)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotent {

        @Test
        @DisplayName("the same key and the same body replays the first answer and creates nothing")
        void replay() throws Exception {
            UUID tenant = UUID.randomUUID();
            Payload payload = Payload.of(tenant, "replay-co");
            String key = key();

            String first = mockMvc.perform(provision(payload, key, token(CREATE)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            String second = mockMvc.perform(provision(payload, key, token(CREATE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.replayed").value(true))
                    .andReturn().getResponse().getContentAsString();

            // Same tenant, same provisioning id: the second call answered from the record rather
            // than doing the work again.
            assertThat(idOf(second)).isEqualTo(idOf(first));
            assertThat(count("SELECT count(*) FROM tms.organization WHERE id = '"
                    + payload.organizationId + "'")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM tms.platform_provisioning WHERE idempotency_key = '"
                    + key + "'")).isEqualTo(1);
        }

        @Test
        @DisplayName("the same key with a different body is refused, not silently accepted")
        void conflict() throws Exception {
            String key = key();
            mockMvc.perform(provision(Payload.of(UUID.randomUUID(), "first-co"), key, token(CREATE)))
                    .andExpect(status().isCreated());

            mockMvc.perform(provision(Payload.of(UUID.randomUUID(), "second-co"), key, token(CREATE)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("idempotency-conflict"));
        }

        @Test
        @DisplayName("a write with no Idempotency-Key is refused before anything is created")
        void keyRequired() throws Exception {
            UUID tenant = UUID.randomUUID();
            Payload payload = Payload.of(tenant, "no-key-co");

            mockMvc.perform(post(TENANTS)
                            .header("Authorization", "Bearer " + token(CREATE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload.json()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("idempotency-key-required"));

            assertThat(count("SELECT count(*) FROM tms.organization WHERE id = '"
                    + payload.organizationId + "'")).isZero();
        }

        @Test
        @DisplayName("the same tenant under a different key is a conflict, not a second tenant")
        void tenantAlreadyProvisioned() throws Exception {
            UUID tenant = UUID.randomUUID();
            mockMvc.perform(provision(Payload.of(tenant, "once-co"), key(), token(CREATE)))
                    .andExpect(status().isCreated());

            mockMvc.perform(provision(Payload.of(tenant, "twice-co"), key(), token(CREATE)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("tenant-already-provisioned"));

            assertThat(count("SELECT count(*) FROM tms.platform_tenant WHERE control_plane_tenant_id = '"
                    + tenant + "'")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("all or nothing")
    class Atomicity {

        @Test
        @DisplayName("an administrator who belongs elsewhere stops the tenant, and leaves nothing behind")
        void rollsBack() throws Exception {
            Payload first = Payload.of(UUID.randomUUID(), "owner-co");
            mockMvc.perform(provision(first, key(), token(CREATE))).andExpect(status().isCreated());

            // Same administrator email, a different tenant. The refusal happens *after* the
            // organization and the company have been inserted, which is what makes this a rollback
            // test and not merely a validation test.
            Payload second = Payload.of(UUID.randomUUID(), "thief-co");
            second.adminEmail = first.adminEmail;

            mockMvc.perform(provision(second, key(), token(CREATE)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("admin-email-conflict"));

            assertThat(count("SELECT count(*) FROM tms.organization WHERE id = '"
                    + second.organizationId + "'")).isZero();
            assertThat(count("SELECT count(*) FROM tms.company WHERE id = '"
                    + second.companyId + "'")).isZero();
            assertThat(count("SELECT count(*) FROM tms.platform_tenant WHERE control_plane_tenant_id = '"
                    + second.controlPlaneTenantId + "'")).isZero();

            // The failure itself survives the rollback, or nobody could explain it afterwards.
            assertThat(count("SELECT count(*) FROM tms.platform_provisioning WHERE control_plane_tenant_id = '"
                    + second.controlPlaneTenantId + "' AND status = 'FAILED'"
                    + " AND error_code = 'admin-email-conflict'")).isEqualTo(1);
        }

        @Test
        @DisplayName("two simultaneous calls with one key produce one tenant")
        void concurrentSameKey() throws Exception {
            UUID tenant = UUID.randomUUID();
            Payload payload = Payload.of(tenant, "race-co");
            String key = key();
            String bearer = token(CREATE);
            String body = payload.json();

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> call = () -> {
                    start.await();
                    return mockMvc.perform(post(TENANTS)
                                    .header("Authorization", "Bearer " + bearer)
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn().getResponse().getStatus();
                };
                Future<Integer> one = pool.submit(call);
                Future<Integer> two = pool.submit(call);
                start.countDown();

                List<Integer> statuses = List.of(one.get(), two.get());
                // One did the work, the other replayed it. Neither failed.
                assertThat(statuses).containsExactlyInAnyOrder(201, 200);
            } finally {
                pool.shutdownNow();
            }

            assertThat(count("SELECT count(*) FROM tms.platform_tenant WHERE control_plane_tenant_id = '"
                    + tenant + "'")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM tms.platform_provisioning WHERE idempotency_key = '"
                    + key + "'")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM tms.organization WHERE id = '"
                    + payload.organizationId + "'")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("who may call this")
    class Isolation {

        @Test
        @DisplayName("a token with only the read scope cannot provision")
        void missingScope() throws Exception {
            mockMvc.perform(provision(Payload.of(UUID.randomUUID(), "read-only-co"), key(), token(READ)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("missing-scope"));
        }

        @Test
        @DisplayName("no token at all")
        void anonymous() throws Exception {
            mockMvc.perform(get(TENANTS + "/" + UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("the legacy integration credential is not a control-plane token")
        void legacyIntegrationCredential() throws Exception {
            mockMvc.perform(get(TENANTS + "/" + UUID.randomUUID())
                            .header("Authorization", "Basic dGVzdC1jbGllbnQ6dGVzdC1zZWNyZXQ="))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("unauthenticated"));
        }

        @Test
        @DisplayName("a control-plane token opens no door on the human API")
        void platformTokenOnHumanApi() throws Exception {
            mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token(CREATE)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a control-plane token opens no door on the partner API")
        void platformTokenOnIntegrationApi() throws Exception {
            mockMvc.perform(get("/integration/v1/identity/ping")
                            .header("Authorization", "Bearer " + token(CREATE)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("what TMS refuses to guess")
    class Validation {

        @Test
        @DisplayName("a slug that cannot become a TMS code is refused, not truncated")
        void unusableSlug() throws Exception {
            mockMvc.perform(provision(Payload.of(UUID.randomUUID(), "!!"), key(), token(CREATE)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation-failed"));
        }

        @Test
        @DisplayName("a deployment mode this release does not implement is refused, not stored")
        void unsupportedDeploymentMode() throws Exception {
            Payload payload = Payload.of(UUID.randomUUID(), "dedicated-co");
            payload.deploymentMode = "DEDICATED";

            mockMvc.perform(provision(payload, key(), token(CREATE)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation-failed"));
        }

        @Test
        @DisplayName("a body with no control-plane tenant is refused by validation")
        void missingTenantId() throws Exception {
            String body = """
                    {"organization":{"id":"%s","slug":"x-co","name":"X"},
                     "company":{"id":"%s","name":"X"},
                     "deploymentMode":"SHARED"}
                    """.formatted(UUID.randomUUID(), UUID.randomUUID());

            mockMvc.perform(post(TENANTS)
                            .header("Authorization", "Bearer " + token(CREATE))
                            .header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---------------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder provision(
            Payload payload, String key, String bearer) {
        return post(TENANTS)
                .header("Authorization", "Bearer " + bearer)
                .header("Idempotency-Key", key)
                .header("X-Correlation-Id", "corr-" + key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.json());
    }

    /** Mutable so a test can make exactly the one change it is about. */
    private static final class Payload {
        UUID controlPlaneTenantId;
        UUID organizationId;
        UUID companyId;
        String slug;
        String adminEmail;
        String deploymentMode = "SHARED";

        static Payload of(UUID tenant, String slug) {
            Payload payload = new Payload();
            payload.controlPlaneTenantId = tenant;
            payload.organizationId = UUID.randomUUID();
            payload.companyId = UUID.randomUUID();
            payload.slug = slug;
            payload.adminEmail = "admin-" + UUID.randomUUID().toString().substring(0, 8) + "@cliente.test";
            return payload;
        }

        String json() {
            return """
                    {
                      "controlPlaneTenantId": "%s",
                      "organization": {
                        "id": "%s", "slug": "%s", "name": "Cliente", "legalName": "Cliente SAC",
                        "taxId": "20123456789", "countryCode": "PE", "currency": "PEN",
                        "timezone": "America/Lima"
                      },
                      "company": {
                        "id": "%s", "name": "Cliente", "legalName": "Cliente SAC",
                        "taxId": "20123456789", "countryCode": "PE", "currency": "PEN"
                      },
                      "admin": { "email": "%s", "fullName": "Administrador" },
                      "deploymentMode": "%s"
                    }
                    """.formatted(controlPlaneTenantId, organizationId, slug, companyId, adminEmail,
                    deploymentMode);
        }
    }

    private static String token(String scope) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .subject("masteradmin-service")
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plus(Duration.ofSeconds(120))))
                    .claim("scope", scope)
                    .claim("actor_id", "u-42")
                    .claim("actor_role", "PLATFORM_OWNER")
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), claims);
            jwt.sign(new ECDSASigner((ECPrivateKey) CONTROL_PLANE_KEY.getPrivate()));
            return jwt.serialize();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static String idOf(String json) {
        return com.jayway.jsonpath.JsonPath.read(json, "$.provisioningId");
    }

    private static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String publicKeyBase64() {
        ECPublicKey publicKey = (ECPublicKey) CONTROL_PLANE_KEY.getPublic();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(publicKey.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
    }

    private static int count(String sql) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, PostgresTestDatabase.username(),
                PostgresTestDatabase.password());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
