package com.ebim.tms.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.settings.TrackingPlateDisplay;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-029 T5: how much of a plate, a name and an arrival time an anonymous link publishes. */
class PublicDisclosureTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    @Test
    @DisplayName("MASKED keeps the last three letters or digits and the separators")
    void masksThePlate() {
        assertThat(PublicDisclosure.plate("ABC-123", TrackingPlateDisplay.MASKED)).isEqualTo("•••-123");
        assertThat(PublicDisclosure.plate("PBA-1234", TrackingPlateDisplay.MASKED)).isEqualTo("•••-•234");
        assertThat(PublicDisclosure.plate(" F2K 448 ", TrackingPlateDisplay.MASKED)).isEqualTo("••• 448");
        assertThat(PublicDisclosure.plate("A1", TrackingPlateDisplay.MASKED)).isEqualTo("••");
        assertThat(PublicDisclosure.plate("ABC", TrackingPlateDisplay.MASKED)).isEqualTo("•••");
    }

    @Test
    @DisplayName("HIDDEN, an empty plate and no plate publish nothing")
    void hidesThePlate() {
        assertThat(PublicDisclosure.plate("ABC-123", TrackingPlateDisplay.HIDDEN)).isNull();
        assertThat(PublicDisclosure.plate("ABC-123", null)).isNull();
        assertThat(PublicDisclosure.plate("  ", TrackingPlateDisplay.MASKED)).isNull();
        assertThat(PublicDisclosure.plate(null, TrackingPlateDisplay.MASKED)).isNull();
    }

    @Test
    @DisplayName("the receiver is a first name and an initial, never the full name")
    void abbreviatesTheReceiver() {
        assertThat(PublicDisclosure.receiver("María López Quispe")).isEqualTo("María L.");
        assertThat(PublicDisclosure.receiver("  juan   pérez ")).isEqualTo("juan P.");
        assertThat(PublicDisclosure.receiver("Recepción")).isEqualTo("Recepción");
        assertThat(PublicDisclosure.receiver("Ana Ñahui")).isEqualTo("Ana Ñ.");
        assertThat(PublicDisclosure.receiver(" ")).isNull();
        assertThat(PublicDisclosure.receiver(null)).isNull();
    }

    @Test
    @DisplayName("an estimate becomes a one-hour window starting half an hour earlier, on the quarter")
    void windowAroundTheEstimate() {
        OffsetDateTime eta = OffsetDateTime.of(2026, 10, 22, 10, 52, 0, 0, ZoneOffset.ofHours(-5));
        PublicDisclosure.ArrivalWindow window = PublicDisclosure.arrivalWindow(eta, null, null, null, LIMA);

        assertThat(window.from()).isEqualTo(OffsetDateTime.of(2026, 10, 22, 10, 15, 0, 0, ZoneOffset.ofHours(-5)));
        assertThat(window.to()).isEqualTo(OffsetDateTime.of(2026, 10, 22, 11, 15, 0, 0, ZoneOffset.ofHours(-5)));
        assertThat(window.basis()).isEqualTo("ESTIMATE");
        assertThat(window.from()).isBefore(eta);
        assertThat(window.to()).isAfter(eta);
    }

    @Test
    @DisplayName("without an estimate the stop's service window on its day, in the company zone; without either, nothing")
    void serviceWindowOrNothing() {
        PublicDisclosure.ArrivalWindow window = PublicDisclosure.arrivalWindow(null, LocalDate.of(2026, 10, 22),
                LocalTime.of(10, 0), LocalTime.of(12, 0), LIMA);

        assertThat(window.from()).isEqualTo(OffsetDateTime.of(2026, 10, 22, 10, 0, 0, 0, ZoneOffset.ofHours(-5)));
        assertThat(window.to()).isEqualTo(OffsetDateTime.of(2026, 10, 22, 12, 0, 0, 0, ZoneOffset.ofHours(-5)));
        assertThat(window.basis()).isEqualTo("SERVICE_WINDOW");

        assertThat(PublicDisclosure.arrivalWindow(null, LocalDate.of(2026, 10, 22), null, null, LIMA)).isNull();
        assertThat(PublicDisclosure.arrivalWindow(null, null, LocalTime.of(10, 0), LocalTime.of(12, 0), LIMA)).isNull();
        assertThat(PublicDisclosure.arrivalWindow(null, LocalDate.of(2026, 10, 22), LocalTime.of(12, 0),
                LocalTime.of(10, 0), LIMA)).isNull();
    }
}
