package com.ebim.tms.integration.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The delivery {@code IntegrationRequestExecutor} fingerprints for a tender response: the shipment
 * <em>and</em> the answer, together.
 *
 * <p>Not a wire type - a partner never sends this shape, and no field name here is a published
 * promise. It exists because {@link TenderResponseV1} alone would be the wrong thing to hash.
 *
 * <p><b>Why the shipment number has to be in the fingerprint.</b> An {@code Idempotency-Key} is
 * unique per (company, credential, operation) and the executor compares the <em>payload hash</em>
 * to decide whether a repeated key is a retry or a mistake. The shipment travels in the path, so
 * hashing the body alone would make two answers that differ only in which shipment they are about
 * indistinguishable: a carrier that reused one key to accept SH-1 and then SH-2 would be replayed
 * SH-1's response and told SH-2 was accepted when nothing had been written. Wrapping the two
 * together makes that case a 409 naming the reused key, which is what the mechanism is for.
 *
 * <p>Validation cascades through {@code @Valid}, so {@link TenderResponseV1}'s constraints still
 * report against their own field names - {@code ApiExceptionHandler.lastNode} keeps the last path
 * segment, so a partner sees {@code decision}, not {@code response.decision}.
 */
public record TenderResponseEnvelope(
        @NotBlank(message = "is required") String shipmentNumber,
        @NotNull(message = "is required") @Valid TenderResponseV1 response) {
}
