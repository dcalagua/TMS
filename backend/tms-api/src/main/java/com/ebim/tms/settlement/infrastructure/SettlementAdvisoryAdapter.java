package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.shared.reference.SettlementAdvisoryPort;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link SettlementAdvisoryPort} (JOB 23).
 *
 * <p>Lives here because it reads {@code FreightDiscrepancy}, which the settlement module owns. The
 * control tower gets a projection and no entity, so it cannot accidentally acquire the ability to
 * resolve one - which is the whole point of the port being read-only.
 */
@Component
public class SettlementAdvisoryAdapter implements SettlementAdvisoryPort {

    private final FreightDiscrepancyRepository discrepancyRepository;

    public SettlementAdvisoryAdapter(FreightDiscrepancyRepository discrepancyRepository) {
        this.discrepancyRepository = discrepancyRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementAdvisory> findOpenDiscrepancies(UUID companyId, Collection<UUID> tripIds, int limit) {
        // An empty day asks nothing. `in ()` is not portable and would be a query run for no reason.
        if (tripIds.isEmpty()) {
            return List.of();
        }
        return discrepancyRepository.findOpenForTrips(companyId, tripIds, PageRequest.of(0, limit));
    }

    @Override
    @Transactional(readOnly = true)
    public long countOpenDiscrepancies(UUID companyId, Collection<UUID> tripIds) {
        return tripIds.isEmpty() ? 0L : discrepancyRepository.countOpenForTrips(companyId, tripIds);
    }
}
