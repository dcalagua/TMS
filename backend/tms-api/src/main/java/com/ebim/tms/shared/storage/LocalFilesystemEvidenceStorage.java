package com.ebim.tms.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An {@link EvidenceStoragePort} that writes to a private directory on the application's own
 * volume.
 *
 * <p>Appropriate for a single-node install or a container with a mounted volume, and explicitly not
 * a distributed answer: two application instances behind a load balancer need shared storage, which
 * is what the Supabase Storage implementation will be for. It exists because "we have no file
 * infrastructure" must not mean "you cannot record a proof of delivery at all" for the deployments
 * that do have a volume.
 *
 * <p><b>The layout.</b> {@code <root>/<companyId>/<yyyy>/<MM>/<uuid>.<ext>}, and every part of it
 * is generated here:
 *
 * <ul>
 *   <li>the company comes first, so one tenant's objects are one subtree - a directory that can be
 *       backed up, moved or, the day a retention policy exists, swept, per customer;</li>
 *   <li>the year and month keep a busy fleet from putting a million entries in one directory;</li>
 *   <li>the file name is a fresh UUID, never the uploader's. The extension comes from the
 *       <em>media type</em> the caller already validated against its allow-list, not from the file
 *       name, which is why {@code ../../etc/passwd} is not a case that has to be handled: no
 *       character of a caller-supplied string reaches a path.</li>
 * </ul>
 *
 * <p>{@link #open} re-checks anyway. The key is matched against the exact shape written above, and
 * the resolved path is required to still be under the root after normalisation - two lines of
 * defense against a key that reached the database through some other route.
 */
public class LocalFilesystemEvidenceStorage implements EvidenceStoragePort {

    /**
     * The one place a media type becomes a file extension. Kept beside the writer rather than
     * derived from the file name, because the file name is the caller's and the media type is
     * already on the allow-list.
     */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/heic", "heic",
            "application/pdf", "pdf");

    /** Anything the map above does not name. The bytes are typed in the database, not by this suffix. */
    private static final String DEFAULT_EXTENSION = "bin";

    /** Exactly the shape {@link #keyFor} produces, and nothing else, is readable. */
    private static final Pattern KEY_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/\\d{4}/\\d{2}/"
                    + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{1,8}$");

    private static final int COPY_BUFFER_BYTES = 8192;

    private final Path root;
    private final Clock clock;

    /**
     * @param root the directory to write under; must be absolute, so a relative path cannot make the
     *     store depend on the working directory the process happened to start in
     */
    public LocalFilesystemEvidenceStorage(Path root, Clock clock) {
        Path normalized = root.toAbsolutePath().normalize();
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException(
                    "tms.storage.evidence.root must be an absolute path; was '" + root + "'");
        }
        this.root = normalized;
        this.clock = clock;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public StoredObject store(UUID companyId, String contentType, String originalFilename, InputStream content,
            long maxBytes) {
        String key = keyFor(companyId, contentType);
        Path target = resolve(companyId, key);
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException failed) {
            throw new EvidenceStorageException("Could not create the evidence directory for company " + companyId,
                    failed);
        }

        // Written to a temporary file in the same directory and moved into place at the end, so a
        // failed or oversized upload never leaves a half-written object under a key the database
        // may already be about to point at.
        Path staging;
        try {
            staging = Files.createTempFile(target.getParent(), "upload-", ".part");
        } catch (IOException failed) {
            throw new EvidenceStorageException("Could not stage an evidence upload for company " + companyId, failed);
        }

        try {
            MessageDigest digest = sha256();
            long written = copy(content, staging, digest, maxBytes);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return new StoredObject(key, written, HexFormat.of().formatHex(digest.digest()));
        } catch (EvidenceRejectedException | EvidenceStorageException failed) {
            deleteQuietly(staging);
            throw failed;
        } catch (IOException failed) {
            deleteQuietly(staging);
            throw new EvidenceStorageException("Could not store evidence for company " + companyId, failed);
        }
    }

    @Override
    public StoredObjectContent open(UUID companyId, String storageKey) {
        Path target = resolve(companyId, storageKey);
        if (!Files.isRegularFile(target)) {
            // The row exists and the object does not: a restore that missed the volume, or a
            // half-finished migration between stores. A fault, and reported as one - the caller
            // must not be told "not found", which would read as "this evidence never existed".
            throw new EvidenceStorageException("Evidence object " + storageKey + " is missing from the store");
        }
        try {
            return new StoredObjectContent(Files.size(target), Files.newInputStream(target));
        } catch (IOException failed) {
            throw new EvidenceStorageException("Could not read evidence object " + storageKey, failed);
        }
    }

    /**
     * The key for a new object. Every component is generated: the company, today's year and month,
     * a fresh UUID, and an extension chosen from the media type.
     */
    private String keyFor(UUID companyId, String contentType) {
        LocalDate today = LocalDate.now(clock);
        String extension = EXTENSIONS.getOrDefault(
                contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT), DEFAULT_EXTENSION);
        return "%s/%04d/%02d/%s.%s".formatted(
                companyId, today.getYear(), today.getMonthValue(), UUID.randomUUID(), extension);
    }

    /**
     * A key turned into a path, with both checks that make that safe: the key must have exactly the
     * shape {@link #keyFor} produces and must name the company doing the asking, and the resolved
     * path must still be under the root once normalised.
     *
     * <p>The second check cannot fail given the first, and is here anyway: it is the one that keeps
     * holding if the key format ever changes.
     */
    private Path resolve(UUID companyId, String storageKey) {
        if (storageKey == null || !KEY_PATTERN.matcher(storageKey).matches()) {
            throw new EvidenceStorageException("Malformed evidence key");
        }
        if (!storageKey.startsWith(companyId.toString() + "/")) {
            // A key belonging to another tenant. The database scoping should have caught it; this
            // is the layer that makes a leaked key useless on its own.
            throw new EvidenceStorageException("Evidence key does not belong to this company");
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new EvidenceStorageException("Evidence key escapes the storage root");
        }
        return resolved;
    }

    /**
     * Copies at most {@code maxBytes} and hashes what it copies.
     *
     * <p>Counts what it reads rather than trusting a declared length: a chunked upload does not have
     * to declare one, and the only number that cannot be lied about is the count of bytes that
     * actually arrived. One byte over the ceiling is enough to stop - the stream is abandoned, and
     * the caller deletes the staging file.
     */
    private static long copy(InputStream source, Path staging, MessageDigest digest, long maxBytes)
            throws IOException {
        long written = 0;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream in = source;
                OutputStream out = new DigestOutputStream(Files.newOutputStream(staging), digest)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > maxBytes) {
                    throw new EvidenceRejectedException(
                            "The file is larger than the " + maxBytes + " byte limit for delivery evidence.");
                }
                out.write(buffer, 0, read);
            }
        }
        if (written == 0) {
            throw new EvidenceRejectedException("The file is empty.");
        }
        return written;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships SHA-256; this is unreachable and is not swallowed for that reason.
            throw new EvidenceStorageException("SHA-256 is unavailable in this JVM", impossible);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The upload already failed; a leftover .part file is a sweepable orphan, not a reason
            // to replace the real error with a different one.
        }
    }
}
