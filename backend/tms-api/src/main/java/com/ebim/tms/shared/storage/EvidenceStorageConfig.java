package com.ebim.tms.shared.storage;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the {@link EvidenceStoragePort} implementation from {@code tms.storage.evidence.mode}.
 *
 * <p>One bean and an explicit switch rather than {@code @ConditionalOnProperty} on each
 * implementation: with a conditional there are configurations that produce <em>no</em> bean and a
 * startup failure whose message is about a missing dependency rather than about the setting that
 * caused it. Here every configuration produces exactly one store, and a mode that cannot be
 * satisfied - {@code LOCAL} without a root, {@code SUPABASE} without its URL or key - fails at startup naming the property, which is the
 * same rule {@code SupabaseJwtDecoders} follows for its own missing coordinates.
 *
 * <p>The chosen store is logged at INFO on startup. Whether a deployment can accept proof of
 * delivery is exactly the kind of thing an operator should be able to read off the boot log rather
 * than discover from a 503.
 */
@Configuration
@EnableConfigurationProperties(EvidenceStorageProperties.class)
public class EvidenceStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(EvidenceStorageConfig.class);

    @Bean
    public EvidenceStoragePort evidenceStoragePort(EvidenceStorageProperties properties, Clock clock) {
        return switch (properties.mode()) {
            case LOCAL -> {
                if (properties.root() == null || properties.root().isBlank()) {
                    throw new IllegalStateException(
                            "tms.storage.evidence.mode is LOCAL but tms.storage.evidence.root is not set. "
                                    + "Point it at a private directory that no web server serves.");
                }
                log.info("Delivery evidence storage: LOCAL, max {} bytes, types {}",
                        properties.maxBytes(), properties.allowedContentTypes());
                yield new LocalFilesystemEvidenceStorage(Path.of(properties.root()), clock);
            }
            case SUPABASE -> {
                EvidenceStorageProperties.Supabase supabase = properties.supabase();
                if (supabase.url() == null || supabase.url().isBlank()
                        || supabase.serviceKey() == null || supabase.serviceKey().isBlank()) {
                    throw new IllegalStateException(
                            "tms.storage.evidence.mode is SUPABASE but tms.storage.evidence.supabase.url or "
                                    + "tms.storage.evidence.supabase.service-key is not set "
                                    + "(TMS_EVIDENCE_SUPABASE_URL, TMS_EVIDENCE_SUPABASE_SERVICE_KEY).");
                }
                URI url = URI.create(supabase.url().trim());
                if (!"https".equals(url.getScheme()) && !"http".equals(url.getScheme())) {
                    throw new IllegalStateException("tms.storage.evidence.supabase.url must be an http(s) URL.");
                }
                // The key is never logged; the host and bucket are what an operator needs to read.
                log.info("Delivery evidence storage: SUPABASE bucket '{}' at {}, max {} bytes, types {}",
                        supabase.bucket(), url.getHost(), properties.maxBytes(), properties.allowedContentTypes());
                yield new SupabaseEvidenceStorage(url, supabase.serviceKey().trim(), supabase.bucket(),
                        supabase.timeout(), clock, HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(10))
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build());
            }
            case DISABLED -> {
                log.info("Delivery evidence storage: DISABLED. Delivery results are still recorded; "
                        + "uploads and downloads will answer 503 until tms.storage.evidence.mode is set.");
                yield new DisabledEvidenceStorage();
            }
        };
    }
}
