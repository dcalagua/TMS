package com.ebim.tms.integration.application;

import java.util.List;

/**
 * One shipment, in full - the response of
 * {@code GET /integration/v1/shipments/{shipmentNumber}}. The same header/detail split
 * {@code planning.application.TripView}/{@code TripDetailView} uses internally, for the same
 * reason: a list of shipments must not fan out into one query per row for stops nobody asked for.
 */
public record ShipmentPlanV1(
        ShipmentPlanHeaderV1 shipment, List<ShipmentPlanStopV1> stops, List<ShipmentPlanOrderV1> orders) {
}
