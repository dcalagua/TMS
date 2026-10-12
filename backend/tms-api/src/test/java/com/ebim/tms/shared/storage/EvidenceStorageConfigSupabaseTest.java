package com.ebim.tms.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-020: SUPABASE without its coordinates stops the start-up, naming them; with them, it is the store. */
class EvidenceStorageConfigSupabaseTest {

    private final EvidenceStorageConfig config = new EvidenceStorageConfig();

    @Test
    @DisplayName("SUPABASE without URL or key fails at start-up naming the variables, never echoing a key")
    void missingCoordinates() {
        var noKey = new EvidenceStorageProperties(EvidenceStorageProperties.Mode.SUPABASE, null, null, null,
                new EvidenceStorageProperties.Supabase("https://ref.supabase.co", " ", null, null));
        assertThatThrownBy(() -> config.evidenceStoragePort(noKey, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TMS_EVIDENCE_SUPABASE_SERVICE_KEY");

        var noUrl = new EvidenceStorageProperties(EvidenceStorageProperties.Mode.SUPABASE, null, null, null,
                new EvidenceStorageProperties.Supabase(null, "secret-value", null, null));
        assertThatThrownBy(() -> config.evidenceStoragePort(noUrl, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TMS_EVIDENCE_SUPABASE_URL")
                .hasMessageNotContaining("secret-value");
    }

    @Test
    @DisplayName("SUPABASE with URL and key is the Supabase store, enabled")
    void configured() {
        var settings = new EvidenceStorageProperties(EvidenceStorageProperties.Mode.SUPABASE, null, null, null,
                new EvidenceStorageProperties.Supabase("https://ref.supabase.co/", "secret-value", null, null));
        EvidenceStoragePort port = config.evidenceStoragePort(settings, Clock.systemUTC());
        assertThat(port).isInstanceOf(SupabaseEvidenceStorage.class);
        assertThat(port.isEnabled()).isTrue();
    }
}
