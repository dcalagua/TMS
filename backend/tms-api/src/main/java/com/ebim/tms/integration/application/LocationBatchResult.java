package com.ebim.tms.integration.application;

import java.util.List;

/**
 * The result of a location batch: a per-item outcome for every element, at the index it was sent,
 * plus a summary.
 *
 * <h2>Why items are independent</h2>
 *
 * <p>Each item is applied in its own transaction, so one bad location does not roll back the other
 * 199. That is the opposite of the bulk file import, which is deliberately all-or-nothing - and
 * the difference is the caller. A human uploading a spreadsheet can fix the file and re-upload, so
 * refusing the whole thing gives them one clear thing to do. A machine synchronising a store
 * master cannot: rejecting 200 stores because one has a malformed postcode would stop an entire
 * feed over a single row, and the sender would have to implement bisection to find out which.
 *
 * <p>The trade is stated in the response rather than hidden: {@code failed} is non-zero, the HTTP
 * status is 207, and every refused item carries its own reason and index.
 *
 * <p>Concrete rather than generic on purpose. A stored response is replayed by deserialising it
 * into this exact type, and a generic parameter would erase to {@code LinkedHashMap} on the way
 * back in.
 */
public record LocationBatchResult(int submitted, int succeeded, int failed, List<Item> results) {

    public LocationBatchResult {
        results = List.copyOf(results);
    }

    public static LocationBatchResult of(List<Item> results) {
        int failed = (int) results.stream().filter(item -> item.error() != null).count();
        return new LocationBatchResult(results.size(), results.size() - failed, failed, results);
    }

    /**
     * One item's outcome. Exactly one of {@code result} and {@code error} is non-null, which makes
     * the element unambiguous without a status field to interpret.
     *
     * @param index     zero-based position in the submitted array, so the sender can match the
     *                  outcome to their own record without relying on the code
     * @param reference the item's {@code code}, echoed for the same reason
     */
    public record Item(int index, String reference, LocationUpsertResult result, IntegrationItemError error) {

        public static Item succeeded(int index, String reference, LocationUpsertResult result) {
            return new Item(index, reference, result, null);
        }

        public static Item failed(int index, String reference, IntegrationItemError error) {
            return new Item(index, reference, null, error);
        }
    }
}
