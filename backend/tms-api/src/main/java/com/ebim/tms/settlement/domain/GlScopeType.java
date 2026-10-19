package com.ebim.tms.settlement.domain;

/** How narrow a GL mapping is (V65). The most specific wins: ROUTE, then ORIGIN, then COMPANY. */
public enum GlScopeType {
    COMPANY,
    ORIGIN,
    ROUTE
}
