package com.ebim.tms.orders.domain;

import com.ebim.tms.shared.settings.CompanySettings;
import java.util.Locale;

/**
 * Formats {@code tms.transport_order_number_seq} values into the order number an operator sees.
 *
 * <p>One definition, because there are now two callers - the manual API and the bulk import -
 * and an order number that differs in shape depending on how the order arrived would be a
 * distinction the domain does not have.
 *
 * <p>The prefix became per-company with migration V34 ({@code tms.company_settings
 * .order_number_prefix}), so a tenant can have its own document series. The digits after it did not:
 * they come from one installation-wide sequence, which is what makes the value unique regardless of
 * what any company chooses as a prefix - see V34 section 3.
 */
public final class OrderNumbers {

    /** Eight digits: 100 million orders before the format widens, at ~10k a day roughly 27 years. */
    private static final String PATTERN = "%08d";

    private OrderNumbers() {}

    /**
     * @param prefix the company's {@code orderNumberPrefix}; blank or null falls back to
     *     {@link CompanySettings#DEFAULT_ORDER_NUMBER_PREFIX}, which is what every order carried
     *     before V34
     */
    public static String format(String prefix, long sequenceValue) {
        String resolved = (prefix == null || prefix.isBlank())
                ? CompanySettings.DEFAULT_ORDER_NUMBER_PREFIX
                : prefix.trim();
        return resolved + String.format(Locale.ROOT, PATTERN, sequenceValue);
    }
}
