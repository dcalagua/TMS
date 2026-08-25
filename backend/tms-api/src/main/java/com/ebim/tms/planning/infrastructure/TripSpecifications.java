package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.application.TripFilter;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.Trip;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composes the Trips screen's list filters. Same shape as {@link PlanningRunSpecifications}, with
 * one difference worth naming: the origin filter.
 *
 * <p>A trip has no origin column - it departs from its <em>run's</em> origin, which migration V11
 * chose so that trip and run can never disagree about where a shipment leaves from. Filtering by
 * origin therefore means asking the run, and the subquery below is the price of that guarantee.
 * The alternative, copying origin_id onto every trip, would buy a marginally simpler query with a
 * second source of truth for the one fact the schema went out of its way to keep single.
 *
 * <p>The subquery is company-scoped in its own right, not only through the trip: a filter is a
 * caller-supplied id, and resolving it against another tenant's runs - even to return nothing -
 * is not a query this module should be able to phrase.
 */
public final class TripSpecifications {

    private TripSpecifications() {}

    public static Specification<Trip> matching(UUID companyId, TripFilter filter) {
        Specification<Trip> specification = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (filter.shipmentNumber() != null && !filter.shipmentNumber().isBlank()) {
            String pattern = "%" + filter.shipmentNumber().trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and(
                    (root, query, cb) -> cb.like(cb.lower(root.get("shipmentNumber")), pattern));
        }
        if (filter.status() != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), filter.status()));
        }
        if (filter.carrierId() != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("carrierId"), filter.carrierId()));
        }
        if (filter.vehicleId() != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("vehicleId"), filter.vehicleId()));
        }
        if (filter.driverId() != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("driverId"), filter.driverId()));
        }
        if (filter.planningDateFrom() != null) {
            specification = specification.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("planningDate"), filter.planningDateFrom()));
        }
        if (filter.planningDateTo() != null) {
            specification = specification.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("planningDate"), filter.planningDateTo()));
        }
        if (filter.originId() != null) {
            specification = specification.and((root, query, cb) -> {
                Subquery<UUID> runs = query.subquery(UUID.class);
                Root<PlanningRun> run = runs.from(PlanningRun.class);
                runs.select(run.get("id")).where(
                        cb.equal(run.get("companyId"), companyId),
                        cb.equal(run.get("originId"), filter.originId()));
                return root.get("planningRunId").in(runs);
            });
        }
        return specification;
    }
}
