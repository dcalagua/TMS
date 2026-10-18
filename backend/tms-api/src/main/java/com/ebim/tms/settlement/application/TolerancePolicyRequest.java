package com.ebim.tms.settlement.application;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A tolerance policy as written by a person (V64, ADR-024 part 1, item 2).
 *
 * <p>Within the absolute bound <b>or</b> within the percentage - see {@code Tolerance}. At least one
 * bound, and an absolute bound needs its currency: the service says so in a sentence, and V46's
 * CHECKs refuse it underneath.
 *
 * @param carrierId null for the company-wide default; a carrier's policy outranks it. Fixed at
 *                  creation - moving a policy to another carrier is retiring one and creating another
 */
public record TolerancePolicyRequest(
        UUID carrierId,
        @PositiveOrZero(message = "cannot be negative") BigDecimal absoluteAmount,
        @PositiveOrZero(message = "cannot be negative")
        @DecimalMax(value = "100", message = "cannot exceed 100") BigDecimal percentage,
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a three-letter currency code") String currency) {
}
