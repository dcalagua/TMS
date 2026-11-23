package com.ebim.tms.iam.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ebim.tms.database.DockerAvailability;
import com.ebim.tms.database.PostgresTestDatabase;
import com.ebim.tms.iam.application.AuthLinkIssuer;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.reference.TemplateEmailPort;
import com.ebim.tms.shared.security.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The new user's welcome email (ADR-031), end to end through the real filter chain and a migrated
 * PostgreSQL - with MailHub and Supabase Auth replaced by recording fakes. Nothing is sent and no Auth
 * account is created anywhere real.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(UserInvitationApiIntegrationTest.Overrides.class)
class UserInvitationApiIntegrationTest {

    private static final String USERS = "/api/v1/admin/users";
    private static final String ACCESS_URL = "http://localhost:5173";
    private static final String REDIRECT = "http://localhost:5173/reset-password";

    private static final UUID ORGANIZATION = UUID.fromString("48888888-0000-4000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("48888888-0000-4000-8000-0000000000c1");
    private static final UUID ADMIN_AUTH = UUID.fromString("48888888-0000-4000-8000-0000000000e1");
    private static final UUID EXISTING_AUTH = UUID.fromString("48888888-0000-4000-8000-0000000000e2");

    private static String jdbcUrl;

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;

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

    /** Creates "the account" deterministically from the address, as Auth would create one per email. */
    static final class FakeAuth implements AuthLinkIssuer {
        static UUID accountOf(String email) {
            return UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public Optional<String> recoveryLink(String email, String redirectTo) {
            return Optional.empty();
        }

        @Override
        public Optional<IssuedLink> invitationLink(String email, String redirectTo) {
            return Optional.of(new IssuedLink("https://auth.example/invite?e=" + email + "&r=" + redirectTo,
                    accountOf(email)));
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
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_user_invitation_api");
        execute("""
                INSERT INTO tms.organization (id, code, name) VALUES ('%s', 'UI-ORG', 'Invitation Organization');
                INSERT INTO tms.company (id, organization_id, code, name, time_zone) VALUES
                    ('%s', '%s', 'UI-A', 'Company A', 'America/Lima');
                INSERT INTO tms.app_user (auth_user_id, email, full_name) VALUES
                    ('%s', 'ui.admin@example.invalid', 'UI Admin'),
                    ('%s', 'ya.tiene.cuenta@example.invalid', 'Ya Tiene Cuenta');
                INSERT INTO tms.app_user (email, full_name) VALUES
                    ('preprovisionado@example.invalid', 'Pre Provisionado');
                INSERT INTO tms.membership (app_user_id, organization_id, company_id)
                SELECT id, '%s', '%s' FROM tms.app_user WHERE email = 'ui.admin@example.invalid';
                INSERT INTO tms.membership_role (membership_id, role_id)
                SELECT m.id, r.id FROM tms.membership m
                JOIN tms.app_user u ON u.id = m.app_user_id AND u.email = 'ui.admin@example.invalid'
                JOIN tms.role r ON r.code = 'COMPANY_ADMIN';
                """.formatted(ORGANIZATION, COMPANY, ORGANIZATION, ADMIN_AUTH, EXISTING_AUTH, ORGANIZATION, COMPANY));
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
        registry.add("tms.auth.password-reset.redirect-url", () -> REDIRECT);
        registry.add("tms.auth.invitation.access-url", () -> ACCESS_URL);
        registry.add("tms.auth.invitation.link-validity", () -> "15 minutos");
        registry.add("tms.auth.supabase.url", () -> "http://auth.invalid");
        registry.add("tms.auth.supabase.service-key", () -> "unused-by-the-fake");
    }

    @BeforeEach
    void reset() {
        adminToken = TestJwts.validFor(ADMIN_AUTH);
        FakeMail.available = true;
        FakeMail.sent.clear();
    }

    @Test
    @DisplayName("a new person gets registro-usuario with their data, and their Auth account is linked")
    void newPersonIsInvitedAndLinked() throws Exception {
        String body = invite("rosa.quispe@example.invalid", "Rosa Quispe")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinked").value(false))
                .andReturn().getResponse().getContentAsString();
        String appUserId = JsonPath.read(body, "$.appUserId");

        TemplateEmailPort.TemplateEmail email = FakeMail.sent.poll(5, TimeUnit.SECONDS);
        assertThat(email).isNotNull();
        assertThat(email.to()).containsExactly("rosa.quispe@example.invalid");
        assertThat(email.templateKey()).isEqualTo("registro-usuario");
        assertThat(email.referenceId()).isEqualTo(appUserId);
        assertThat(email.idempotencyKey()).startsWith("registro-usuario-" + appUserId + "-");
        assertThat(email.variables())
                .containsEntry("producto", "TMS")
                .containsEntry("vigencia", "15 minutos")
                .containsEntry("nombre", "Rosa Quispe")
                .containsEntry("correo", "rosa.quispe@example.invalid")
                .containsEntry("urlAcceso", ACCESS_URL)
                .containsEntry("enlace", "https://auth.example/invite?e=rosa.quispe@example.invalid&r=" + REDIRECT);
        // Linked to the account the link belongs to: no UPDATE by hand any more.
        assertThat(query("SELECT auth_user_id FROM tms.app_user WHERE id = '" + appUserId + "'"))
                .isEqualTo(FakeAuth.accountOf("rosa.quispe@example.invalid").toString());
    }

    @Test
    @DisplayName("a pre-provisioned profile with no account gets the same welcome and is linked")
    void preProvisionedProfileIsInvited() throws Exception {
        invite("preprovisionado@example.invalid", "Ignored Name").andExpect(status().isCreated());

        TemplateEmailPort.TemplateEmail email = FakeMail.sent.poll(5, TimeUnit.SECONDS);
        assertThat(email).isNotNull();
        // The profile is theirs: the name the administrator typed is not used.
        assertThat(email.variables()).containsEntry("nombre", "Pre Provisionado");
        assertThat(query("SELECT auth_user_id FROM tms.app_user WHERE email = 'preprovisionado@example.invalid'"))
                .isEqualTo(FakeAuth.accountOf("preprovisionado@example.invalid").toString());
    }

    @Test
    @DisplayName("somebody who already has an account gets access and no email")
    void existingAccountGetsNoEmail() throws Exception {
        invite("ya.tiene.cuenta@example.invalid", "Ya Tiene Cuenta")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinked").value(true));

        assertThat(FakeMail.sent.poll(1500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("«Reenviar invitación» sends it again under a new key, and is limited per person")
    void resendIsANewEmailAndLimited() throws Exception {
        String membershipId = JsonPath.read(invite("reenvio@example.invalid", "Reenvio")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.membershipId");
        TemplateEmailPort.TemplateEmail first = FakeMail.sent.poll(5, TimeUnit.SECONDS);
        assertThat(first).isNotNull();

        Thread.sleep(5);
        resend(membershipId).andExpect(status().isAccepted());
        TemplateEmailPort.TemplateEmail again = FakeMail.sent.poll(5, TimeUnit.SECONDS);
        assertThat(again).isNotNull();
        assertThat(again.templateKey()).isEqualTo("registro-usuario");
        assertThat(again.idempotencyKey()).isNotEqualTo(first.idempotencyKey());

        for (int i = 1; i < 5; i++) {
            resend(membershipId).andExpect(status().isAccepted());
        }
        resend(membershipId).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("no invitation for a revoked access, and 503 where email is not configured")
    void resendRefusals() throws Exception {
        String membershipId = JsonPath.read(invite("revocado@example.invalid", "Revocado")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.membershipId");
        FakeMail.sent.poll(5, TimeUnit.SECONDS);

        FakeMail.available = false;
        resend(membershipId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("feature-not-configured"));

        FakeMail.available = true;
        mockMvc.perform(asAdmin(post(USERS + "/" + membershipId + "/revoke"))).andExpect(status().isOk());
        resend(membershipId).andExpect(status().isConflict());
        assertThat(FakeMail.sent.poll(1000, TimeUnit.MILLISECONDS)).isNull();
    }

    private ResultActions invite(String email, String fullName) throws Exception {
        return mockMvc.perform(asAdmin(post(USERS)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"fullName\":\"" + fullName + "\",\"roleCodes\":[\"VIEWER\"]}"));
    }

    private ResultActions resend(String membershipId) throws Exception {
        return mockMvc.perform(asAdmin(post(USERS + "/" + membershipId + "/resend-invitation")));
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + adminToken).header(ApiHeaders.COMPANY_ID, COMPANY.toString());
    }

    private static String query(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static void execute(String sql) {
        try (var connection = PostgresTestDatabase.connect(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not seed an invitation fixture", failed);
        }
    }
}
