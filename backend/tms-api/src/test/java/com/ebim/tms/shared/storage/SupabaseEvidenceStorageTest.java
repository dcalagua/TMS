package com.ebim.tms.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-020 against a stand-in of the Storage API that answers the way the real one was observed to
 * on 2026-10-12: errors as HTTP 400 with the meaning in the body's {@code statusCode}, a private
 * bucket readable only through {@code /object/authenticated/}, and both key headers required.
 */
class SupabaseEvidenceStorageTest {

    private static final String KEY = "service-role-jwt";
    private static final UUID COMPANY = UUID.fromString("11111111-0000-4000-8000-000000000001");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), ZoneOffset.UTC);

    private HttpServer server;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private SupabaseEvidenceStorage storage;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/storage/v1/", this::handle);
        server.start();
        storage = new SupabaseEvidenceStorage(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                KEY, "tms-evidence", Duration.ofSeconds(5), CLOCK, HttpClient.newHttpClient());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(exchange.getRequestMethod() + " " + path);
        if (!KEY.equals(exchange.getRequestHeaders().getFirst("apikey"))
                || !("Bearer " + KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            reply(exchange, 400, "{\"statusCode\":\"403\",\"error\":\"Unauthorized\"}");
            return;
        }
        if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/storage/v1/object/tms-evidence/")) {
            String key = path.substring("/storage/v1/object/tms-evidence/".length());
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if ("text/plain".equals(type)) {
                reply(exchange, 400, "{\"statusCode\":\"415\",\"error\":\"invalid_mime_type\"}");
            } else if (objects.containsKey(key)) {
                reply(exchange, 400, "{\"statusCode\":\"409\",\"error\":\"Duplicate\"}");
            } else {
                objects.put(key, exchange.getRequestBody().readAllBytes());
                reply(exchange, 200, "{\"Key\":\"tms-evidence/" + key + "\"}");
            }
            return;
        }
        if ("GET".equals(exchange.getRequestMethod())
                && path.startsWith("/storage/v1/object/authenticated/tms-evidence/")) {
            byte[] body = objects.get(path.substring("/storage/v1/object/authenticated/tms-evidence/".length()));
            if (body == null) {
                reply(exchange, 400, "{\"statusCode\":\"404\",\"error\":\"not_found\",\"code\":\"NoSuchKey\"}");
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
            return;
        }
        reply(exchange, 404, "{}");
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    @DisplayName("stores under the shared key format with size and checksum of the bytes, and reads them back")
    void roundTrip() throws Exception {
        byte[] photo = "a photo of a signed delivery note".getBytes(StandardCharsets.UTF_8);

        StoredObject stored = storage.store(COMPANY, "image/jpeg", "foto.jpg", new ByteArrayInputStream(photo), 1024);

        assertThat(stored.storageKey()).matches(COMPANY + "/2026/10/[0-9a-f-]{36}\\.jpg");
        assertThat(stored.sizeBytes()).isEqualTo(photo.length);
        assertThat(stored.checksumSha256())
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(photo)));
        assertThat(objects.get(stored.storageKey())).isEqualTo(photo);

        StoredObjectContent content = storage.open(COMPANY, stored.storageKey());
        assertThat(content.sizeBytes()).isEqualTo(photo.length);
        assertThat(content.stream().readAllBytes()).isEqualTo(photo);
        assertThat(requests).contains("GET /storage/v1/object/authenticated/tms-evidence/" + stored.storageKey());
    }

    @Test
    @DisplayName("an oversized or empty file is refused before anything is sent")
    void refusedLocally() {
        assertThatThrownBy(() -> storage.store(COMPANY, "image/jpeg", null,
                new ByteArrayInputStream(new byte[2048]), 1024)).isInstanceOf(EvidenceRejectedException.class);
        assertThatThrownBy(() -> storage.store(COMPANY, "image/jpeg", null,
                new ByteArrayInputStream(new byte[0]), 1024)).isInstanceOf(EvidenceRejectedException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("another company's key never leaves the process; a missing object is a fault, not 'not found'")
    void keysAndMissingObjects() {
        String foreign = "22222222-0000-4000-8000-000000000002/2026/10/" + UUID.randomUUID() + ".jpg";
        assertThatThrownBy(() -> storage.open(COMPANY, foreign)).isInstanceOf(EvidenceStorageException.class)
                .hasMessageContaining("does not belong");
        assertThatThrownBy(() -> storage.open(COMPANY, "../../etc/passwd")).isInstanceOf(EvidenceStorageException.class);
        assertThat(requests).isEmpty();

        String missing = COMPANY + "/2026/10/" + UUID.randomUUID() + ".jpg";
        assertThatThrownBy(() -> storage.open(COMPANY, missing)).isInstanceOf(EvidenceStorageException.class)
                .hasMessageContaining("missing from the store");
    }

    @Test
    @DisplayName("the store's own refusal of a type is a rejection the caller can show; a bad key is a fault")
    void remoteRefusals() {
        assertThatThrownBy(() -> storage.store(COMPANY, "text/plain", null,
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1024))
                .isInstanceOf(EvidenceRejectedException.class);

        SupabaseEvidenceStorage wrongKey = new SupabaseEvidenceStorage(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "not-the-key", "tms-evidence",
                Duration.ofSeconds(5), CLOCK, HttpClient.newHttpClient());
        assertThatThrownBy(() -> wrongKey.store(COMPANY, "image/png", null,
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1024))
                .isInstanceOf(EvidenceStorageException.class).hasMessageContaining("status 403")
                .hasMessageNotContaining("not-the-key");
    }

    @Test
    @DisplayName("an unreachable store is a fault, not a hang")
    void unreachable() throws IOException {
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        SupabaseEvidenceStorage nowhere = new SupabaseEvidenceStorage(URI.create("http://127.0.0.1:" + port),
                KEY, "tms-evidence", Duration.ofSeconds(2), CLOCK, HttpClient.newHttpClient());
        assertThatThrownBy(() -> nowhere.store(COMPANY, "image/png", null,
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1024))
                .isInstanceOf(EvidenceStorageException.class).hasMessageContaining("Could not reach");
    }

    @Test
    @DisplayName("the service key is never part of the settings' printable form")
    void keyIsRedacted() {
        var settings = new EvidenceStorageProperties.Supabase("https://x.supabase.co", "secret-value", null, null);
        assertThat(settings.toString()).doesNotContain("secret-value").contains("<redacted>");
        assertThat(settings.bucket()).isEqualTo("tms-evidence");
        assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(30));
    }
}
