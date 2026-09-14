package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.IntegrationScope;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.reference.CarrierTenderOffer;
import com.ebim.tms.shared.reference.CarrierTenderPort;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The carrier's side of tendering, API version 1 (migration V31): the offers one carrier is holding,
 * and their answer to one of them.
 *
 * <p>Thin on purpose. Every rule about what may be answered and when lives in {@code planning}
 * behind {@link CarrierTenderPort}, so the M2M path and the UI path cannot diverge on a single one
 * of them - which is why there is one service in {@code planning} and not a second copy here. What
 * this class owns is the wire contract: turning a {@code decision} string into a boolean, and a
 * {@link CarrierTenderOffer} into the {@link TenderOfferV1} a partner's ERP is entitled to keep
 * parsing.
 *
 * <p><b>The carrier is never read from the payload.</b> It comes from
 * {@link IntegrationPrincipal#carrierId()}, which the authenticator resolved from the credential -
 * the same discipline that makes the company unspoofable. A credential holding the scope with no
 * carrier bound to it is refused here with a message an administrator can act on, rather than
 * falling back to the company and handing one partner every carrier's offers.
 */
@Service
public class IntegrationTenderService {

    private final CarrierTenderPort carrierTenderPort;

    public IntegrationTenderService(CarrierTenderPort carrierTenderPort) {
        this.carrierTenderPort = carrierTenderPort;
    }

    /**
     * The offers this carrier is holding and can still answer, oldest first.
     *
     * <p>Not paginated, and that is a decision rather than an omission: the result is bounded by the
     * offers one carrier has outstanding right now, which is a working queue and not a history. A
     * carrier with two hundred unanswered tenders has an operational problem that a page boundary
     * would hide rather than solve.
     */
    public List<TenderOfferV1> openOffers(IntegrationPrincipal principal, String shipmentNumber) {
        return carrierTenderPort
                .findOpenOffers(principal.companyScope(), requireCarrier(principal), shipmentNumber).stream()
                .map(TenderOfferV1::from)
                .toList();
    }

    /**
     * Records this carrier's answer.
     *
     * <p>Wrapped in an {@link IntegrationOutcome} so the delivery lands in the integration inbox
     * like every other inbound write: what a carrier accepted, and when, is exactly the delivery a
     * support engineer will be asked to reconstruct from the database alone.
     *
     * <p>Always {@code single}: one answer, one shipment, one business row. The
     * {@code externalReference} is the shipment number - the only identity this contract has - so
     * the inbox can be searched by it.
     *
     * <p>{@link #requireAnswersTheOfferInFront} runs first when the sender named an attempt; see
     * there for what it is defending against and what it cannot defend against.
     */
    public IntegrationOutcome<TenderOfferV1> respond(IntegrationPrincipal principal,
            TenderResponseEnvelope delivery) {
        TenderResponseV1 request = delivery.response();
        UUID carrierId = requireCarrier(principal);
        requireAnswersTheOfferInFront(principal, carrierId, delivery);

        boolean accepted = "ACCEPTED".equals(request.decision().trim().toUpperCase(Locale.ROOT));
        CarrierTenderOffer answered = carrierTenderPort.respond(principal.companyScope(),
                carrierId, delivery.shipmentNumber(), accepted, request.reason(), principal.id());
        return IntegrationOutcome.single(TenderOfferV1.from(answered), 200, null,
                principal.clientId(), answered.shipmentNumber());
    }

    /**
     * Refuses an answer aimed at an offer that is no longer the one outstanding.
     *
     * <h2>The redelivery this exists for</h2>
     *
     * <p>A shipment may be offered to the same carrier twice. Carrier A refuses attempt 1, the
     * waterfall moves down the list, and days later a planner comes back to A with attempt 3, which
     * sits {@code SENT}. If A's own middleware now redelivers the attempt-1 "REJECTED" - a queue
     * drained late, a replayed webhook, an operator pressing resend - {@code planning} resolves
     * "this carrier's tender on this shipment" to the <em>highest attempt</em>, which is attempt 3.
     * The retry does not repeat its old effect; it lands a brand new one on a live offer nobody
     * answered. That is the sharpest edge of "an external retry must not duplicate an effect",
     * because here it does not even duplicate - it destroys.
     *
     * <p>Neither {@code (carrier, shipment)} nor the decision distinguishes the two, and
     * {@code Idempotency-Key} only helps a sender that reuses the same key on the redelivery, which
     * is exactly the sender that was never the problem. The missing term is the attempt, so the
     * contract now carries it.
     *
     * <h2>What this check is, honestly</h2>
     *
     * <p>A wire-contract guard, not an invariant. It reads the offer queue and then calls a separate
     * transaction, so a re-offer landing between the two would slip through the window - and it is
     * silent for a sender that omits {@code attempt} at all. The invariant version is one line in
     * {@code planning.TripTenderService.respondAsCarrier}, inside the transaction that already holds
     * the trip lock: select the tender by attempt instead of by {@code attempt DESC}. That file is
     * not this module's to edit, so the proposal travels with this commit rather than the code.
     *
     * <p>Absent from the queue is not refused. An offer already answered is not open, and that is
     * the case {@code planning} handles correctly by itself: the same decision returns the recorded
     * answer, the opposite decision is a 409. Refusing here would break the genuine retry this
     * endpoint was built to absorb.
     */
    private void requireAnswersTheOfferInFront(IntegrationPrincipal principal, UUID carrierId,
            TenderResponseEnvelope delivery) {
        Integer declared = delivery.response().attempt();
        if (declared == null) {
            return;
        }
        List<CarrierTenderOffer> open = carrierTenderPort.findOpenOffers(principal.companyScope(), carrierId,
                delivery.shipmentNumber());
        if (open.isEmpty() || open.stream().anyMatch(offer -> offer.attempt() == declared)) {
            return;
        }
        // The attempt number is not a disclosure: it is the value TMS put in the offer this carrier
        // read from GET /integration/v1/tenders, and only offers made to them are ever there.
        throw new ConflictException("This answer names attempt " + declared + " on shipment "
                + delivery.shipmentNumber() + ", but the offer outstanding for this carrier is attempt "
                + open.getFirst().attempt() + ". Re-read the offer and answer that one; a late redelivery of "
                + "an older answer must not be applied to a newer offer.");
    }

    /**
     * A {@link ConflictException} and not a 403: the credential <em>is</em> allowed to answer
     * tenders - the scope check on the controller already passed - it just has no carrier to answer
     * for. That is a misconfiguration on the shipper's side, and telling a partner "forbidden" for
     * a mistake their counterparty made would send them looking in the wrong place.
     */
    private static UUID requireCarrier(IntegrationPrincipal principal) {
        if (!principal.speaksForACarrier()) {
            throw new ConflictException("This credential holds " + IntegrationScope.TENDER_RESPOND.code()
                    + " but is not bound to a carrier. Ask the shipper to complete its configuration.");
        }
        return principal.carrierId();
    }
}
