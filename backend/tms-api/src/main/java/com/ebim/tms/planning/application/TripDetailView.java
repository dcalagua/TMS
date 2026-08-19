package com.ebim.tms.planning.application;

import java.util.List;

/**
 * One trip with everything on it: the board row, its active assignments and its ordered stops.
 * Returned by every trip endpoint except the board itself - see {@link TripView} for why the two
 * shapes differ.
 */
public record TripDetailView(TripView trip, List<TripAssignmentView> assignments, List<TripStopView> stops) {
}
