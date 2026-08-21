package com.ebim.tms.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * The defaults of evidence storage, which are all security decisions: off unless configured, a
 * bounded file size, and an allow-list that never collapses into "anything".
 */
class EvidenceStoragePropertiesTest {

    @Test
    @DisplayName("is off, bounded and image-or-PDF only when nothing is configured")
    void defaultsToDisabled() {
        EvidenceStorageProperties properties = new EvidenceStorageProperties(null, null, null, null);

        assertThat(properties.mode()).isEqualTo(EvidenceStorageProperties.Mode.DISABLED);
        assertThat(properties.maxBytes()).isEqualTo(EvidenceStorageProperties.DEFAULT_MAX_FILE_SIZE.toBytes());
        assertThat(properties.allowedContentTypes())
                .containsExactly("image/jpeg", "image/png", "image/webp", "application/pdf");
    }

    @Test
    @DisplayName("an empty allow-list is never read as 'allow everything'")
    void emptyAllowListFallsBackToTheDefaults() {
        EvidenceStorageProperties properties =
                new EvidenceStorageProperties(null, null, null, List.of("   ", ""));

        assertThat(properties.allows("application/x-msdownload")).isFalse();
        assertThat(properties.allows("image/png")).isTrue();
    }

    @Test
    @DisplayName("matches media types case-insensitively, because a client's header is its own")
    void matchesCaseInsensitively() {
        EvidenceStorageProperties properties =
                new EvidenceStorageProperties(null, null, null, List.of("IMAGE/JPEG"));

        assertThat(properties.allows("image/jpeg")).isTrue();
        assertThat(properties.allows(" Image/Jpeg ")).isTrue();
        assertThat(properties.allows(null)).isFalse();
    }

    @Test
    @DisplayName("clamps a configured size to the ceiling the schema itself enforces")
    void clampsTheConfiguredSize() {
        assertThat(new EvidenceStorageProperties(null, null, DataSize.ofMegabytes(500), null).maxBytes())
                .isEqualTo(EvidenceStorageProperties.ABSOLUTE_MAX_FILE_SIZE.toBytes());
        assertThat(new EvidenceStorageProperties(null, null, DataSize.ofBytes(0), null).maxBytes())
                .isEqualTo(EvidenceStorageProperties.DEFAULT_MAX_FILE_SIZE.toBytes());
        assertThat(new EvidenceStorageProperties(null, null, DataSize.ofMegabytes(2), null).maxBytes())
                .isEqualTo(DataSize.ofMegabytes(2).toBytes());
    }

    @Test
    @DisplayName("a disabled store refuses both directions, and says why")
    void disabledStoreRefusesBothDirections() {
        DisabledEvidenceStorage storage = new DisabledEvidenceStorage();

        assertThat(storage.isEnabled()).isFalse();
        assertThatExceptionOfType(EvidenceStorageUnavailableException.class)
                .isThrownBy(() -> storage.open(UUID.randomUUID(), "any/key"))
                .withMessageContaining("not configured");
    }
}
