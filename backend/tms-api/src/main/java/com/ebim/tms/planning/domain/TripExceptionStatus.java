package com.ebim.tms.planning.domain;

/**
 * Whether somebody has dealt with a {@link TripException} yet. Mirrors
 * {@code ck_trip_exception_status} (migration V27).
 *
 * <p>Two states and deliberately nothing else. No assignment, no severity, no escalation ladder,
 * no SLA clock: no rule in TMS reads one, and a workflow engine invented ahead of the first
 * customer who needs it is the failure mode this schema has avoided repeatedly. The one question
 * the two states answer - "what went wrong today that nobody has closed out" - is a real question
 * with a real reader, which is what earns them their place.
 */
public enum TripExceptionStatus {
    OPEN,
    RESOLVED
}
