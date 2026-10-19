package com.ebim.tms.settlement.domain;

/** Who reported a payment (V67, {@code ck_carrier_payment_source}). */
public enum PaymentSource {

    /** The ERP, through {@code POST /integration/v1/payable-payments}, attributed to its credential. */
    INTEGRATION,

    /** A person keyed it and a different person confirmed it. */
    OPERATOR
}
