package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebim.tms.shared.api.InvalidRequestException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The planning-date/departure consistency rule, in the two places it is easy to get wrong: the
 * boundary hours of a day, where the company's zone and UTC disagree about which day it is.
 */
class ShipmentTimeRulesTest {

    private static final LocalDate PLANNING_DATE = LocalDate.of(2026, 8, 20);
    private static final String LIMA = "America/Lima";

    private static void check(OffsetDateTime departure, String zone) {
        ShipmentTimeRules.requireDepartureOnPlanningDate(departure, PLANNING_DATE, zone, "Trip 1");
    }

    @Test
    @DisplayName("a departure during the planning day is accepted")
    void acceptsADepartureOnThePlanningDate() {
        assertThatCode(() -> check(OffsetDateTime.of(2026, 8, 20, 6, 0, 0, 0, ZoneOffset.ofHours(-5)), LIMA))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no departure yet is not an inconsistency")
    void acceptsAMissingDeparture() {
        assertThatCode(() -> check(null, LIMA)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a departure on another day is refused as a bad request")
    void refusesADepartureOnAnotherDay() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> check(OffsetDateTime.of(2026, 8, 25, 6, 0, 0, 0, ZoneOffset.ofHours(-5)), LIMA))
                .withMessageContaining("2026-08-25")
                .withMessageContaining("2026-08-20");
    }

    @Test
    @DisplayName("the company's zone decides the day, not UTC")
    void judgesTheDayInTheCompanyTimeZone() {
        // 20:00 in Lima on the 20th is already 01:00 UTC on the 21st. Judged by UTC this would be
        // refused, and the last five hours of every planning day would be unplannable.
        OffsetDateTime lateEvening = OffsetDateTime.of(2026, 8, 21, 1, 0, 0, 0, ZoneOffset.UTC);

        assertThatCode(() -> check(lateEvening, LIMA)).doesNotThrowAnyException();
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() -> check(lateEvening, "UTC"));
    }

    @Test
    @DisplayName("the offset the client sent does not decide the day either")
    void ignoresTheClientOffset() {
        // The same instant as the previous test, sent with a Lima offset instead of Z.
        OffsetDateTime sameInstant = OffsetDateTime.of(2026, 8, 20, 20, 0, 0, 0, ZoneOffset.ofHours(-5));

        assertThatCode(() -> check(sameInstant, LIMA)).doesNotThrowAnyException();
        assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() -> check(sameInstant, "UTC"));
    }

    @Test
    @DisplayName("an unusable company time zone falls back instead of making planning impossible")
    void fallsBackOnAnUnknownZone() {
        OffsetDateTime noon = OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0,
                ZoneOffset.ofTotalSeconds(java.time.ZoneId.systemDefault().getRules()
                        .getOffset(java.time.Instant.parse("2026-08-20T12:00:00Z")).getTotalSeconds()));

        assertThatCode(() -> check(noon, "Not/AZone")).doesNotThrowAnyException();
        assertThatCode(() -> check(noon, null)).doesNotThrowAnyException();
        assertThatCode(() -> check(noon, "   ")).doesNotThrowAnyException();
    }
}
