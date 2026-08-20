package com.ebim.tms.shared.reference;

import java.util.List;

/** One published shipment with its ordered stops and the orders assigned to it. */
public record PublishedShipmentDetail(
        PublishedShipment shipment, List<PublishedShipmentStop> stops, List<PublishedShipmentOrder> orders) {
}
