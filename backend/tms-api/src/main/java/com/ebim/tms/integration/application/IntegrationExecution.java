package com.ebim.tms.integration.application;

/**
 * What the controller needs in order to answer: the body, the status code, and whether this was
 * a replay of an earlier identical delivery rather than a fresh execution.
 *
 * <p>{@code replayed} is surfaced to the caller as {@code X-Idempotent-Replay: true}. A partner
 * that cannot see the difference between "we wrote it" and "we already had it" has no way to
 * verify their own retry logic from their side of the wire.
 */
public record IntegrationExecution<T>(T body, int httpStatus, boolean replayed) {
}
