package com.ebim.tms.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * ADR-020 against a real bucket, run by hand when a deployment's coordinates change:
 *
 * <pre>
 *   TMS_EVIDENCE_SUPABASE_LIVE_URL=https://&lt;ref&gt;.supabase.co \
 *   TMS_EVIDENCE_SUPABASE_LIVE_KEY=&lt;service_role key&gt; \
 *   ./mvnw test -Dtest=SupabaseEvidenceStorageLiveTest
 * </pre>
 *
 * Skipped everywhere else. It writes one object under a company id that cannot exist and deletes it.
 */
@EnabledIfEnvironmentVariable(named = "TMS_EVIDENCE_SUPABASE_LIVE_KEY", matches = ".+")
class SupabaseEvidenceStorageLiveTest {

    private static final UUID NO_COMPANY = UUID.fromString("00000000-0000-4000-8000-00000000c0de");

    @Test
    @DisplayName("store, read back and refuse anonymous access on the real bucket")
    void liveRoundTrip() throws Exception {
        String url = System.getenv("TMS_EVIDENCE_SUPABASE_LIVE_URL");
        String key = System.getenv("TMS_EVIDENCE_SUPABASE_LIVE_KEY");
        String bucket = System.getenv().getOrDefault("TMS_EVIDENCE_SUPABASE_LIVE_BUCKET", "tms-evidence");
        HttpClient http = HttpClient.newHttpClient();
        SupabaseEvidenceStorage storage = new SupabaseEvidenceStorage(URI.create(url), key, bucket,
                Duration.ofSeconds(30), Clock.systemUTC(), http);
        byte[] photo = ("live check " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        StoredObject stored = storage.store(NO_COMPANY, "image/png", "check.png", new ByteArrayInputStream(photo), 1024);
        try {
            assertThat(storage.open(NO_COMPANY, stored.storageKey()).stream().readAllBytes()).isEqualTo(photo);
            HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(
                    URI.create(url + "/storage/v1/object/public/" + bucket + "/" + stored.storageKey())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(anonymous.statusCode()).isNotEqualTo(200);
        } finally {
            http.send(HttpRequest.newBuilder(URI.create(url + "/storage/v1/object/" + bucket))
                    .header("apikey", key).header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(
                            "{\"prefixes\":[\"" + stored.storageKey() + "\"]}"))
                    .build(), HttpResponse.BodyHandlers.discarding());
        }
    }
}
