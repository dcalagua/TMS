package com.ebim.tms.planning.application;

import com.ebim.tms.planning.infrastructure.CarrierScorecardQueries;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The drill-down of one scorecard row: the trips the figures were counted from, newest first.
 *
 * <p>Capped at {@link CarrierScorecardService#TRIP_LIMIT}, and the cap is said rather than hidden:
 * {@code total} is every trip of the carrier in the range and {@code truncated} is true when the list
 * stops short of it - a list cut in silence is read as complete.
 */
public record CarrierScorecardTripsView(
        String carrierKey,
        UUID carrierId,
        boolean ownFleet,
        String carrierName,
        LocalDate from,
        LocalDate to,
        long total,
        boolean truncated,
        List<CarrierScorecardQueries.TripRow> trips) {

    public CarrierScorecardTripsView {
        trips = List.copyOf(trips);
    }
}
