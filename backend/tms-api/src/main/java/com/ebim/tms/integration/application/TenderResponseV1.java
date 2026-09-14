package com.ebim.tms.integration.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A carrier's answer to one offer, API version 1 (migration V31).
 *
 * <p>The shipment is not in the body - it is the path - and neither is the carrier, which comes from
 * the credential. What is left is the decision, the words, and which offer is being answered.
 *
 * <h2>Why {@code attempt} exists</h2>
 *
 * <p>A shipment can be offered to the same carrier more than once: they refuse, the waterfall moves
 * on, and a planner comes back to them later with a fresh offer carrying a higher {@code attempt}.
 * Until this field existed, the only identity this contract had was
 * {@code (carrier, shipment, decision)} - which is not an identity at all once two offers on the
 * same shipment have been made to the same carrier. A redelivery of the first "REJECTED", arriving
 * after the second offer went out, is indistinguishable from an answer to the second one, and
 * {@code planning} resolves that ambiguity in favour of the newest offer. The retry would kill a
 * live offer that nobody had answered.
 *
 * <p>{@code attempt} is what closes it, and it is the number TMS itself put in the
 * {@link TenderOfferV1} the carrier read. Echoing it back turns a repeated POST into something the
 * server can check rather than guess at.
 *
 * @param decision {@code "ACCEPTED"} or {@code "REJECTED"}, upper-cased by the service. A string
 *     against a pattern rather than an enum bound at deserialisation, so a client sending
 *     {@code "MAYBE"} gets a 400 naming the field and the two legal values instead of a Jackson
 *     message about a coercion failure - the shape every other inbound contract in this API uses
 * @param reason the carrier's own words. Required on {@code REJECTED} - a refusal with no reason is
 *     the answer that helps the shipper's planner least, exactly when they have to decide what to
 *     do next - and optional colour on {@code ACCEPTED} ("we will send the 12t, not the 8t")
 * @param attempt which offer this answers, copied from {@link TenderOfferV1#attempt()}. Optional so
 *     that no integration written against V31 stops working, but a sender that omits it is taking
 *     the risk above on itself - {@code IntegrationTenderService} can only refuse a stale answer it
 *     was given the means to recognise
 */
public record TenderResponseV1(
        @NotNull(message = "is required")
        @Pattern(regexp = "^(ACCEPTED|REJECTED)$", flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "must be ACCEPTED or REJECTED")
        String decision,

        @Size(max = 1000, message = "must be at most 1000 characters") String reason,

        @Min(value = 1, message = "must be 1 or greater") Integer attempt) {

    /**
     * The shape this record had before {@code attempt} was added.
     *
     * <p>Not for wire use - Jackson binds the canonical constructor and fills a missing
     * {@code attempt} with null on its own. It exists so that the tests and callers written against
     * the two-component record keep compiling, and so that "the sender did not say" has exactly one
     * representation.
     */
    public TenderResponseV1(String decision, String reason) {
        this(decision, reason, null);
    }
}
