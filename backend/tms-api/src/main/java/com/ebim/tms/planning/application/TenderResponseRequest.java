package com.ebim.tms.planning.application;

import jakarta.validation.constraints.Size;

/**
 * What the carrier said, recorded by a person in the TMS UI after a phone call or a mail.
 *
 * <p>One record for both answers rather than one each, because the field is the same field: on an
 * acceptance it is optional colour ("they will send the 12t, not the 8t"), on a rejection it is
 * required and is the reason. {@code TripTenderService.reject} enforces the requirement with a
 * sentence, {@code TripTender.reject} asserts it again, and
 * {@code ck_trip_tender_rejection_has_reason} is the backstop - the three-layer shape this module
 * uses for every rule that a CHECK can express.
 *
 * <p>Carries no timestamp. Unlike an execution transition, where the operator's own time is the
 * whole point ({@code TripExecutionRequest}), a response is recorded when it is received: there is
 * no fleet fact here that happened earlier and is being written up later, and letting a clerk
 * backdate an acceptance would weaken the one record a dispute turns on.
 */
public record TenderResponseRequest(
        @Size(max = 1000, message = "must be at most 1000 characters") String notes) {
}
