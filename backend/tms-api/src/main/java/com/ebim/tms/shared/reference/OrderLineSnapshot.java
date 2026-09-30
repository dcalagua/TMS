package com.ebim.tms.shared.reference;

import java.math.BigDecimal;

/** One line of an order as TMS holds it, for comparing against what a warehouse dispatched. */
public record OrderLineSnapshot(int lineNumber, String materialCode, BigDecimal quantity, String uom) {
}
