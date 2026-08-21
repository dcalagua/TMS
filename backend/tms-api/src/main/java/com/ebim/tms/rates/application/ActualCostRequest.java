package com.ebim.tms.rates.application;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * What the carrier actually charged for one trip.
 *
 * <p>{@code currency} is optional and means different things in the two cases the endpoint has to
 * serve, which is why it is not simply {@code @NotBlank}:
 *
 * <ul>
 *   <li>The trip already has a cost row (almost always, because confirmation estimates it): the
 *       currency is already fixed by the estimate. Sending a different one is refused rather than
 *       converted - see {@code TripCost.currency}.</li>
 *   <li>The trip has no cost row - no rate card covered it - and this is the first figure anyone
 *       has recorded. Then the currency is required, because there is nothing to inherit it from.</li>
 * </ul>
 *
 * @param reference the carrier's invoice or settlement number, free text: TMS is not an
 *     accounts-payable system and must not pretend to validate somebody else's numbering
 */
public record ActualCostRequest(
        @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter ISO 4217 code") String currency,
        @Size(max = 100) String reference,
        @Size(max = 1000) String notes) {
}
