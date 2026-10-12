package com.ebim.tms.planning.domain;

/** Why a document was rejected or not delivered (ADR-018, {@code ck_document_delivery_reason}). */
public enum DocumentDeliveryReason {
    CUSTOMER_ABSENT,
    CUSTOMER_REFUSED,
    CUSTOMER_CLOSED,
    ADDRESS_NOT_FOUND,
    DOCUMENT_ERROR,
    GOODS_REJECTED,
    /** Anything else. The remark becomes mandatory, because "other" alone answers nothing. */
    OTHER
}
