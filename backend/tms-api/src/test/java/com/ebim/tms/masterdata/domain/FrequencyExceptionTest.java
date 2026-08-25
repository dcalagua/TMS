package com.ebim.tms.masterdata.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one invariant {@link FrequencyException} enforces for itself. Proved without a database, so
 * it holds on a host where Testcontainers cannot run - the reasoning {@code FrequencyCalendarTest}
 * documents. {@code ck_frequency_exception_cutoff_requires_service} (V24) says the same thing at
 * the other end; this test is what makes the rule provable in this environment.
 */
class FrequencyExceptionTest {

    private static final LocalDate CHRISTMAS_EVE = LocalDate.of(2026, 12, 24);

    @Test
    @DisplayName("an open date may state its own cutoff")
    void openDateAcceptsACutoffOverride() {
        FrequencyException exception = new FrequencyException(
                UUID.randomUUID(), CHRISTMAS_EVE, true, LocalTime.of(11, 0), "Closes early", UUID.randomUUID());

        assertThat(exception.serviceOverride()).isTrue();
        assertThat(exception.cutoffTimeOverride()).isEqualTo(LocalTime.of(11, 0));
    }

    @Test
    @DisplayName("a closed date is allowed to omit a cutoff, and that is the ordinary case")
    void closedDateNeedsNoCutoff() {
        FrequencyException exception = new FrequencyException(
                UUID.randomUUID(), CHRISTMAS_EVE.plusDays(1), false, null, "Christmas Day", UUID.randomUUID());

        assertThat(exception.serviceOverride()).isFalse();
        assertThat(exception.cutoffTimeOverride()).isNull();
    }

    @Test
    @DisplayName("a closed date cannot carry a cutoff: nothing is dispatched, so there is no last moment to order")
    void closedDateRejectsACutoffOverride() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FrequencyException(
                        UUID.randomUUID(), CHRISTMAS_EVE.plusDays(1), false, LocalTime.of(11, 0), null,
                        UUID.randomUUID()))
                .withMessageContaining("closed date");
    }
}
