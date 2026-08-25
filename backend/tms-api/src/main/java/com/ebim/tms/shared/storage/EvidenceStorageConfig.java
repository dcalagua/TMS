package com.ebim.tms.shared.storage;

import java.nio.file.Path;
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
 * satisfied - {@code LOCAL} without a root - fails at startup naming the property, which is the
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
            case DISABLED -> {
                log.info("Delivery evidence storage: DISABLED. Delivery results are still recorded; "
                        + "uploads and downloads will answer 503 until tms.storage.evidence.mode is set.");
                yield new DisabledEvidenceStorage();
            }
        };
    }
}
