package com.ebim.tms.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-014 section 3: when the release window for a dispatch closes. */
class ReleaseDeadlineTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final LocalDate DISPATCH = LocalDate.of(2026, 9, 30);

    @Test
    @DisplayName("the ADR example: dispatch 30/09, lead time 1, cutoff 16:00 closes 29/09 16:00 Lima")
    void adrExample() {
        ReleaseDeadline deadline = ReleaseDeadline.of(DISPATCH, 1, LocalTime.of(16, 0), LIMA);

        assertThat(deadline.at()).isEqualTo(OffsetDateTime.of(2026, 9, 29, 16, 0, 0, 0, ZoneOffset.ofHours(-5)));
        assertThat(deadline.endOfDay()).isFalse();
        assertThat(deadline.isMissedAt(OffsetDateTime.of(2026, 9, 29, 15, 59, 0, 0, ZoneOffset.ofHours(-5)))).isFalse();
        assertThat(deadline.isMissedAt(OffsetDateTime.of(2026, 9, 29, 16, 0, 0, 0, ZoneOffset.ofHours(-5)))).isFalse();
        assertThat(deadline.isMissedAt(OffsetDateTime.of(2026, 9, 29, 16, 0, 1, 0, ZoneOffset.ofHours(-5)))).isTrue();
    }

    @Test
    @DisplayName("the company's zone decides: 16:00 in Lima is 21:00 UTC")
    void judgedInTheCompanyZone() {
        ReleaseDeadline deadline = ReleaseDeadline.of(DISPATCH, 0, LocalTime.of(16, 0), LIMA);

        assertThat(deadline.isMissedAt(OffsetDateTime.of(2026, 9, 30, 20, 30, 0, 0, ZoneOffset.UTC))).isFalse();
        assertThat(deadline.isMissedAt(OffsetDateTime.of(2026, 9, 30, 21, 30, 0, 0, ZoneOffset.UTC))).isTrue();
    }

    @Test
    @DisplayName("a missing cutoff means end of day, and a missing lead time means zero")
    void missingCutoffAndLeadTime() {
        ReleaseDeadline deadline = ReleaseDeadline.of(DISPATCH, null, null, LIMA);

        assertThat(deadline.endOfDay()).isTrue();
        assertThat(deadline.at()).isEqualTo(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-5)));
        assertThat(deadline.isMissedAt(OffsetDateTime.of(2026, 9, 30, 23, 59, 59, 0, ZoneOffset.ofHours(-5)))).isFalse();
        assertThat(deadline.isMissedAt(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-5)))).isTrue();
    }

    @Test
    @DisplayName("of two deadlines the earlier governs")
    void earlierGoverns() {
        ReleaseDeadline early = ReleaseDeadline.of(DISPATCH, 2, LocalTime.of(18, 0), LIMA);
        ReleaseDeadline late = ReleaseDeadline.of(DISPATCH, 1, LocalTime.of(9, 0), LIMA);

        assertThat(ReleaseDeadline.earlier(early, late)).isEqualTo(early);
        assertThat(ReleaseDeadline.earlier(late, early)).isEqualTo(early);
        assertThat(ReleaseDeadline.earlier(null, late)).isEqualTo(late);
        assertThat(ReleaseDeadline.earlier(null, null)).isNull();
    }
}
