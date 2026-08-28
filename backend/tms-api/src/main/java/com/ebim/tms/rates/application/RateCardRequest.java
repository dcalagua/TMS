package com.ebim.tms.rates.application;

import com.ebim.tms.rates.domain.RateCardScope;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Create and update share one shape, following {@code CarrierRequest}.
 *
 * <p>{@code carrierId} is accepted on both and enforced to be unchanged on update
 * ({@code RateCardService}): a card is an agreement with one counterparty, and re-pointing it at
 * another would silently restate every estimate that cites it. It stays in the update body rather
 * than being dropped from it so that a client sending the object it just read back is answered
 * with a clear refusal instead of having a field it supplied quietly ignored.
 *
 * <p>The scope trio ({@code scope}, {@code originId}, {@code routeId}) is validated in the service
 * and not with annotations, because the rule is conditional - exactly one target, decided by
 * {@code scope} - and a cross-field bean-validation constraint expresses that far less clearly
 * than the four lines that check it.
 *
 * <p>Every amount is optional and at least one component is required; the same is true in the
 * database ({@code ck_rate_card_has_a_component}). Null means "this card does not charge for it",
 * which is deliberately different from {@code 0}, "it charges nothing for it" - see
 * {@code RateComponents}.
 */
public record RateCardRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$",
                message = "must be 1-32 characters: letters, digits, underscore or hyphen") String code,
        @NotBlank @Size(max = 200) String name,
        @NotNull UUID carrierId,
        @NotNull RateCardScope scope,
        UUID originId,
        /** The lane's far end. Required for scope LANE, forbidden for every other scope (V39). */
        UUID destinationId,
        UUID routeId,
        UUID vehicleTypeId,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter ISO 4217 code") String currency,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal baseAmount,
        @DecimalMin("0.0000") @Digits(integer = 10, fraction = 4) BigDecimal amountPerKm,
        @DecimalMin("0.0000") @Digits(integer = 10, fraction = 4) BigDecimal amountPerKg,
        @DecimalMin("0.0000") @Digits(integer = 10, fraction = 4) BigDecimal amountPerM3,
        @DecimalMin("0.0000") @Digits(integer = 10, fraction = 4) BigDecimal amountPerPallet,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal minimumAmount,
        // The V39 charges.
        @DecimalMin("0.0000") @Digits(integer = 10, fraction = 4) BigDecimal amountPerStop,
        @DecimalMin("0.0000") @DecimalMax("100.0000") @Digits(integer = 3, fraction = 4)
                BigDecimal fuelSurchargePercent,
        @DecimalMin("0.0000") @Digits(integer = 10, fraction = 4) BigDecimal amountPerWaitingHour,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal tollAmount,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal accessorialAmount,
        @Size(max = 120) String accessorialLabel,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal maximumAmount) {

    /**
     * The pre-V39 shape: no lane and none of the new charges.
     *
     * <p>Kept so that a caller pricing on base and distance alone need not name seven components it
     * does not use. A rate card with twenty-four fields is a real contract; a request literal with
     * twenty-four nulls is noise around the two that matter.
     */
    public RateCardRequest(String code, String name, UUID carrierId, RateCardScope scope, UUID originId,
            UUID routeId, UUID vehicleTypeId, String currency, LocalDate validFrom, LocalDate validTo,
            BigDecimal baseAmount, BigDecimal amountPerKm, BigDecimal amountPerKg, BigDecimal amountPerM3,
            BigDecimal amountPerPallet, BigDecimal minimumAmount) {
        this(code, name, carrierId, scope, originId, null, routeId, vehicleTypeId, currency, validFrom,
                validTo, baseAmount, amountPerKm, amountPerKg, amountPerM3, amountPerPallet, minimumAmount,
                null, null, null, null, null, null, null);
    }
}
