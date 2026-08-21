package com.ebim.tms.integration.application;

import java.util.List;

/**
 * The result of an order batch. Same shape and same reasoning as {@link LocationBatchResult} -
 * per-item independence, index-aligned results, 207 when anything was refused - carrying
 * {@link OrderUpsertResult} instead.
 *
 * <p>The order case is where per-item independence earns its keep: a day's orders routinely
 * include one that names a store created only this morning, and refusing the other 199 because of
 * it would stop a dispatch operation over a master data race.
 */
public record OrderBatchResult(int submitted, int succeeded, int failed, List<Item> results) {

    public OrderBatchResult {
        results = List.copyOf(results);
    }

    public static OrderBatchResult of(List<Item> results) {
        int failed = (int) results.stream().filter(item -> item.error() != null).count();
        return new OrderBatchResult(results.size(), results.size() - failed, failed, results);
    }

    /** @param reference the item's {@code externalReference}, echoed so the sender can match it */
    public record Item(int index, String reference, OrderUpsertResult result, IntegrationItemError error) {

        public static Item succeeded(int index, String reference, OrderUpsertResult result) {
            return new Item(index, reference, result, null);
        }

        public static Item failed(int index, String reference, IntegrationItemError error) {
            return new Item(index, reference, null, error);
        }
    }
}
