package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.FleetDocument;
import com.ebim.tms.shared.reference.FleetDocumentStatus;
import com.ebim.tms.shared.reference.FleetDocumentSubject;
import com.ebim.tms.shared.reference.FleetDocumentType;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * The list filters of the fleet documents screen. The status filter is translated into date
 * predicates with the boundaries {@link FleetDocumentStatus#of} applies in Java - the same pairing
 * {@code DriverSpecifications} keeps with the licence status, pinned to each other by a test.
 */
public final class FleetDocumentSpecifications {

    private FleetDocumentSpecifications() {
    }

    public static Specification<FleetDocument> matching(UUID companyId, FleetDocumentSubject subjectType,
            UUID vehicleId, UUID driverId, FleetDocumentType documentType, FleetDocumentStatus status,
            LocalDate today, int alertDays, Boolean active) {
        Specification<FleetDocument> specification = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);
        if (subjectType != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("subjectType"), subjectType));
        }
        if (vehicleId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("vehicleId"), vehicleId));
        }
        if (driverId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("driverId"), driverId));
        }
        if (documentType != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("documentType"), documentType));
        }
        if (status != null) {
            specification = specification.and(statusFilter(status, today, alertDays));
        }
        if (active != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return specification;
    }

    static Specification<FleetDocument> statusFilter(FleetDocumentStatus status, LocalDate today, int alertDays) {
        LocalDate horizon = today.plusDays(alertDays);
        return switch (status) {
            case UNRECORDED -> (root, query, cb) -> cb.isNull(root.get("expiresOn"));
            case EXPIRED -> (root, query, cb) -> cb.lessThan(root.get("expiresOn"), today);
            // Inclusive on both ends, as FleetDocumentStatus.of.
            case EXPIRING_SOON -> (root, query, cb) -> cb.between(root.get("expiresOn"), today, horizon);
            case VALID -> (root, query, cb) -> cb.greaterThan(root.get("expiresOn"), horizon);
        };
    }
}
