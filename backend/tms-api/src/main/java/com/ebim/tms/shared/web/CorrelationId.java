package com.ebim.tms.shared.web;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Per-request trace identifier.
 *
 * <p>Held in SLF4J's {@link MDC} so that every log line of a request carries it without any
 * plumbing through method signatures, and read back by the error factory and the audit actor
 * context. {@link CorrelationIdFilter} is the only writer.
 *
 * <p>The value is never trusted as data: it is sanitised to a bounded, printable token
 * before it reaches a log line or a response header, because it arrives from the client.
 */
public final class CorrelationId {

    static final String MDC_KEY = "correlationId";

    /** Long enough for a UUID, short enough to keep a log line and a header sane. */
    static final int MAX_LENGTH = 64;

    private CorrelationId() {}

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * Accepts a client-supplied value only if it is a bounded token of characters that are
     * safe in a header and a log line; otherwise a fresh identifier is generated. This blocks
     * header injection and log forging through {@code X-Correlation-Id}.
     */
    static String sanitize(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_LENGTH) {
            return generate();
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == ':';
            if (!allowed) {
                return generate();
            }
        }
        return candidate;
    }

    static void set(String value) {
        MDC.put(MDC_KEY, value);
    }

    static void clear() {
        MDC.remove(MDC_KEY);
    }
}
