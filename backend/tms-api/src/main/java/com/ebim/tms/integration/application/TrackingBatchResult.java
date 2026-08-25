package com.ebim.tms.integration.application;

import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.reference.TrackingIntakeOutcome;
import com.ebim.tms.shared.reference.TrackingIntakeResult;
import java.util.List;

/**
 * What a run of positions produced, per item and in total.
 *
 * <p>Same shape as {@link OrderBatchResult} - index-aligned results, 207 when anything was
 * refused - with one addition that matters to the sender: {@code stored} counts the positions that
 * became a row, which is always less than or equal to {@code accepted}. A feed pushing every five
 * seconds against a deployment keeping one a minute will see all of its items accepted and about a
 * twelfth of them stored, and that ratio is exactly what tells its operator to push less often
 * without anybody having to ask them to.
 *
 * @param accepted items TMS has and the sender need do nothing about, whether or not they became
 *     rows - see {@link TrackingIntakeOutcome#accepted()}
 * @param refused items the sender can act on. Only these make the response a 207
 */
public record TrackingBatchResult(int submitted, int accepted, int stored, int refused, List<Item> results) {

    public TrackingBatchResult {
        results = List.copyOf(results);
    }

    public static TrackingBatchResult of(List<Item> results) {
        int accepted = (int) results.stream().filter(item -> item.error() == null).count();
        int stored = (int) results.stream()
                .filter(item -> item.outcome() == TrackingIntakeOutcome.RECORDED)
                .count();
        return new TrackingBatchResult(results.size(), accepted, stored, results.size() - accepted, results);
    }

    /**
     * @param shipmentNumber echoed so the sender can match a result to what it sent without
     *     counting indexes, exactly as {@code OrderBatchResult.Item} echoes the external reference
     * @param outcome the vocabulary of {@link TrackingIntakeOutcome}, reported for accepted items
     *     too: "we kept it", "we already had it" and "that was closer together than we store" are
     *     three different pieces of information and only the first means a row exists
     */
    public record Item(int index, String shipmentNumber, TrackingIntakeOutcome outcome, IntegrationItemError error) {

        public static Item from(TrackingIntakeResult result, String shipmentNumber) {
            return new Item(result.index(), shipmentNumber, result.outcome(),
                    result.accepted() ? null : errorFor(result));
        }

        /**
         * Maps a refusal onto the same machine codes a single-object failure carries, so a partner
         * branches on one vocabulary whether the answer arrived as an RFC 9457 document or as an
         * element of a batch - the rule {@link IntegrationItemError} states.
         */
        private static IntegrationItemError errorFor(TrackingIntakeResult result) {
            return switch (result.outcome()) {
                case UNKNOWN_SHIPMENT ->
                        new IntegrationItemError(ProblemType.RESOURCE_NOT_FOUND.code(), result.reason(), List.of());
                case NOT_TRACKABLE ->
                        new IntegrationItemError(ProblemType.CONFLICT.code(), result.reason(), List.of());
                default ->
                        new IntegrationItemError(ProblemType.MALFORMED_REQUEST.code(), result.reason(), List.of());
            };
        }
    }
}
