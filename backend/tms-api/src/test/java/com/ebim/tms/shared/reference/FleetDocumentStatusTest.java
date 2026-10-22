package com.ebim.tms.shared.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FleetDocumentStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @ParameterizedTest(name = "expires {0} with a {1}-day window -> {2}")
    @CsvSource({
            "2026-10-21, 30, EXPIRED",
            // Inclusive: a paper that expires today is still valid today.
            "2026-10-22, 30, EXPIRING_SOON",
            "2026-11-21, 30, EXPIRING_SOON",
            "2026-11-22, 30, VALID",
            "2026-11-06, 15, EXPIRING_SOON",
            "2026-11-07, 15, VALID"
    })
    void boundaries(LocalDate expiresOn, int alertDays, FleetDocumentStatus expected) {
        assertThat(FleetDocumentStatus.of(expiresOn, TODAY, alertDays)).isEqualTo(expected);
    }

    @Test
    @DisplayName("no date is unrecorded, never expired")
    void noDate() {
        assertThat(FleetDocumentStatus.of(null, TODAY, 30)).isEqualTo(FleetDocumentStatus.UNRECORDED);
    }

    @Test
    @DisplayName("every document type belongs to exactly one subject, and nothing is mandatory by default")
    void catalogue() {
        for (FleetDocumentType type : FleetDocumentType.values()) {
            assertThat(type.subject()).isNotNull();
            assertThat(type.defaultMandatory()).isFalse();
        }
        assertThat(FleetDocumentType.TECHNICAL_INSPECTION.defaultEnforcement()).isEqualTo(ComplianceEnforcement.BLOCK);
        assertThat(FleetDocumentType.MEDICAL_CERTIFICATE.subject()).isEqualTo(FleetDocumentSubject.DRIVER);
    }
}
