package com.ebim.tms.rates.infrastructure;

import com.ebim.tms.rates.domain.TripCost;
import com.ebim.tms.shared.reference.TripCostLookupPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a shipment was priced at and what it cost (V46), answered by the module that owns
 * {@code tms.trip_cost}.
 *
 * <p>Read-only by design. Settlement compares these figures and never writes them - V30's
 * close/reopen is the only thing that may change what a shipment cost, and a second writer would be
 * how two numbers come to disagree.
 *
 * <p>The company predicate is in the query and not applied to the result, which is the rule every
 * finder in this codebase follows and what {@code TenantScopedRepositoryTest} enforces.
 */
@Component
class TripCostLookupAdapter implements TripCostLookupPort {

    private final TripCostRepository tripCostRepository;

    TripCostLookupAdapter(TripCostRepository tripCostRepository) {
        this.tripCostRepository = tripCostRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TripCostSummary> findCosts(Collection<UUID> tripIds, UUID companyId) {
        if (tripIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, TripCostSummary> byTrip = new HashMap<>();
        for (TripCost cost : tripCostRepository.findByCompanyIdAndTripIdIn(companyId, tripIds)) {
            byTrip.put(cost.tripId(), new TripCostSummary(
                    cost.tripId(),
                    // Null stays null. A shipment nobody estimated has no expected figure, and
                    // reading that as zero would report an entire invoice as an overcharge.
                    cost.estimatedAmount(),
                    cost.actualAmount(),
                    cost.currency()));
        }
        return Map.copyOf(byTrip);
    }
}
