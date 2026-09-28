package com.ebim.tms.integration.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * A dispatch document with the body it arrived in. The document is what is validated and
 * fingerprinted (so the same content re-sent with other whitespace is the same delivery); the raw
 * body is what is stored (ADR-013: the document as received, not re-serialised).
 */
public record DispatchConfirmationDelivery(@Valid @NotNull DispatchConfirmationV1 document,
        @com.fasterxml.jackson.annotation.JsonIgnore String rawBody) {
}
