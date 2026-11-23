package com.ebim.tms.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.reference.TemplateEmailPort.TemplateEmail;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** What TMS puts on the wire to EBIM MailHub (ADR-031), against a local stand-in - never the real one. */
class MailHubEmailSenderTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private record Captured(String key, String region, String contentType, String body) {
    }

    private String serve(int status, AtomicReference<Captured> captured) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/functions/v1/mailhub-send", exchange -> {
            captured.set(new Captured(exchange.getRequestHeaders().getFirst("X-MailHub-Key"),
                    exchange.getRequestHeaders().getFirst("x-region"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] answer = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/functions/v1/mailhub-send";
    }

    private MailHubEmailSender sender(String url, String token) {
        return new MailHubEmailSender(new MailHubProperties(url, token, null, Duration.ofSeconds(5)), json);
    }

    private static TemplateEmail email() {
        return new TemplateEmail(List.of("rosa.quispe@empresa.com"), "restablecer-contrasena",
                Map.of("nombre", "Rosa Quispe", "enlace", "https://example.invalid/reset"), "REF-123",
                "restablecer-contrasena-REF-123");
    }

    @Test
    @DisplayName("posts the template in MailHub's shape, with the key and the region as headers")
    void sendsMailHubsShape() throws IOException {
        AtomicReference<Captured> captured = new AtomicReference<>();
        sender(serve(200, captured), "test-token").send(email());

        assertThat(captured.get().key()).isEqualTo("test-token");
        assertThat(captured.get().region()).isEqualTo("us-east-1");
        assertThat(captured.get().contentType()).startsWith("application/json");
        JsonNode body = json.readTree(captured.get().body());
        assertThat(body.path("to").get(0).asString()).isEqualTo("rosa.quispe@empresa.com");
        assertThat(body.path("content").path("type").asString()).isEqualTo("template");
        assertThat(body.path("content").path("templateKey").asString()).isEqualTo("restablecer-contrasena");
        assertThat(body.path("content").path("variables").path("nombre").asString()).isEqualTo("Rosa Quispe");
        assertThat(body.path("referenceId").asString()).isEqualTo("REF-123");
        assertThat(body.path("idempotencyKey").asString()).isEqualTo("restablecer-contrasena-REF-123");
    }

    @Test
    @DisplayName("a refusal is an error that names the status and never the token")
    void refusalIsAnError() throws IOException {
        AtomicReference<Captured> captured = new AtomicReference<>();
        MailHubEmailSender sender = sender(serve(401, captured), "secret-token-value");

        assertThatThrownBy(() -> sender.send(email()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining("secret-token-value");
    }

    @Test
    @DisplayName("without a token it is unavailable and its properties never print one")
    void unconfigured() {
        MailHubEmailSender sender = sender("https://example.invalid/mailhub-send", " ");
        assertThat(sender.isAvailable()).isFalse();
        assertThatThrownBy(() -> sender.send(email())).isInstanceOf(IllegalStateException.class);
        assertThat(new MailHubProperties("https://x", "abc", null, null).toString()).doesNotContain("abc")
                .contains("token=<set>");
    }
}
