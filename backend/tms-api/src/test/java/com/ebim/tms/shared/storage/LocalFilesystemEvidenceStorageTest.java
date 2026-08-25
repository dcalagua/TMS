package com.ebim.tms.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The store that backs proof-of-delivery evidence when a deployment has a volume for it.
 *
 * <p>Three things are worth a test here and they are all about <em>keys</em>: that the store makes
 * its own, that it will not read one belonging to another tenant, and that nothing shaped like a
 * path traversal is readable. The fourth is the size ceiling, which has to hold while the bytes are
 * arriving rather than after they have all been written.
 */
class LocalFilesystemEvidenceStorageTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID OTHER_COMPANY = UUID.randomUUID();

    /** Fixed, so the year/month segments of a key are a fact the test can assert. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-09T10:15:30Z"), ZoneOffset.UTC);

    @TempDir
    Path root;

    private LocalFilesystemEvidenceStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFilesystemEvidenceStorage(root, CLOCK);
    }

    private static InputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Test
    @DisplayName("stores the bytes under a key it generated itself, and reports what it wrote")
    void storesAndReportsWhatItWrote() {
        StoredObject stored = storage.store(COMPANY, "image/jpeg", "signature.jpg", bytes("proof"), 1024);

        assertThat(stored.storageKey())
                .startsWith(COMPANY + "/2026/03/")
                .endsWith(".jpg");
        assertThat(stored.sizeBytes()).isEqualTo(5);
        assertThat(stored.checksumSha256()).isEqualTo(sha256("proof"));
    }

    @Test
    @DisplayName("takes the extension from the media type, never from the file name it was given")
    void extensionComesFromTheMediaType() {
        StoredObject stored = storage.store(COMPANY, "application/pdf", "../../etc/passwd", bytes("proof"), 1024);

        assertThat(stored.storageKey()).endsWith(".pdf").doesNotContain("..").doesNotContain("passwd");
    }

    @Test
    @DisplayName("reads back exactly what was stored")
    void readsBackWhatWasStored() throws IOException {
        StoredObject stored = storage.store(COMPANY, "image/png", null, bytes("delivered"), 1024);

        StoredObjectContent content = storage.open(COMPANY, stored.storageKey());
        try (InputStream stream = content.stream()) {
            assertThat(content.sizeBytes()).isEqualTo(9);
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("delivered");
        }
    }

    @Test
    @DisplayName("refuses to read a key belonging to another company")
    void refusesAnotherCompanysKey() {
        StoredObject stored = storage.store(COMPANY, "image/png", null, bytes("proof"), 1024);

        assertThatExceptionOfType(EvidenceStorageException.class)
                .isThrownBy(() -> storage.open(OTHER_COMPANY, stored.storageKey()))
                .withMessageContaining("does not belong");
    }

    @Test
    @DisplayName("refuses anything that is not shaped exactly like a key it would have written")
    void refusesAMalformedKey() {
        for (String key : new String[] {
                "../../../etc/passwd",
                COMPANY + "/../" + OTHER_COMPANY + "/2026/03/x.jpg",
                COMPANY + "/2026/03/not-a-uuid.jpg",
                ""}) {
            assertThatExceptionOfType(EvidenceStorageException.class)
                    .isThrownBy(() -> storage.open(COMPANY, key));
        }
    }

    @Test
    @DisplayName("stops writing at the ceiling and leaves nothing behind")
    void refusesAnOversizedUploadWithoutLeavingAPartialFile() {
        assertThatExceptionOfType(EvidenceRejectedException.class)
                .isThrownBy(() -> storage.store(COMPANY, "image/jpeg", null, bytes("far too many bytes"), 4))
                .withMessageContaining("larger");

        assertThat(filesUnder(root)).isEmpty();
    }

    @Test
    @DisplayName("refuses an upload with no bytes in it")
    void refusesAnEmptyUpload() {
        assertThatExceptionOfType(EvidenceRejectedException.class)
                .isThrownBy(() -> storage.store(COMPANY, "image/jpeg", null, bytes(""), 1024))
                .withMessageContaining("empty");
    }

    @Test
    @DisplayName("reports a missing object as a fault, not as 'never existed'")
    void reportsAMissingObject() throws IOException {
        StoredObject stored = storage.store(COMPANY, "image/png", null, bytes("proof"), 1024);
        Files.delete(root.resolve(stored.storageKey()));

        assertThatExceptionOfType(EvidenceStorageException.class)
                .isThrownBy(() -> storage.open(COMPANY, stored.storageKey()))
                .withMessageContaining("missing");
    }

    @Test
    @DisplayName("insists on an absolute root, so it cannot depend on the working directory")
    void insistsOnAnAbsoluteRoot() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new LocalFilesystemEvidenceStorage(Path.of("evidence"), CLOCK))
                .withMessageContaining("absolute");
    }

    private static List<Path> filesUnder(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).toList();
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
