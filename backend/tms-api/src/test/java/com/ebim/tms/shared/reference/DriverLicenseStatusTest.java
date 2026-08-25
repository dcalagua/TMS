package com.ebim.tms.shared.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The licence rule, pinned at its boundaries.
 *
 * <p>Every date here is relative to one fixed reference day rather than to {@code now()}: this
 * rule is entirely about the distance between two dates, and a test that asked the clock would be
 * testing the clock. The reference day is arbitrary and deliberately not "today".
 */
class DriverLicenseStatusTest {

    private static final LocalDate ON = LocalDate.of(2026, 8, 20);

    @Test
    @DisplayName("no expiry on file is UNRECORDED - not knowing is not evidence of expiry")
    void missingExpiryIsUnrecorded() {
        assertThat(DriverLicenseStatus.of(null, ON)).isEqualTo(DriverLicenseStatus.UNRECORDED);
    }

    @Test
    @DisplayName("the expiry day itself is still valid - inclusive, as a driver is told when handed the licence")
    void expiryDayIsInclusive() {
        assertThat(DriverLicenseStatus.of(ON, ON)).isEqualTo(DriverLicenseStatus.EXPIRING_SOON);
        assertThat(DriverLicenseStatus.of(ON.minusDays(1), ON)).isEqualTo(DriverLicenseStatus.EXPIRED);
    }

    @Test
    @DisplayName("the warning horizon is inclusive on its last day and VALID one day past it")
    void warningHorizonBoundary() {
        LocalDate horizon = ON.plusDays(DriverLicenseStatus.EXPIRY_WARNING_DAYS);

        assertThat(DriverLicenseStatus.of(horizon, ON)).isEqualTo(DriverLicenseStatus.EXPIRING_SOON);
        assertThat(DriverLicenseStatus.of(horizon.plusDays(1), ON)).isEqualTo(DriverLicenseStatus.VALID);
    }

    /**
     * The four statuses partition every possible expiry date, which is what lets
     * {@code DriverSpecifications} translate a filter into date predicates without a fifth
     * "everything else" branch. If a date ever fell through, the screen would show a driver the
     * filter could not find.
     */
    @Test
    @DisplayName("the four statuses partition the whole range of dates around the reference day")
    void statusesPartitionTheRange() {
        for (int offset = -60; offset <= 60; offset++) {
            LocalDate expiresOn = ON.plusDays(offset);
            DriverLicenseStatus status = DriverLicenseStatus.of(expiresOn, ON);

            DriverLicenseStatus expected;
            if (offset < 0) {
                expected = DriverLicenseStatus.EXPIRED;
            } else if (offset <= DriverLicenseStatus.EXPIRY_WARNING_DAYS) {
                expected = DriverLicenseStatus.EXPIRING_SOON;
            } else {
                expected = DriverLicenseStatus.VALID;
            }
            assertThat(status).as("expiry %s judged on %s", expiresOn, ON).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("DriverReference asks the same question through licenseStatusOn")
    void referenceDelegates() {
        DriverReference driver = new DriverReference(java.util.UUID.randomUUID(), "DR-1", "Quispe, Ana", "DNI",
                "12345678", null, "Q-987654", "A-IIB", ON.minusDays(1), null, true);

        assertThat(driver.licenseStatusOn(ON)).isEqualTo(DriverLicenseStatus.EXPIRED);
        assertThat(driver.licenseStatusOn(ON.minusDays(5))).isEqualTo(DriverLicenseStatus.EXPIRING_SOON);
    }
}
