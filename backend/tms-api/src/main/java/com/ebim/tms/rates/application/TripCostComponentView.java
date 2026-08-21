package com.ebim.tms.rates.application;

import com.ebim.tms.rates.domain.CostComponentReason;
import com.ebim.tms.rates.domain.CostComponentStatus;
import com.ebim.tms.rates.domain.CostQuantitySource;
import com.ebim.tms.rates.domain.CostUnit;
import com.ebim.tms.rates.domain.RateComponent;
import com.ebim.tms.rates.domain.TripCostComponent;
import java.math.BigDecimal;

/**
 * One line of an estimate as the API renders it.
 *
 * <p>{@code reason} is a code and not a sentence on purpose: the screen turns it into the
 * operator's language. A message frozen here would be in whichever language the person who ran the
 * estimate happened to be using.
 */
public record TripCostComponentView(
        RateComponent component,
        CostComponentStatus status,
        BigDecimal rate,
        BigDecimal quantity,
        CostUnit unit,
        CostQuantitySource quantitySource,
        BigDecimal amount,
        CostComponentReason reason) {

    public static TripCostComponentView from(TripCostComponent component) {
        return new TripCostComponentView(component.component(), component.status(), component.rate(),
                component.quantity(), component.unit(), component.quantitySource(), component.amount(),
                component.reason());
    }
}
