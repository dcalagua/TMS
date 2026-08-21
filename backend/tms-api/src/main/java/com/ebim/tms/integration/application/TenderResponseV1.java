package com.ebim.tms.integration.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A carrier's answer to one offer, API version 1 (migration V31).
 *
 * <p>The shipment is not in the body - it is the path - and neither is the carrier, which comes from
 * the credential. What is left is the decision and the words, which is everything a carrier is
 * entitled to decide.
 *
 * @param decision {@code "ACCEPTED"} or {@code "REJECTED"}, upper-cased by the service. A string
 *     against a pattern rather than an enum bound at deserialisation, so a client sending
 *     {@code "MAYBE"} gets a 400 naming the field and the two legal values instead of a Jackson
 *     message about a coercion failure - the shape every other inbound contract in this API uses
 * @param reason the carrier's own words. Required on {@code REJECTED} - a refusal with no reason is
 *     the answer that helps the shipper's planner least, exactly when they have to decide what to
 *     do next - and optional colour on {@code ACCEPTED} ("we will send the 12t, not the 8t")
 */
public record TenderResponseV1(
        @NotNull(message = "is required")
        @Pattern(regexp = "^(ACCEPTED|REJECTED)$", flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "must be ACCEPTED or REJECTED")
        String decision,

        @Size(max = 1000, message = "must be at most 1000 characters") String reason) {
}
