package com.ebim.tms.planning.domain;

/** The two things that happen to a tracked document (ADR-018, V57). */
public enum DocumentDeliveryEventType {

    /** The carrier reported an outcome, or corrected one. */
    REPORTED,

    /** The office confirmed the signed copy is back. */
    RETURNED
}
