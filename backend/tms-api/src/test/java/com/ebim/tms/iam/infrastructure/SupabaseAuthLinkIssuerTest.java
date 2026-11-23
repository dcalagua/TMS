package com.ebim.tms.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.iam.application.AuthEmailProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** How TMS asks Supabase Auth for recovery and invitation links (ADR-031), against a local stand-in. */
class SupabaseAuthLinkIssuerTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private record Captured(String path, String apikey, String authorization, String body) {
    }

    private SupabaseAuthLinkIssuer issuer(int status, String answer, AtomicReference<Captured> captured)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(new Captured(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("apikey"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        return new SupabaseAuthLinkIssuer(new AuthEmailProperties(null, null,
                new AuthEmailProperties.Supabase(url, "service-role-key")), json);
    }

    /** A stand-in that answers by the {@code type} in the body: {@code invite} then {@code recovery}. */
    private SupabaseAuthLinkIssuer issuerByType(int inviteStatus, String inviteAnswer, int recoveryStatus,
            String recoveryAnswer, List<String> types) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String type = json.readTree(body).path("type").asString();
            types.add(type);
            boolean invite = "invite".equals(type);
            byte[] bytes = (invite ? inviteAnswer : recoveryAnswer).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(invite ? inviteStatus : recoveryStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new SupabaseAuthLinkIssuer(new AuthEmailProperties(null, null,
                new AuthEmailProperties.Supabase(url, "service-role-key")), json);
    }

    @Test
    @DisplayName("an invitation creates the account and returns its link and its id")
    void invitationOfANewAccount() throws IOException {
        UUID account = UUID.randomUUID();
        List<String> types = new ArrayList<>();
        var issuer = issuerByType(200, "{\"id\":\"" + account + "\",\"action_link\":\"https://auth.example/invite\"}",
                500, "{}", types);

        var issued = issuer.invitationLink("nueva@empresa.com", "http://localhost:5173/reset-password");

        assertThat(issued).isPresent();
        assertThat(issued.get().link()).isEqualTo("https://auth.example/invite");
        assertThat(issued.get().authUserId()).isEqualTo(account);
        assertThat(types).containsExactly("invite");
    }

    @Test
    @DisplayName("an account that already exists gets a recovery link to it instead, still with its id")
    void invitationOfAnExistingAccount() throws IOException {
        UUID account = UUID.randomUUID();
        List<String> types = new ArrayList<>();
        var issuer = issuerByType(422, "{\"msg\":\"A user with this email address has already been registered\"}",
                200, "{\"user\":{\"id\":\"" + account + "\"},\"properties\":{\"action_link\":\"https://auth.example/recovery\"}}",
                types);

        var issued = issuer.invitationLink("existente@empresa.com", "http://localhost:5173/reset-password");

        assertThat(issued).isPresent();
        assertThat(issued.get().link()).isEqualTo("https://auth.example/recovery");
        assertThat(issued.get().authUserId()).isEqualTo(account);
        assertThat(types).containsExactly("invite", "recovery");
    }

    @Test
    @DisplayName("asks the admin API for a recovery link that lands on the reset page, with the service key")
    void asksForARecoveryLink() throws IOException {
        AtomicReference<Captured> captured = new AtomicReference<>();
        var issuer = issuer(200, "{\"id\":\"u1\",\"action_link\":\"https://auth.example/verify?token=abc\"}", captured);

        assertThat(issuer.recoveryLink("rosa@empresa.com", "http://localhost:5173/reset-password"))
                .contains("https://auth.example/verify?token=abc");
        assertThat(captured.get().path()).isEqualTo("/auth/v1/admin/generate_link");
        assertThat(captured.get().apikey()).isEqualTo("service-role-key");
        assertThat(captured.get().authorization()).isEqualTo("Bearer service-role-key");
        JsonNode body = json.readTree(captured.get().body());
        assertThat(body.path("type").asString()).isEqualTo("recovery");
        assertThat(body.path("email").asString()).isEqualTo("rosa@empresa.com");
        assertThat(body.path("redirect_to").asString()).isEqualTo("http://localhost:5173/reset-password");
    }

    @Test
    @DisplayName("reads the link under properties too, as some Auth versions nest it")
    void readsNestedLink() throws IOException {
        var issuer = issuer(200, "{\"properties\":{\"action_link\":\"https://auth.example/nested\"}}",
                new AtomicReference<>());
        assertThat(issuer.recoveryLink("rosa@empresa.com", "http://x/reset-password"))
                .contains("https://auth.example/nested");
    }

    @Test
    @DisplayName("no Auth account is an empty answer, any other refusal an error")
    void missingAccountAndErrors() throws IOException {
        assertThat(issuer(404, "{\"msg\":\"User not found\"}", new AtomicReference<>())
                .recoveryLink("nobody@empresa.com", "http://x/reset-password")).isEmpty();
        stop();
        var failing = issuer(500, "{}", new AtomicReference<>());
        assertThatThrownBy(() -> failing.recoveryLink("rosa@empresa.com", "http://x/reset-password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("service-role-key");
    }
}
