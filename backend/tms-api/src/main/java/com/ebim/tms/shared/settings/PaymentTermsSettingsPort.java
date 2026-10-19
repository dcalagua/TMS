package com.ebim.tms.shared.settings;

import java.util.Optional;
import java.util.UUID;

/**
 * The company's default payment terms ({@code tms.company_settings.default_payment_terms_days}, V67,
 * ADR-023 section 2), read by {@code settlement} for a carrier without terms of its own. Empty when
 * the company has not set one, so the caller falls back to {@code tms.settlement.default-payment-terms-days}.
 *
 * <p>Implemented by {@code iam}, which owns {@code company_settings}; apart from {@link FiscalSettingsPort}
 * because terms are not a tax rule.
 */
public interface PaymentTermsSettingsPort {

    Optional<Integer> defaultPaymentTermsDaysOf(UUID companyId);
}
