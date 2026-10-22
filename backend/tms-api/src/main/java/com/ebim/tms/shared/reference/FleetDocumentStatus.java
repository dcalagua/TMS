package com.ebim.tms.shared.reference;

import java.time.LocalDate;

/**
 * How a fleet compliance document stands on a given day (migration V72) - the
 * {@link DriverLicenseStatus} of every other paper, with the alert window a company setting instead
 * of a constant.
 *
 * <p>Derived, never stored, for the reason the licence status is: a status column would be wrong
 * every morning at midnight.
 */
public enum FleetDocumentStatus {

    /** No expiry date known - the document is missing, or on file without one. Never expired. */
    UNRECORDED,

    /** Valid, and not within the alert window. */
    VALID,

    /** Valid on the day asked about, and expiring within the alert window of it. */
    EXPIRING_SOON,

    /** The last valid day has passed. */
    EXPIRED;

    /** The window a company that never configured one gets: the licence's. */
    public static final int DEFAULT_ALERT_DAYS = DriverLicenseStatus.EXPIRY_WARNING_DAYS;

    /**
     * @param expiresOn the last valid day, <em>inclusive</em>, or null when not recorded
     * @param on        the day being asked about: today for a screen, the planning date for a trip
     * @param alertDays how many days ahead an expiry counts as imminent
     */
    public static FleetDocumentStatus of(LocalDate expiresOn, LocalDate on, int alertDays) {
        if (expiresOn == null) {
            return UNRECORDED;
        }
        if (expiresOn.isBefore(on)) {
            return EXPIRED;
        }
        return expiresOn.isAfter(on.plusDays(alertDays)) ? VALID : EXPIRING_SOON;
    }
}
