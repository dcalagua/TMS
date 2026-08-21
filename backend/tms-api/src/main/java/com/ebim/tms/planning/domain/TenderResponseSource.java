package com.ebim.tms.planning.domain;

/**
 * Who answered a tender. Mirrors {@code ck_trip_tender_response_source} (migration V31).
 *
 * <p>Two values and the same vocabulary {@link TransportEventSource} uses, minus its
 * {@code SYSTEM}: a tender is answered by a party, and TMS is not a party to its own offer. An
 * expiry is not an answer - it has its own state and its own timestamp - so there is no third value
 * for it here.
 *
 * <p>The distinction is evidentiary, not cosmetic. An acceptance typed in by the shipper's own
 * clerk after a phone call and one signed by the carrier's credential are worth different things
 * when the load does not turn up, and a single {@code accepted_by} column that sometimes held a
 * person and sometimes a machine would lose exactly that.
 */
public enum TenderResponseSource {

    /**
     * A person in the TMS UI, recording what the carrier said on the phone or in a mail. Always
     * carries the {@code app_user} who typed it - never the carrier, who has no account here.
     */
    OPERATOR,

    /**
     * The carrier's own credential over the M2M API, identified by the {@code integration_client}
     * it authenticated as. The credential is bound to exactly one carrier
     * ({@code integration_client.carrier_id}), which is what makes this the carrier's own answer
     * rather than somebody's on their behalf.
     */
    INTEGRATION
}
