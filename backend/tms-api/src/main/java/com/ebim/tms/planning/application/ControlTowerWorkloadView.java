package com.ebim.tms.planning.application;

import java.math.BigDecimal;

/**
 * One row of the "which vehicles are carrying the most" panel: a shipment and how full the truck
 * running it is.
 *
 * @param trip        the shipment header, resolved exactly as everywhere else
 * @param percentUsed the <em>worst</em> of the three capacity dimensions - the one that decides
 *     whether the truck is full - or null when the trip has no vehicle yet, or when every
 *     dimension the vehicle declares is unlimited or zero. Decided here rather than in the browser
 *     so a screen cannot invent a fourth answer: a null is "we do not know how full this is", and
 *     rendering it as 0% would read as an empty truck
 */
public record ControlTowerWorkloadView(TripView trip, BigDecimal percentUsed) {
}
