package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.Carrier;
import com.ebim.tms.fleet.domain.Driver;
import com.ebim.tms.shared.reference.DriverLicenseStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API-facing view of a {@link Driver}, kept separate from the JPA entity (review chain rule).
 *
 * <p>Carries the two derived values the frontend must not compute itself: {@code fullName}, so a
 * screen never has to guess the order of the two name columns, and {@code licenseStatus}, so the
 * badge on a list, the warning in a picker and the refusal from an assignment all come from one
 * rule ({@link DriverLicenseStatus}) evaluated in one place.
 *
 * @param licenseStatus judged against the company's own current day, not the server's - see
 *     {@code DriverService.today}
 */
public record DriverView(
        UUID id,
        String code,
        String firstName,
        String lastName,
        String fullName,
        String documentType,
        String documentNumber,
        String phone,
        String licenseNumber,
        String licenseCategory,
        LocalDate licenseExpiresOn,
        DriverLicenseStatus licenseStatus,
        UUID carrierId,
        String carrierCode,
        String carrierBusinessName,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param carrier the driver's carrier, already resolved by the caller (batched for lists) -
     *     {@code null} when the driver is the company's own staff
     * @param today the day the licence status is judged against
     */
    public static DriverView from(Driver driver, Carrier carrier, LocalDate today) {
        return new DriverView(driver.id(), driver.code(), driver.firstName(), driver.lastName(), driver.fullName(),
                driver.documentType(), driver.documentNumber(), driver.phone(), driver.licenseNumber(),
                driver.licenseCategory(), driver.licenseExpiresOn(),
                DriverLicenseStatus.of(driver.licenseExpiresOn(), today), driver.carrierId(),
                carrier == null ? null : carrier.code(), carrier == null ? null : carrier.businessName(),
                driver.active(), driver.createdAt(), driver.updatedAt());
    }
}
