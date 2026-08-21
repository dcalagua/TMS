package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TenderResponseSource;
import com.ebim.tms.planning.domain.TenderStatus;
import com.ebim.tms.planning.domain.TripTender;
import com.ebim.tms.shared.reference.MasterReference;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * One attempt to place a shipment with a carrier, as a screen and an API read it (migration V31).
 *
 * <p><b>{@code status} is the effective status, never the stored one.</b> A sent offer past its
 * deadline reports {@code EXPIRED} here even while the column still says {@code SENT}, because
 * there is no scheduler to materialise the lapse and a screen showing a dead offer as live would be
 * the one place this feature could mislead somebody. The lag and its cost are documented in
 * migration V31, section 1b; {@code expiresAt} is carried so a screen can show the countdown that
 * produced the answer.
 *
 * @param carrierName resolved from {@code carrierId} through {@code CarrierLookupPort}, active or
 *     not: a carrier deactivated after being offered a shipment must still render on the offer they
 *     were made
 * @param respondedByClient set when the carrier's own credential answered over the M2M API, null
 *     when a person recorded the answer. Paired with {@code responseSource}, which is the field a
 *     screen actually shows - the credential's id is here for an audit reader following one answer
 *     back to the key that signed it
 * @param allowedTransitions the states this attempt may still move to, decided by
 *     {@code TenderStatus}'s transition table <em>after</em> the deadline has been applied, and
 *     sent to the browser so a screen renders the buttons that work instead of keeping a second
 *     copy of the lifecycle in TypeScript. Never authorization: the server re-checks every move it
 *     is asked to make
 */
public record TripTenderView(
        UUID id,
        UUID tripId,
        int attempt,
        TenderStatus status,
        UUID carrierId,
        String carrierName,
        BigDecimal offeredAmount,
        String currency,
        String notes,
        OffsetDateTime expiresAt,
        OffsetDateTime sentAt,
        OffsetDateTime respondedAt,
        TenderResponseSource responseSource,
        UUID respondedByClient,
        String responseNotes,
        OffsetDateTime expiredAt,
        OffsetDateTime cancelledAt,
        String cancelReason,
        Set<TenderStatus> allowedTransitions,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param now the instant the deadline is judged against - passed in rather than read here so
     *     that a whole list of attempts is rendered against one consistent moment, and so a test can
     *     choose it
     */
    public static TripTenderView from(TripTender tender, MasterReference carrier, OffsetDateTime now) {
        TenderStatus effective = tender.effectiveStatus(now);
        return new TripTenderView(
                tender.id(),
                tender.tripId(),
                tender.attempt(),
                effective,
                tender.carrierId(),
                carrier == null ? null : carrier.name(),
                tender.offeredAmount(),
                tender.currency(),
                tender.notes(),
                tender.expiresAt(),
                tender.sentAt(),
                tender.respondedAt(),
                tender.responseSource(),
                tender.respondedByClient(),
                tender.responseNotes(),
                tender.expiredAt(),
                tender.cancelledAt(),
                tender.cancelReason(),
                effective.allowedTransitions(),
                tender.createdAt(),
                tender.updatedAt());
    }
}
