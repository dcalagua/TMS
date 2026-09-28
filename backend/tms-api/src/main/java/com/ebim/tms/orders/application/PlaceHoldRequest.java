package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.HoldType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Placing a hold on an order.
 *
 * @param reasonCode an optional short code ("CREDIT_LIMIT"), upper case, as V54's
 *                   {@code ck_order_hold_reason_code_shape} requires
 * @param blocking   null means true: a hold stops release, planning and dispatch unless the person
 *                   placing it says it is only a note
 */
public record PlaceHoldRequest(
        @NotNull HoldType holdType,
        @Size(max = 64) @Pattern(regexp = "^[A-Z0-9][A-Z0-9_.-]{0,63}$",
                message = "must be upper case letters, digits, '_', '.' or '-'") String reasonCode,
        @NotBlank @Size(max = 500) String reason,
        Boolean blocking) {

    public boolean isBlocking() {
        return blocking == null || blocking;
    }
}
