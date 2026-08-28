package com.ebim.tms.costing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.reference.TransportCostComparison;
import com.ebim.tms.shared.reference.TransportCostComparison.Outcome;
import com.ebim.tms.shared.reference.TransportCostNature;
import com.ebim.tms.shared.reference.TransportCostQuote;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** When a carrier's price and our own cost can be held against each other, and when they cannot. */
class TransportCostComparisonTest {

    private static TransportCostQuote carrier(String amount, String currency) {
        return new TransportCostQuote(TransportCostNature.EXTERNAL_CARRIER_PRICE,
                amount == null ? null : new BigDecimal(amount), currency, "Transportes Lima");
    }

    private static TransportCostQuote ownFleet(String amount, String currency) {
        return new TransportCostQuote(TransportCostNature.OWN_FLEET_INTERNAL_COST,
                amount == null ? null : new BigDecimal(amount), currency, "Own fleet");
    }

    @Test
    @DisplayName("same currency, both costed: the cheaper one wins and is flagged as unlike-for-unlike")
    void comparesInOneCurrency() {
        var result = TransportCostComparison.compare(List.of(carrier("1430.00", "PEN"), ownFleet("1210.00", "PEN")));

        assertThat(result.outcome()).isEqualTo(Outcome.COMPARED);
        assertThat(result.cheapest().nature()).isEqualTo(TransportCostNature.OWN_FLEET_INTERNAL_COST);
        // The number won, and the screen must still say what kind of number it is: our estimate has
        // no margin in it and the carrier's price does.
        assertThat(result.comparesCostAgainstPrice()).isTrue();
    }

    @Test
    @DisplayName("different currencies are reported incomparable, not converted")
    void refusesToConvert() {
        var result = TransportCostComparison.compare(List.of(carrier("1430.00", "PEN"), ownFleet("320.00", "USD")));

        // 320 USD is plainly more than 1430 PEN at any rate this decade, so a naive min() would have
        // picked the wrong option AND looked right. TMS holds no rate and will not guess one.
        assertThat(result.outcome()).isEqualTo(Outcome.INCOMPARABLE_CURRENCY);
        assertThat(result.cheapest()).isNull();
        assertThat(result.options()).hasSize(2);
    }

    @Test
    @DisplayName("an uncosted option takes no part and does not win")
    void uncostedDoesNotWin() {
        var result = TransportCostComparison.compare(List.of(carrier("1430.00", "PEN"), ownFleet(null, "PEN")));

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_ENOUGH_COSTED_OPTIONS);
        assertThat(result.cheapest()).isNull();
    }

    @Test
    @DisplayName("an exact tie names neither")
    void tie() {
        var result = TransportCostComparison.compare(List.of(carrier("1000.00", "PEN"), ownFleet("1000.00", "PEN")));

        assertThat(result.outcome()).isEqualTo(Outcome.TIED);
    }

    @Test
    @DisplayName("two carrier prices compared are like for like")
    void twoPricesAreLikeForLike() {
        var result = TransportCostComparison.compare(List.of(carrier("1430.00", "PEN"), carrier("1290.00", "PEN")));

        assertThat(result.outcome()).isEqualTo(Outcome.COMPARED);
        assertThat(result.comparesCostAgainstPrice()).isFalse();
    }

    @Test
    @DisplayName("a quote can never be built with a negative amount")
    void noNegativeQuotes() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ownFleet("-1.00", "PEN"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
