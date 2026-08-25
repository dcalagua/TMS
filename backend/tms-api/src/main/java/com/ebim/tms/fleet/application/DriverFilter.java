package com.ebim.tms.fleet.application;

import com.ebim.tms.shared.reference.DriverLicenseStatus;
import java.util.UUID;

/**
 * The optional list filters for {@code GET /fleet/drivers}, bound alongside {@code PageQuery}.
 *
 * <p>{@code name} matches either half of the name, so a dispatcher does not have to know whether
 * what they typed is a given name or a surname. {@code licenseStatus} is the filter behind the
 * badge - "show me whose licence has run out" is the question this master exists to answer before
 * a truck is at the gate rather than after.
 */
public record DriverFilter(
        String code, String name, UUID carrierId, DriverLicenseStatus licenseStatus, Boolean active) {
}
