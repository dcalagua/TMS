package com.ebim.tms.shared.api;

/**
 * A failure that already knows which problem type it is.
 *
 * <p>The exceptions above this one each mean one thing - not found, conflict, invalid request - and
 * the handler maps each to its type. That works while a module's failures fall into those buckets.
 * The control-plane provisioning API does not: "this tenant already exists", "that administrator
 * email belongs to somebody else" and "the identifiers clash" are all conflicts, and a control plane
 * reacts to each differently. Rather than give each a class whose only content is the constant, they
 * carry the constant.
 *
 * <p>Lives in {@code shared.api} because {@code ApiExceptionHandler} has to handle it, and
 * {@code shared} must never depend on a business module ({@code ModuleBoundaryTest}).
 */
public class ProblemTypedException extends RuntimeException {

    private final transient ProblemType type;

    public ProblemTypedException(ProblemType type, String message) {
        super(message);
        this.type = type;
    }

    public ProblemTypedException(ProblemType type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    public ProblemType type() {
        return type;
    }
}
