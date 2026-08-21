package com.ebim.tms.shared.reference;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What {@code planning} is allowed to know about a driver: enough to display one on a shipment
 * and to decide whether they may be assigned to a trip, and nothing more.
 *
 * <p>Deliberately not {@link MasterReference}, which carries a code, a name and optional
 * coordinates. Two of the three fields that matter here - the carrier the driver works for and
 * the day their licence runs out - are inputs to a <em>rule</em>, not labels, and flattening them
 * into a name would push the rule into the fleet module or, worse, into the browser. The same
 * reasoning gives {@link VehicleCapacityReference} its own type instead of a {@code MasterReference}.
 *
 * <p>{@code phone} is here because a dispatcher's next action after "which driver is on
 * SH-00000142" is to call them, and a trip screen that had to fetch the fleet master separately
 * to do it would be one round trip and one permission check away from the answer.
 *
 * @param licenseExpiresOn the last day the licence is valid, inclusive, or null when the company
 *     does not record it - never treated as expired, see {@code DriverLicenseStatus}
 * @param carrierId the carrier this driver works for, or null for a company's own staff
 * @param active whether the driver may still be assigned; a false one is resolvable for display
 *     because a trip they already ran has to keep rendering their name
 */
public record DriverReference(
        UUID id,
        String code,
        String fullName,
        String documentType,
        String documentNumber,
        String phone,
        String licenseNumber,
        String licenseCategory,
        LocalDate licenseExpiresOn,
        UUID carrierId,
        boolean active) {

    /**
     * Where this driver's licence stands on {@code date}. The date is a parameter because the two
     * callers mean different days by it: an assignment asks about the trip's planning date, a
     * dispatch about today.
     */
    public DriverLicenseStatus licenseStatusOn(LocalDate date) {
        return DriverLicenseStatus.of(licenseExpiresOn, date);
    }
}
