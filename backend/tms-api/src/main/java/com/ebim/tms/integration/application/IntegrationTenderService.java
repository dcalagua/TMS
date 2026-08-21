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
     */
    public IntegrationOutcome<TenderOfferV1> respond(IntegrationPrincipal principal,
            TenderResponseEnvelope delivery) {
        TenderResponseV1 request = delivery.response();
        boolean accepted = "ACCEPTED".equals(request.decision().trim().toUpperCase(Locale.ROOT));
        CarrierTenderOffer answered = carrierTenderPort.respond(principal.companyScope(),
                requireCarrier(principal), delivery.shipmentNumber(), accepted, request.reason(), principal.id());
        return IntegrationOutcome.single(TenderOfferV1.from(answered), 200, null,
                principal.clientId(), answered.shipmentNumber());
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
