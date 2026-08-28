package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.TransportEventSource;
import com.ebim.tms.planning.domain.DeliveryQuantities;
import com.ebim.tms.shared.reference.OrderAmounts;
import java.math.BigDecimal;
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
        List<DeliveryEvidenceView> evidence,
        /**
         * How much was taken, delivered, refused and left outstanding (migration V45, debt D3).
         *
         * <p><b>Null means not recorded</b>, and a screen must render that as a gap rather than as
         * zero. Every delivery written before V45 carries null here and made no claim about
         * amounts; showing them as "0 delivered" would invent a shortfall that never happened.
         */
        DeliveryQuantitiesView quantities) {

    /**
     * The four figures, flattened for the wire. {@code outstanding} is derived server-side rather
     * than left to each screen to subtract - it is the amount a second attempt would carry, and two
     * clients computing it separately is how they come to disagree.
     */
    public record DeliveryQuantitiesView(
            BigDecimal attemptedWeightKg, BigDecimal attemptedVolumeM3, BigDecimal attemptedPallets,
            BigDecimal deliveredWeightKg, BigDecimal deliveredVolumeM3, BigDecimal deliveredPallets,
            BigDecimal refusedWeightKg, BigDecimal refusedVolumeM3, BigDecimal refusedPallets,
            BigDecimal outstandingWeightKg, BigDecimal outstandingVolumeM3, BigDecimal outstandingPallets) {

        /** Null for a delivery that recorded no amounts - the caller renders a gap, not a zero. */
        public static DeliveryQuantitiesView of(DeliveryQuantities quantities) {
            if (quantities == null || !quantities.isRecorded()) {
                return null;
            }
            OrderAmounts outstanding = quantities.outstanding();
            return new DeliveryQuantitiesView(
                    quantities.attemptedWeight(), quantities.attemptedVolume(), quantities.attemptedPallets(),
                    quantities.deliveredWeight(), quantities.deliveredVolume(), quantities.deliveredPallets(),
                    quantities.refusedWeight(), quantities.refusedVolume(), quantities.refusedPallets(),
                    outstanding.weightKg(), outstanding.volumeM3(), outstanding.pallets());
        }
    }
}
