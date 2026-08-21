package com.ebim.tms.orders.domain;

import java.util.Locale;

/**
 * Formats {@code tms.transport_order_number_seq} values into the order number an operator sees.
 *
 * <p>One definition, because there are now two callers - the manual API and the bulk import -
 * and an order number that differs in shape depending on how the order arrived would be a
 * distinction the domain does not have.
 */
public final class OrderNumbers {

    private static final String PREFIX = "TO-";

    /** Eight digits: 100 million orders before the format widens, at ~10k a day roughly 27 years. */
    private static final String PATTERN = "%08d";

    private OrderNumbers() {}

    public static String format(long sequenceValue) {
        return PREFIX + String.format(Locale.ROOT, PATTERN, sequenceValue);
    }
}
