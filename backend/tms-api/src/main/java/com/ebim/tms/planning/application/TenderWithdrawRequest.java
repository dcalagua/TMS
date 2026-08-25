package com.ebim.tms.planning.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why an offer is being pulled back.
 *
 * <p>{@code @NotBlank} for the reason {@code TripService.cancel} requires a reason on a confirmed
 * trip: withdrawing something a carrier was told about needs an explanation, and this is the only
 * place it is recorded. A draft that was never sent is withdrawn under the same rule rather than a
 * looser one - it costs a sentence, and a history where half the withdrawals say nothing is a
 * history nobody trusts.
 */
public record TenderWithdrawRequest(
        @NotBlank(message = "is required")
        @Size(max = 500, message = "must be at most 500 characters") String reason) {
}
