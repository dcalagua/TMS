package com.ebim.tms.shared.storage;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

/**
 * Typed settings for proof-of-delivery evidence storage, under {@code tms.storage.evidence}.
 *
 * <p>The default is {@link Mode#DISABLED} and that default is the decision: a deployment that has
 * not said where a customer's signed delivery note goes must not have one guessed for it. Turning
 * the feature on is one property plus a path, and both are deployment concerns rather than
 * anything the application can infer.
 *
 * @param mode which store backs the feature. {@link Mode#LOCAL} writes to a private directory on
 *     the application's own volume - appropriate for a single-node install or a container with a
 *     mounted volume, and explicitly not a distributed answer. {@link Mode#SUPABASE} writes to a
 *     private Supabase Storage bucket (ADR-020) with the same keys, so no caller changes
 * @param root the directory {@link Mode#LOCAL} writes under. Must be absolute, must not be inside
 *     anything a web server serves, and is required when the mode is {@code LOCAL}
 * @param maxFileSize the largest artefact accepted. 5 MB by default: enough for a phone photo of a
 *     pallet, far short of a video, and under the hard 25 MB ceiling
 *     {@code ck_delivery_evidence_size} puts in the schema
 * @param allowedContentTypes the media types accepted, matched exactly and case-insensitively. An
 *     allow-list rather than a block-list, and deliberately short: images and PDFs are what a proof
 *     of delivery is, and every other type is something somebody is using the store for instead
 * @param supabase the coordinates {@link Mode#SUPABASE} needs (ADR-020); ignored by the other modes
 */
@ConfigurationProperties(prefix = "tms.storage.evidence")
public record EvidenceStorageProperties(
        Mode mode, String root, DataSize maxFileSize, List<String> allowedContentTypes, Supabase supabase) {

    /** Which store backs the feature. */
    public enum Mode {
        /** No store. Uploads and downloads answer 503; recording a delivery result still works. */
        DISABLED,
        /** A private directory on the application's own volume. */
        LOCAL,
        /** A private Supabase Storage bucket, reached by the backend only (ADR-020). */
        SUPABASE
    }

    /**
     * A private Supabase Storage bucket (ADR-020).
     *
     * @param url the project's URL, {@code https://<ref>.supabase.co}
     * @param serviceKey the project's {@code service_role} key. A secret that bypasses row level
     *     security: it lives in the deployment's secret settings and nowhere else, and is never
     *     printed - see {@link #toString()}
     * @param bucket the private bucket, {@code tms-evidence} by default
     * @param timeout per request, 30 seconds by default
     */
    public record Supabase(String url, String serviceKey, String bucket, Duration timeout) {

        public static final String DEFAULT_BUCKET = "tms-evidence";
        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

        public Supabase {
            bucket = bucket == null || bucket.isBlank() ? DEFAULT_BUCKET : bucket.trim();
            timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
        }

        @Override
        public String toString() {
            return "Supabase[url=" + url + ", bucket=" + bucket + ", timeout=" + timeout + ", serviceKey="
                    + (serviceKey == null || serviceKey.isBlank() ? "<unset>" : "<redacted>") + "]";
        }
    }

    /** The four-setting form, for the stores that need no remote coordinates. */
    public EvidenceStorageProperties(Mode mode, String root, DataSize maxFileSize, List<String> allowedContentTypes) {
        this(mode, root, maxFileSize, allowedContentTypes, null);
    }

    public static final DataSize DEFAULT_MAX_FILE_SIZE = DataSize.ofMegabytes(5);

    /** Matches {@code ck_delivery_evidence_size} - the schema will refuse anything larger anyway. */
    public static final DataSize ABSOLUTE_MAX_FILE_SIZE = DataSize.ofMegabytes(25);

    private static final List<String> DEFAULT_CONTENT_TYPES =
            List.of("image/jpeg", "image/png", "image/webp", "application/pdf");

    /** The one Spring binds: the record has a second, shorter constructor for the remote-less stores. */
    @ConstructorBinding
    public EvidenceStorageProperties {
        mode = mode == null ? Mode.DISABLED : mode;
        supabase = supabase == null ? new Supabase(null, null, null, null) : supabase;
        maxFileSize = clamp(maxFileSize);
        allowedContentTypes = normalize(allowedContentTypes);
    }

    private static DataSize clamp(DataSize requested) {
        if (requested == null || requested.toBytes() <= 0) {
            return DEFAULT_MAX_FILE_SIZE;
        }
        return requested.toBytes() > ABSOLUTE_MAX_FILE_SIZE.toBytes() ? ABSOLUTE_MAX_FILE_SIZE : requested;
    }

    /**
     * Lower-cased, de-duplicated and order-preserving, falling back to the defaults for an empty
     * list. An empty allow-list is never read as "allow everything": that reading turns a
     * configuration slip into an open upload endpoint.
     */
    private static List<String> normalize(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return DEFAULT_CONTENT_TYPES;
        }
        Set<String> unique = new LinkedHashSet<>();
        requested.stream()
                .filter(type -> type != null && !type.isBlank())
                .map(type -> type.trim().toLowerCase(Locale.ROOT))
                .forEach(unique::add);
        return unique.isEmpty() ? DEFAULT_CONTENT_TYPES : List.copyOf(unique);
    }

    public boolean allows(String contentType) {
        return contentType != null && allowedContentTypes.contains(contentType.trim().toLowerCase(Locale.ROOT));
    }

    public long maxBytes() {
        return maxFileSize.toBytes();
    }
}
