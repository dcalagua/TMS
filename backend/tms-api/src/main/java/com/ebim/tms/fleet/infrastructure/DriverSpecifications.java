package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.Driver;
import com.ebim.tms.shared.reference.DriverLicenseStatus;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composes the optional list filters for {@link DriverRepository}. */
public final class DriverSpecifications {

    private DriverSpecifications() {}

    /**
     * @param name           matched as a substring against either half of the name, so typing a
     *                       surname finds the driver and so does typing a given name - a
     *                       dispatcher searching for "Ana" should not have to know which column
     *                       the master keeps her in
     * @param licenseStatus  translated into date predicates against {@code today}, using the same
     *                       boundaries {@link DriverLicenseStatus#of} applies in Java. Two
     *                       encodings of one rule is a real risk, and
     *                       {@code DriverLicenseStatusTest} pins them to each other.
     * @param today          the day the licence status is judged against, resolved from the
     *                       company's own time zone by the caller - never {@code LocalDate.now()}
     *                       here, which would be the server's day
     */
    public static Specification<Driver> matching(UUID companyId, String code, String name, UUID carrierId,
            DriverLicenseStatus licenseStatus, LocalDate today, Boolean active) {
        Specification<Driver> specification = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (code != null && !code.isBlank()) {
            String pattern = "%" + code.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("code")), pattern));
        }
        if (name != null && !name.isBlank()) {
            String pattern = "%" + name.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("firstName")), pattern),
                    cb.like(cb.lower(root.get("lastName")), pattern)));
        }
        if (carrierId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("carrierId"), carrierId));
        }
        if (licenseStatus != null) {
            specification = specification.and(licenseFilter(licenseStatus, today));
        }
        if (active != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return specification;
    }

    private static Specification<Driver> licenseFilter(DriverLicenseStatus status, LocalDate today) {
        LocalDate warningHorizon = today.plusDays(DriverLicenseStatus.EXPIRY_WARNING_DAYS);
        return switch (status) {
            case UNRECORDED -> (root, query, cb) -> cb.isNull(root.get("licenseExpiresOn"));
            case EXPIRED -> (root, query, cb) -> cb.lessThan(root.get("licenseExpiresOn"), today);
            // Inclusive on both ends, matching DriverLicenseStatus.of: the day it expires is still
            // valid, and the horizon day itself is still "soon".
            case EXPIRING_SOON -> (root, query, cb) ->
                    cb.between(root.get("licenseExpiresOn"), today, warningHorizon);
            case VALID -> (root, query, cb) -> cb.greaterThan(root.get("licenseExpiresOn"), warningHorizon);
        };
    }
}
