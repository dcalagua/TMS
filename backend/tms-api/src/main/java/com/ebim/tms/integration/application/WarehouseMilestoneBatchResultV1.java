package com.ebim.tms.integration.application;

import java.util.List;

/** One result per milestone, in the order received: RECORDED, DUPLICATE, UNKNOWN_SHIPMENT or INVALID. */
public record WarehouseMilestoneBatchResultV1(int received, int recorded, int refused, List<Item> results) {

    public record Item(int index, String eventId, String result, String message) {
    }
}
