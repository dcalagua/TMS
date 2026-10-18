package com.ebim.tms.integration.application;

import java.util.List;

/**
 * One result per event, in the order received: {@code HOLD_PLACED}, {@code ALREADY_HELD},
 * {@code NOTED}, {@code ORDER_CLOSED}, {@code UNKNOWN_ORDER}, {@code DUPLICATE} or {@code INVALID}
 * (WAREHOUSE_EXECUTION_V1 7.5). {@code recorded} counts the events stored by this delivery.
 */
public record WarehouseOrderEventBatchResultV1(int received, int recorded, int refused, List<Item> results) {

    /** @param orderNumber the TMS order the event resolved to, when it resolved to one */
    public record Item(int index, String eventId, String result, String orderNumber, String message) {
    }
}
