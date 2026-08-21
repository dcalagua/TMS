package com.ebim.tms.rates.application;

import com.ebim.tms.rates.domain.RateCardScope;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The optional list filters for {@code GET /rates/rate-cards}, bound alongside {@code PageQuery}.
 *
 * @param onDate keeps only the cards in force on that day. The one filter here that is not a plain
 *     equality, and the one a commercial manager reaches for first: "what am I paying today".
 */
public record RateCardFilter(
        String code,
        String name,
        UUID carrierId,
        RateCardScope scope,
        UUID vehicleTypeId,
        String currency,
        LocalDate onDate,
        Boolean active) {
}
