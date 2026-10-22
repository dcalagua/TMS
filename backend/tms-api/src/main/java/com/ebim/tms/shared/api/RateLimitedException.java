package com.ebim.tms.shared.api;

/**
 * An anonymous caller exceeded a rate limit (ADR-029: the public tracking link). Answered
 * {@code 429 rate-limited} with a {@code Retry-After} header, so a well-behaved client waits instead
 * of retrying in a loop.
 */
public class RateLimitedException extends ProblemTypedException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(ProblemType.RATE_LIMITED, "Too many requests. Try again later.");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
