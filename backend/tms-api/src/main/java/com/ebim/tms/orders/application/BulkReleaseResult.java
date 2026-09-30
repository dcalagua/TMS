package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.Eligibility;
import com.ebim.tms.orders.domain.SchedulingReason;
import java.util.List;
import java.util.UUID;

/**
 * The per-item answer of a bulk release - the Integration API's batch shape
 * ({@code OrderBatchResult}): index-aligned results, and a 207 whenever anything was refused.
 */
public record BulkReleaseResult(int submitted, int released, int refused, List<Item> results) {

    public BulkReleaseResult {
        results = List.copyOf(results);
    }

    public static BulkReleaseResult of(List<Item> results) {
        int refused = (int) results.stream().filter(item -> !item.released()).count();
        return new BulkReleaseResult(results.size(), results.size() - refused, refused, results);
    }

    public boolean anyRefused() {
        return refused > 0;
    }

    /**
     * @param status           the status the order ended in, when it was found
     * @param eligibility      what the gate judged, when it got that far
     * @param overrideRequired true when only a reason was missing
     * @param reasons          the eligibility reasons, when the gate refused on them
     * @param message          the sentence for any refusal
     */
    public record Item(int index, UUID orderId, String orderNumber, boolean released, String status,
            Eligibility eligibility, boolean overrideRequired, List<SchedulingReason> reasons, String message) {

        public Item {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }
}
