package com.ebim.tms.settlement.application;

import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.settings.PaymentTermsSettingsPort;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The payment terms an invoice is approved under (V67, ADR-023 section 2, D-06): the carrier's own,
 * else the company's default, else {@code tms.settlement.default-payment-terms-days}. No number of days
 * is a literal in Java or SQL.
 */
@Component
public class PaymentTermsResolver {

    private final CarrierLookupPort carrierLookupPort;
    private final PaymentTermsSettingsPort settingsPort;
    private final int propertyDefault;

    public PaymentTermsResolver(CarrierLookupPort carrierLookupPort, PaymentTermsSettingsPort settingsPort,
            @Value("${tms.settlement.default-payment-terms-days}") int propertyDefault) {
        if (propertyDefault < 0 || propertyDefault > 365) {
            throw new IllegalStateException("tms.settlement.default-payment-terms-days must be 0 to 365");
        }
        this.carrierLookupPort = carrierLookupPort;
        this.settingsPort = settingsPort;
        this.propertyDefault = propertyDefault;
    }

    public int termsDaysFor(UUID companyId, UUID carrierId) {
        return carrierLookupPort.paymentTermsDaysOf(carrierId, companyId)
                .or(() -> settingsPort.defaultPaymentTermsDaysOf(companyId))
                .orElse(propertyDefault);
    }
}
