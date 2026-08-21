package com.ebim.tms.rates.infrastructure;

import com.ebim.tms.shared.reference.TripCostAnalyticsPort;
import com.ebim.tms.shared.reference.TripCostTotals;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link TripCostAnalyticsPort}: what the KPI report calls to learn what
 * a range of operating days cost, without either module importing the other.
 *
 * <p>A repository translation and nothing else - no rule of its own, in the shape
 * {@code CarrierLookupAdapter} established rather than the shape
 * {@code TripCostEstimationAdapter} did. That one delegates to {@code TripCostService} because
 * pricing has rules that must agree with the on-demand estimate; this one has none to agree with:
 * summing rows the module already owns is not a use case, and routing it through the service would
 * put a read with no invariants beside four writes that have several.
 *
 * <p>{@code readOnly} and joining the caller's transaction, like every other lookup adapter, so the
 * whole report is one consistent read rather than a cost total taken a moment after the shipment
 * counts it is compared against.
 */
@Component
public class TripCostAnalyticsAdapter implements TripCostAnalyticsPort {

    private final TripCostRepository tripCostRepository;

    public TripCostAnalyticsAdapter(TripCostRepository tripCostRepository) {
        this.tripCostRepository = tripCostRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TripCostTotals> totalsByCurrency(UUID companyId, LocalDate from, LocalDate to) {
        return tripCostRepository.totalsByCurrency(companyId, from, to).stream()
                .map(row -> new TripCostTotals(row.getCurrency(), row.getTripsEstimated(),
                        row.getEstimatedAmount(), row.getTripsWithActual(), row.getActualAmount(),
                        row.getTripsComparable(), row.getComparableEstimated(), row.getComparableActual()))
                .toList();
    }
}
