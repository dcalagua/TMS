package com.ebim.tms.rates.application;

import com.ebim.tms.rates.domain.RateCardScope;
import com.ebim.tms.rates.domain.TripCost;
import com.ebim.tms.rates.domain.TripCostComponent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The estimated and actual cost of one trip, with the lines that explain the estimate.
 *
 * <p>Always returned, even for a trip nobody has priced: {@link #none} produces the "not priced
 * yet" shape rather than a 404. "The cost of trip X" is a resource that exists as soon as the trip
 * does - it is simply empty until somebody or something fills it - and answering 404 would make
 * every caller treat a perfectly normal state as an error.
 *
 * @param priced            false for the empty shape; every other field is then null or zero
 * @param estimateComplete  whether every component the card charges for could actually be
 *                          calculated. False means the estimate is understated by the
 *                          {@code NOT_CALCULABLE} lines below, which is a thing to say out loud
 *                          rather than to hide behind a total.
 * @param variance          {@code actual - estimated}, or null while either is missing. Positive
 *                          means the shipment cost more than the agreement said it would.
 */
public record TripCostView(
        UUID tripId,
        boolean priced,
        String currency,
        BigDecimal estimatedAmount,
        OffsetDateTime estimatedAt,
        UUID rateCardId,
        String rateCardCode,
        String rateCardName,
        RateCardScope rateCardScope,
        boolean estimateComplete,
        List<TripCostComponentView> components,
        BigDecimal actualAmount,
        String actualReference,
        String actualNotes,
        OffsetDateTime actualRecordedAt,
        BigDecimal variance,
        boolean closed,
        OffsetDateTime closedAt,
        OffsetDateTime updatedAt) {

    public TripCostView {
        components = List.copyOf(components);
    }

    /** A trip nobody has priced: no card matched it, or nobody has asked yet. */
    public static TripCostView none(UUID tripId) {
        return new TripCostView(tripId, false, null, null, null, null, null, null, null, true, List.of(),
                null, null, null, null, null, false, null, null);
    }

    public static TripCostView from(TripCost cost) {
        List<TripCostComponent> components = cost.components();
        return new TripCostView(
                cost.tripId(),
                true,
                cost.currency(),
                cost.estimatedAmount(),
                cost.estimatedAt(),
                cost.rateCardId(),
                cost.rateCardCode(),
                cost.rateCardName(),
                cost.rateCardScope(),
                components.stream().allMatch(TripCostComponent::isApplied),
                components.stream().map(TripCostComponentView::from).toList(),
                cost.actualAmount(),
                cost.actualReference(),
                cost.actualNotes(),
                cost.actualRecordedAt(),
                cost.variance(),
                cost.isClosed(),
                cost.closedAt(),
                cost.updatedAt());
    }
}
