package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.PlanningRunStatus;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composes the optional list filters for {@link PlanningRunRepository}. See {@code TransportOrderSpecifications}. */
public final class PlanningRunSpecifications {

    private PlanningRunSpecifications() {}

    public static Specification<PlanningRun> matching(UUID companyId, String planNumber, UUID originId,
            LocalDate planningDateFrom, LocalDate planningDateTo, PlanningRunStatus status) {
        Specification<PlanningRun> specification = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (planNumber != null && !planNumber.isBlank()) {
            String pattern = "%" + planNumber.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("planNumber")), pattern));
        }
        if (originId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("originId"), originId));
        }
        if (planningDateFrom != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("planningDate"), planningDateFrom));
        }
        if (planningDateTo != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.lessThanOrEqualTo(root.get("planningDate"), planningDateTo));
        }
        if (status != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return specification;
    }
}
