package com.ebim.tms.rates.infrastructure;

import com.ebim.tms.rates.application.TripCostService;
import com.ebim.tms.shared.reference.TripCostEstimationPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only implementation of {@link TripCostEstimationPort}: what {@code planning} calls when a
 * plan is confirmed, without either module importing the other.
 *
 * <p>A thin delegation and nothing else. The rule about what confirmation-time pricing stays quiet
 * about lives in {@link TripCostService#estimateOnConfirmation}, beside the on-demand estimate it
 * has to agree with, rather than here where it would be one adapter away from the calculation it
 * governs.
 */
@Component
public class TripCostEstimationAdapter implements TripCostEstimationPort {

    private final TripCostService tripCostService;

    public TripCostEstimationAdapter(TripCostService tripCostService) {
        this.tripCostService = tripCostService;
    }

    @Override
    public void estimateOnConfirmation(CompanyScope scope, UUID tripId, UUID actorId) {
        tripCostService.estimateOnConfirmation(scope, tripId, actorId);
    }
}
