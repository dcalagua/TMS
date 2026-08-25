package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.security.CompanyScope;
import java.util.List;
import java.util.UUID;

/**
 * How a carrier answers for itself, without {@code com.ebim.tms.integration} depending on
 * {@code com.ebim.tms.planning} (migration V31). Implemented by
 * {@code planning.infrastructure.CarrierTenderAdapter}.
 *
 * <p><b>The carrier id is a parameter of every method, and that is the security model.</b> It is
 * resolved server-side from the authenticated credential ({@code integration_client.carrier_id})
 * and never from a payload, so a carrier's key can address its own offers and no others. A
 * credential with no carrier bound to it cannot call these methods at all - the endpoint refuses
 * before it gets here, rather than falling back to the company and handing one partner every
 * carrier's tenders.
 *
 * <p>Company <em>and</em> carrier on every call, never carrier alone: two companies may both
 * subcontract to the same haulier, and a credential is bound to exactly one of them. Filtering by
 * carrier without the tenant would be the one query in TMS that could cross companies.
 */
public interface CarrierTenderPort {

    /**
     * The offers this carrier has been made and not yet answered, oldest first - the order a queue
     * is worked in.
     *
     * <p>Lapsed offers are excluded rather than reported as expired: this is a work list, and an
     * offer the carrier can no longer accept is not work. What happened to it is answerable from
     * the shipment event feed, which is where a partner reconciling state looks.
     */
    List<CarrierTenderOffer> findOpenOffers(CompanyScope scope, UUID carrierId, String shipmentNumber);

    /**
     * Records the carrier's own answer to the offer outstanding on one shipment.
     *
     * <p>Idempotent in the way that matters to an at-least-once sender: re-sending the <em>same</em>
     * decision for an already-answered tender returns the recorded answer unchanged rather than
     * failing, so a carrier whose request timed out is not left unable to confirm. Sending the
     * <em>opposite</em> decision is refused - "we accepted and now we reject" is a withdrawal of a
     * commitment, not a retry, and it needs a person.
     *
     * @param accepted true for yes, false for no
     * @param notes the carrier's own words. Required when {@code accepted} is false - a rejection
     *     with no reason is the answer that helps the planner least
     * @param integrationClientId the credential that answered, recorded on the tender so an audit
     *     reader can follow one acceptance back to the key that signed it
     * @throws com.ebim.tms.shared.api.ResourceNotFoundException when this carrier has no tender on
     *     that shipment number in this company - the same answer a shipment that does not exist
     *     gets, so a carrier cannot probe for the shipper's other shipments
     * @throws com.ebim.tms.shared.api.ConflictException when the offer is no longer answerable:
     *     lapsed, withdrawn, or already answered the other way
     */
    CarrierTenderOffer respond(CompanyScope scope, UUID carrierId, String shipmentNumber, boolean accepted,
            String notes, UUID integrationClientId);
}
