package com.ebim.tms.planning.domain;

/** How one order of a dispatch document compares with the trip (V53 ck_external_dispatch_order_match). */
public enum ExternalDispatchOrderMatch {
    MATCHED,
    VARIANCE,
    UNCOMPARABLE,
    /** A known order that is not on this trip. */
    EXTRA,
    /** No order of the company has this key. */
    UNKNOWN
}
