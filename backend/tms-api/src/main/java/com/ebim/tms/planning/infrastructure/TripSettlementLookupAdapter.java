package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.shared.reference.TripSettlementLookupPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who ran a shipment (V46), answered by the module that owns {@code tms.trip}.
 *
 * <p>Read-only. Settlement never moves a shipment - an invoice is a document about work already
 * done, and letting the audit of it change the record of it would be the wrong way round.
 */
@Component
class TripSettlementLookupAdapter implements TripSettlementLookupPort {

    private final TripRepository tripRepository;

    TripSettlementLookupAdapter(TripRepository tripRepository) {
        this.tripRepository = tripRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TripSettlementSummary> findForSettlement(Collection<UUID> tripIds, UUID companyId) {
        if (tripIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, TripSettlementSummary> byTrip = new HashMap<>();
        for (Trip trip : tripRepository.findByIdInAndCompanyId(tripIds, companyId)) {
            byTrip.put(trip.id(), new TripSettlementSummary(trip.id(), trip.shipmentNumber(), trip.carrierId()));
        }
        return Map.copyOf(byTrip);
    }
}
