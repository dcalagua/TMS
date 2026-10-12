package com.ebim.tms.shared.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link EvidenceStoragePort} over a private Supabase Storage bucket (ADR-020).
 *
 * <p><b>Only the backend talks to the bucket.</b> It authenticates with the project's service key,
 * which bypasses Storage's row level security; the bucket is private and {@code storage.objects}
 * carries no policy, so no browser key - anon or a user's JWT - can read or list anything in it.
 * Bytes still reach a person only through the API, after the company, carrier and permission checks
 * of the caller. There is no signed or public URL, as ADR-006 requires.
 *
 * <p><b>Same keys as the volume.</b> {@link EvidenceKeys} generates them, so moving evidence between
 * stores is a copy and no database row changes. A key of another company is refused before any
 * request leaves the process.
 *
 * <p><b>Size, checksum and type are computed here</b>, from the bytes read, before the upload: the
 * file is held in memory up to the configured ceiling (5 MB by default, 25 MB at most), which is
 * what lets an oversized upload be refused without sending it, and the checksum be the one of the
 * bytes actually stored. The bucket's own size and type limits are a second line.
 *
 * <p><b>Errors.</b> Storage answers failures with an HTTP error and a JSON body whose
 * {@code statusCode} carries the real meaning (409 duplicate, 404 missing, 415 type). A missing
 * object is a fault, exactly as {@link LocalFilesystemEvidenceStorage} reports it: the row exists, so
 * the evidence existed, and "not found" would read as "it never did".
 */
public class SupabaseEvidenceStorage implements EvidenceStoragePort {

    private static final Pattern STATUS_CODE = Pattern.compile("\"statusCode\"\\s*:\\s*\"?(\\d{3})");
    private static final int READ_BUFFER_BYTES = 8192;

    private final URI storageBase;
    private final String serviceKey;
    private final String bucket;
    private final Duration timeout;
    private final Clock clock;
    private final HttpClient http;

    /**
     * @param projectUrl the project's URL, e.g. {@code https://<ref>.supabase.co}
     * @param serviceKey the project's {@code service_role} key (a JWT): Storage refuses keys that
     *     are not JWTs
     */
    public SupabaseEvidenceStorage(URI projectUrl, String serviceKey, String bucket, Duration timeout, Clock clock,
            HttpClient http) {
        String base = projectUrl.toString().replaceAll("/+$", "");
        this.storageBase = URI.create(base + "/storage/v1/");
        this.serviceKey = serviceKey;
        this.bucket = bucket;
        this.timeout = timeout;
        this.clock = clock;
        this.http = http;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public StoredObject store(UUID companyId, String contentType, String originalFilename, InputStream content,
            long maxBytes) {
        byte[] bytes = readBounded(content, maxBytes);
        String checksum = HexFormat.of().formatHex(sha256().digest(bytes));
        String key = EvidenceKeys.newKey(companyId, contentType, clock);

        HttpRequest request = authorized(HttpRequest.newBuilder(objectUri("object/", key)))
                .header("Content-Type", contentType)
                // Never overwrite: the key is new, so an existing object would be a collision to report.
                .header("x-upsert", "false")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<byte[]> response = send(request, "store evidence for company " + companyId);
        if (!isSuccess(response.statusCode())) {
            int status = storageStatus(response);
            if (status == 415) {
                throw new EvidenceRejectedException("The evidence store does not accept files of type " + contentType + ".");
            }
            if (status == 413) {
                throw new EvidenceRejectedException("The file is larger than the evidence store accepts.");
            }
            throw new EvidenceStorageException("Supabase Storage refused to store evidence for company " + companyId
                    + " (status " + status + ")");
        }
        return new StoredObject(key, bytes.length, checksum);
    }

    @Override
    public StoredObjectContent open(UUID companyId, String storageKey) {
        EvidenceKeys.requireOwned(companyId, storageKey);
        HttpRequest request = authorized(HttpRequest.newBuilder(objectUri("object/authenticated/", storageKey)))
                .GET()
                .build();
        HttpResponse<byte[]> response = send(request, "read evidence object " + storageKey);
        if (!isSuccess(response.statusCode())) {
            int status = storageStatus(response);
            if (status == 404) {
                throw new EvidenceStorageException("Evidence object " + storageKey + " is missing from the store");
            }
            throw new EvidenceStorageException("Supabase Storage refused to read evidence object " + storageKey
                    + " (status " + status + ")");
        }
        byte[] body = response.body();
        return new StoredObjectContent(body.length, new ByteArrayInputStream(body));
    }

    private URI objectUri(String route, String key) {
        // Keys are [0-9a-f-/.] only (EvidenceKeys), so nothing in them needs escaping.
        return storageBase.resolve(route + bucket + "/" + key);
    }

    /** Both headers: the gateway reads {@code apikey}, Storage reads the bearer JWT. */
    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        return builder.timeout(timeout)
                .header("apikey", serviceKey)
                .header("Authorization", "Bearer " + serviceKey);
    }

    private HttpResponse<byte[]> send(HttpRequest request, String what) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException failed) {
            throw new EvidenceStorageException("Could not reach Supabase Storage to " + what, failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EvidenceStorageException("Interrupted while trying to " + what, interrupted);
        }
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    /** The status Storage means, which its JSON body carries; the HTTP status when the body says nothing. */
    static int storageStatus(HttpResponse<byte[]> response) {
        byte[] body = response.body();
        if (body != null && body.length > 0) {
            Matcher matcher = STATUS_CODE.matcher(new String(body, StandardCharsets.UTF_8));
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return response.statusCode();
    }

    private static byte[] readBounded(InputStream source, long maxBytes) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[READ_BUFFER_BYTES];
        long total = 0;
        try (InputStream in = source) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new EvidenceRejectedException(
                            "The file is larger than the " + maxBytes + " byte limit for delivery evidence.");
                }
                buffer.write(chunk, 0, read);
            }
        } catch (IOException unreadable) {
            throw new EvidenceStorageException("Could not read the uploaded evidence", unreadable);
        }
        if (total == 0) {
            throw new EvidenceRejectedException("The file is empty.");
        }
        return buffer.toByteArray();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new EvidenceStorageException("SHA-256 is unavailable in this JVM", impossible);
        }
    }
}
