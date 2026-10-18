package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.AccrualBasis;
import com.ebim.tms.shared.reference.AccruableTripLookupPort.AccruableTrip;
import com.ebim.tms.shared.reference.TripCostLookupPort.TripCostSummary;
import java.math.BigDecimal;

/**
 * What a trip would be accrued at, or why it cannot be (V65, ADR-023).
 *
 * <p>The closed actual if the cost is closed, otherwise the estimate. Nothing else: an actual
 * recorded on an open cost is still being argued about, and a zero standing for "unknown" is the
 * invented figure the ADR forbids.
 */
public sealed interface AccrualPricing {

    /** A positive figure, in the trip cost's currency. */
    record Priced(BigDecimal amount, String currency, AccrualBasis basis) implements AccrualPricing {
    }

    /** Not accruable, with the reason a person reads. */
    record Unpriced(UnaccruedReason reason) implements AccrualPricing {
    }

    /** Own fleet: not a freight payable, so neither accrued nor listed. */
    record OwnFleet() implements AccrualPricing {
    }

    static AccrualPricing of(AccruableTrip trip, TripCostSummary cost) {
        if (trip.ownFleet()) {
            return new OwnFleet();
        }
        if (trip.carrierId() == null) {
            return new Unpriced(UnaccruedReason.NO_CARRIER);
        }
        if (cost == null) {
            return new Unpriced(UnaccruedReason.NO_TRIP_COST);
        }
        if (cost.closed() && positive(cost.actualAmount())) {
            return new Priced(cost.actualAmount(), cost.currency(), AccrualBasis.ACTUAL);
        }
        if (!cost.closed() && positive(cost.expectedAmount())) {
            return new Priced(cost.expectedAmount(), cost.currency(), AccrualBasis.ESTIMATED);
        }
        return new Unpriced(UnaccruedReason.NOT_CALCULABLE);
    }

    private static boolean positive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }
}
