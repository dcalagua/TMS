package com.ebim.tms.shared.api;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A business invariant refused the write: most commonly a duplicate value in a scope that
 * must be unique (a master data code already used inside the same company).
 *
 * <p>Services check for the conflict before writing whenever practical, so the message can
 * name the offending value. The database's own unique constraint remains the backstop against
 * a race between two concurrent requests; a caller that hits it is still answered with this
 * same exception, translated from {@code DataIntegrityViolationException}, so the response
 * shape never depends on which of the two paths caught the duplicate.
 */
public class ConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Extra problem-detail members, in order; never secrets. Transient: a problem is not serialised Java. */
    private final transient Map<String, Object> properties;

    public ConflictException(String message) {
        this(message, Map.of());
    }

    /**
     * A conflict that carries machine-readable detail beside the sentence - for example the reasons
     * the release gate refused an order (ADR-014), so a client switches on codes, not on prose.
     */
    public ConflictException(String message, Map<String, Object> properties) {
        super(message);
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public Map<String, Object> properties() {
        return properties == null ? Map.of() : properties;
    }
}
