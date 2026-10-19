package com.ebim.tms.settlement.domain;

/** Where a person's payment is in the four-eyes flow (V67, {@code ck_carrier_payment_request_status}). */
public enum PaymentRequestStatus {
    PENDING,
    CONFIRMED,
    REJECTED
}
