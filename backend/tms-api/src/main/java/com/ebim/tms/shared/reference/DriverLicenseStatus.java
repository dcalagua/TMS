package com.ebim.tms.shared.reference;

import java.time.LocalDate;

/**
 * How a driver's licence stands on a given day - the one place the "expired" and "about to
 * expire" rules are written.
 *
 * <p>Derived, never stored: a status column would be wrong every morning at midnight and would
 * need a job to keep it true.
 *
 * <p>It lives in {@code shared.reference} rather than in {@code fleet.domain} because it is
 * exactly the vocabulary two modules have to agree in. {@code fleet} computes it for the drivers
 * screen and filters a query by it; {@code planning} asks it before allowing an assignment and
 * again before a dispatch. Neither module may depend on the other ({@code ModuleBoundaryTest}), so
 * a copy on each side would be two encodings of one rule - the failure mode where a driver is
 * assignable on one screen and refused by the endpoint behind it.
 *
 * <p>Only {@link #EXPIRED} blocks anything. {@link #EXPIRING_SOON} is a warning a planner acts on
 * by choice - refusing a driver whose licence runs out next week would strand shipments that
 * finish today - and {@link #UNRECORDED} blocks nothing at all, because not knowing an expiry is
 * not evidence of one (see {@code tms.driver.license_expires_on}, migration V26).
 */
public enum DriverLicenseStatus {

    /** No expiry date on file. Never a refusal - see the class comment. */
    UNRECORDED,

    /** Valid, and not close enough to expiry to be worth interrupting anyone about. */
    VALID,

    /** Valid on the day asked about, but expiring within {@link #EXPIRY_WARNING_DAYS} of it. */
    EXPIRING_SOON,

    /** The expiry date has passed. This is the one that stops an assignment and a dispatch. */
    EXPIRED;

    /**
     * How far ahead an expiry counts as imminent. Thirty days is a month's notice: long enough to
     * renew a licence in the markets this product targets, short enough that the warning still
     * means something when it appears.
     */
    public static final int EXPIRY_WARNING_DAYS = 30;

    /**
     * @param expiresOn the last day the licence is valid, <em>inclusive</em>, or null when not
     *     recorded. Inclusive because that is what a driver is told when they are handed it: a
     *     licence that expires on the 30th is still valid on the 30th.
     * @param on the day being asked about - "today" for a screen or a dispatch, the trip's
     *     planning date for an assignment, which is why it is a parameter and not {@code now()}
     */
    public static DriverLicenseStatus of(LocalDate expiresOn, LocalDate on) {
        if (expiresOn == null) {
            return UNRECORDED;
        }
        if (expiresOn.isBefore(on)) {
            return EXPIRED;
        }
        return expiresOn.isAfter(on.plusDays(EXPIRY_WARNING_DAYS)) ? VALID : EXPIRING_SOON;
    }
}
