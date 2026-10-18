package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.tax.WithholdingKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;

/**
 * The company's fiscal overrides, replaced whole (V66, ADR-027, {@code PUT /settlement/fiscal-settings}).
 * A null is "inherit the deployment default"; a withholding kind not listed loses its override.
 *
 * @param fiscalCountry ISO 3166-1 alpha-2; null = the company's default country
 */
public record FiscalSettingsRequest(
        @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter country code") String fiscalCountry,
        @DecimalMin(value = "0.00", message = "cannot be negative")
        @DecimalMax(value = "100.00", inclusive = false, message = "must be below 100")
        @Digits(integer = 3, fraction = 2) BigDecimal vatRate,
        @DecimalMin(value = "0.00", message = "cannot be negative")
        @DecimalMax(value = "100.00", inclusive = false, message = "must be below 100")
        @Digits(integer = 3, fraction = 2) BigDecimal freightVatRate,
        @Valid List<Withholding> withholdings) {

    public record Withholding(
            @NotNull(message = "is required") WithholdingKind kind,
            Boolean enabled,
            @Pattern(regexp = "^[0-9A-Za-z]{1,10}$", message = "must be 1 to 10 letters or digits") String code,
            @DecimalMin(value = "0.00", message = "cannot be negative")
            @DecimalMax(value = "100.00", message = "cannot exceed 100")
            @Digits(integer = 3, fraction = 2) BigDecimal rate,
            @DecimalMin(value = "0.00", message = "cannot be negative")
            @Digits(integer = 12, fraction = 2) BigDecimal thresholdAmount) {
    }
}
