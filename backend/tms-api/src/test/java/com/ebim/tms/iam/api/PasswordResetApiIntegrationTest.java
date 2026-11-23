package com.ebim.tms.iam.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.iam.application.AuthLinkIssuer;
import com.ebim.tms.shared.reference.TemplateEmailPort;
import com.ebim.tms.shared.security.TestJwts;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * «¿Olvidaste tu contraseña?» through MailHub (ADR-031), end to end through the real filter chain
 * and a migrated PostgreSQL - with MailHub and Supabase Auth replaced by recording fakes. No test
 * here sends an email or asks a real Auth server for a link.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PasswordResetApiIntegrationTest.Overrides.class)
class PasswordResetApiIntegrationTest {

    private static final String RESET = "/api/v1/auth/password-reset";
    private static final String REDIRECT = "http://localhost:5173/reset-password";
    private static final AtomicInteger CLIENT = new AtomicInteger();

    private static String jdbcUrl;
    private static String rosaId;

    @Autowired
    private MockMvc mockMvc;

    static final class FakeMail implements TemplateEmailPort {
        static volatile boolean available = true;
        static final BlockingQueue<TemplateEmail> sent = new LinkedBlockingQueue<>();

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public void send(TemplateEmail email) {
            sent.add(email);
        }
    }

    static final class FakeAuth implements AuthLinkIssuer {
        static volatile boolean hasAccount = true;
        static final BlockingQueue<String> redirects = new LinkedBlockingQueue<>();

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public Optional<String> recoveryLink(String email, String redirectTo) {
            redirects.add(redirectTo);
            return hasAccount ? Optional.of("https://auth.example/verify?token=t-" + email) : Optional.empty();
        }

        @Override
        public Optional<IssuedLink> invitationLink(String email, String redirectTo) {
            return Optional.empty();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Overrides {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder();
        }

        @Bean
        @Primary
        TemplateEmailPort fakeMail() {
            return new FakeMail();
        }

        @Bean
        @Primary
        AuthLinkIssuer fakeAuth() {
            return new FakeAuth();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_password_reset_api");
        rosaId = insertReturningId("INSERT INTO tms.app_user (email, full_name) VALUES"
                + " ('rosa.quispe@empresa.com', 'Rosa Quispe')");
        insertReturningId("INSERT INTO tms.app_user (email, full_name, active) VALUES"
                + " ('ex.empleado@empresa.com', 'Ex Empleado', false)");
        insertReturningId("INSERT INTO tms.app_user (email, full_name) VALUES"
                + " ('sin.cuenta@empresa.com', 'Sin Cuenta')");
        insertReturningId("INSERT INTO tms.app_user (email, full_name) VALUES"
                + " ('insistente@empresa.com', 'Insistente')");
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
        registry.add("tms.auth.password-reset.redirect-url", () -> REDIRECT);
        registry.add("tms.auth.password-reset.link-validity", () -> "1 hora");
        registry.add("tms.auth.supabase.url", () -> "http://auth.invalid");
        registry.add("tms.auth.supabase.service-key", () -> "unused-by-the-fake");
    }

    @BeforeEach
    void reset() {
        FakeMail.available = true;
        FakeMail.sent.clear();
        FakeAuth.hasAccount = true;
        FakeAuth.redirects.clear();
    }

    @Test
    @DisplayName("an active user gets the template with their name, address, request time, link and reference")
    void sendsTheTemplate() throws Exception {
        // Typed with capitals: the address is matched case-insensitively, as tms.app_user stores it.
        request("Rosa.Quispe@Empresa.COM").andExpect(status().isAccepted());

        TemplateEmailPort.TemplateEmail email = FakeMail.sent.poll(5, TimeUnit.SECONDS);
        assertThat(email).isNotNull();
        assertThat(email.to()).containsExactly("rosa.quispe@empresa.com");
        assertThat(email.templateKey()).isEqualTo("restablecer-contrasena");
        assertThat(email.referenceId()).isEqualTo(rosaId);
        assertThat(email.idempotencyKey()).isEqualTo("restablecer-contrasena-" + rosaId);
        assertThat(email.variables())
                .containsEntry("nombre", "Rosa Quispe")
                .containsEntry("correo", "rosa.quispe@empresa.com")
                .containsEntry("enlace", "https://auth.example/verify?token=t-rosa.quispe@empresa.com")
                .containsEntry("vigencia", "1 hora")
                .containsEntry("producto", "TMS");
        assertThat(email.variables().get("solicitadoEn")).matches("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}");
        // The link Auth issued lands on the frontend's reset page.
        assertThat(FakeAuth.redirects.poll()).isEqualTo(REDIRECT);
    }

    @Test
    @DisplayName("an unknown, inactive or Auth-less address gets the same 202 and no email")
    void sameAnswerAndNoEmail() throws Exception {
        request("nadie@empresa.com").andExpect(status().isAccepted());
        request("ex.empleado@empresa.com").andExpect(status().isAccepted());
        FakeAuth.hasAccount = false;
        request("sin.cuenta@empresa.com").andExpect(status().isAccepted());

        assertThat(FakeMail.sent.poll(1500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("a malformed address is 400; the path is open to POST only")
    void validationAndScope() throws Exception {
        request("no-es-un-correo").andExpect(status().isBadRequest());
        mockMvc.perform(get(RESET)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("one address is limited to three requests an hour, answered 429 with Retry-After")
    void rateLimitedPerAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            request("insistente@empresa.com").andExpect(status().isAccepted());
        }
        request("insistente@empresa.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("a deployment that cannot send answers 503, for every address alike")
    void notConfigured() throws Exception {
        FakeMail.available = false;

        request("rosa.quispe@empresa.com")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("feature-not-configured"));
    }

    /** Anonymous, from an address of its own so the per-client limit never couples two tests. */
    private ResultActions request(String email) throws Exception {
        String client = "10.0.0." + CLIENT.incrementAndGet();
        return mockMvc.perform(post(RESET)
                .with(http -> {
                    http.setRemoteAddr(client);
                    return http;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private static String insertReturningId(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql + " RETURNING id")) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed a password reset fixture", failed);
        }
    }
}
