package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.TransportEventSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What happened to one order at one stop, as a screen sees it (migration V28).
 *
 * <p>Flat rather than nested inside {@link TripStopView}, and that is deliberate: a delivery is
 * addressed by {@code (stop, order)} in the API, it is rendered under both the stop it happened at
 * and the order it was about, and a client that has to reach into a stop to find one would have to
 * flatten the list itself. {@code tripStopId} and {@code orderId} are what a screen groups by;
 * {@code stopSequence} and {@code orderNumber} are what it prints.
 *
 * <p>The <em>absence</em> of a view for an assigned order means nobody has recorded that order yet.
 * There is no {@code PENDING} result, because "not recorded" and "recorded as pending" are the same
 * statement told twice - see {@link DeliveryResult}.
 *
 * @param recordedByName who recorded it - an operator's address or an integration's machine label,
 *     snapshotted at the time
 * @param recordedAt when the row was typed, against {@code deliveredAt}'s when-it-happened. Both
 *     are shown: an entry made at 18:40 for a handover at 09:15 is an end-of-day paperwork run,
 *     which is ordinary and must be visible as such
 * @param evidence the artefacts backing this result up, oldest first; empty when there are none,
 *     which is every delivery in a deployment with no configured store
 */
public record OrderDeliveryView(
        UUID id,
        UUID tripStopId,
        Integer stopSequence,
        UUID orderId,
        String orderNumber,
        DeliveryResult result,
        OffsetDateTime deliveredAt,
        String receiverName,
        String receiverDocument,
        String notes,
        TransportEventSource source,
        String recordedByName,
        OffsetDateTime recordedAt,
        List<DeliveryEvidenceView> evidence) {
}
