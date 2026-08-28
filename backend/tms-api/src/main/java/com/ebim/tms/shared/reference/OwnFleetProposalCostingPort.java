package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * What one of our own trucks would cost on a proposed trip (V48, JOB 22).
 *
 * <p>How planning reaches own-fleet costing without depending on it: the costing module owns the
 * profiles and the arithmetic, planning owns the proposal and its measurements, and this is the
 * whole of what crosses between them. Same shape as {@code RoutingPort} and for the same reason -
 * the module stays extractable.
 *
 * <p>Deliberately takes the distance and the duty rather than a trip id. A proposed trip <b>does
 * not exist yet</b>; there is nothing to look up, and the caller has already measured it.
 */
public interface OwnFleetProposalCostingPort {

    /**
     * @param distanceKm  the run's kilometres, or null when a leg could not be measured
     * @param dutyMinutes driving plus service time, or null when a leg could not be measured
     * @return a costed quote; empty when no profile is in force for that vehicle on that date. A
     *         present quote may still carry a <b>null amount</b>, meaning a profile exists and a
     *         component it charges for had no quantity. The two are different messages for
     *         different people - configure the rates, or geocode the stop - and collapsing them
     *         into one "no cost" would send both to whoever read it first
     */
    Optional<TransportCostQuote> costProposedTrip(UUID companyId, UUID vehicleId, LocalDate on,
            BigDecimal distanceKm, Long dutyMinutes);
}
