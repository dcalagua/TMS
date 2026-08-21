package com.ebim.tms.shared.storage;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
 *     mounted volume, and explicitly not a distributed answer. Supabase Storage is the next
 *     implementation and needs no change to any caller, because a storage key is opaque
 * @param root the directory {@link Mode#LOCAL} writes under. Must be absolute, must not be inside
 *     anything a web server serves, and is required when the mode is {@code LOCAL}
 * @param maxFileSize the largest artefact accepted. 5 MB by default: enough for a phone photo of a
 *     pallet, far short of a video, and under the hard 25 MB ceiling
 *     {@code ck_delivery_evidence_size} puts in the schema
 * @param allowedContentTypes the media types accepted, matched exactly and case-insensitively. An
 *     allow-list rather than a block-list, and deliberately short: images and PDFs are what a proof
 *     of delivery is, and every other type is something somebody is using the store for instead
 */
@ConfigurationProperties(prefix = "tms.storage.evidence")
public record EvidenceStorageProperties(
        Mode mode, String root, DataSize maxFileSize, List<String> allowedContentTypes) {

    /** Which store backs the feature. */
    public enum Mode {
        /** No store. Uploads and downloads answer 503; recording a delivery result still works. */
        DISABLED,
        /** A private directory on the application's own volume. */
        LOCAL
    }

    public static final DataSize DEFAULT_MAX_FILE_SIZE = DataSize.ofMegabytes(5);

    /** Matches {@code ck_delivery_evidence_size} - the schema will refuse anything larger anyway. */
    public static final DataSize ABSOLUTE_MAX_FILE_SIZE = DataSize.ofMegabytes(25);

    private static final List<String> DEFAULT_CONTENT_TYPES =
            List.of("image/jpeg", "image/png", "image/webp", "application/pdf");

    public EvidenceStorageProperties {
        mode = mode == null ? Mode.DISABLED : mode;
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
