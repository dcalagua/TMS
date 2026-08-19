package com.ebim.tms.shared.api;

import com.ebim.tms.shared.web.CorrelationId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.ProblemDetail;

/**
 * Builds the RFC 9457 documents the API returns for every error.
 *
 * <p>One factory keeps the shape identical whether the failure happened in the security
 * filter chain (before any controller ran) or in a {@code @RestControllerAdvice}, which is
 * exactly what a client needs in order to handle errors uniformly.
 *
 * <p>Extension members: {@code code}, {@code timestamp}, {@code correlationId} and, for
 * validation failures, {@code errors}. Nothing here ever carries a stack trace, a SQL
 * fragment, a class name or any other internal detail.
 */
public final class ApiProblems {

    public static final String CODE = "code";
    public static final String TIMESTAMP = "timestamp";
    public static final String CORRELATION_ID = "correlationId";
    public static final String ERRORS = "errors";

    private ApiProblems() {}

    public static ProblemDetail of(ProblemType type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), detail);
        problem.setType(type.type());
        problem.setTitle(type.title());
        problem.setProperty(CODE, type.code());
        problem.setProperty(TIMESTAMP, Instant.now().toString());
        CorrelationId.current().ifPresent(id -> problem.setProperty(CORRELATION_ID, id));
        return problem;
    }

    /** Decorates a {@link ProblemDetail} Spring already produced, so the shape stays uniform. */
    public static ProblemDetail decorate(ProblemDetail problem, ProblemType type) {
        problem.setType(type.type());
        problem.setTitle(type.title());
        problem.setProperty(CODE, type.code());
        problem.setProperty(TIMESTAMP, Instant.now().toString());
        CorrelationId.current().ifPresent(id -> problem.setProperty(CORRELATION_ID, id));
        return problem;
    }

    /**
     * A field-level violation. {@code field} is the request-side name (a JSON path or a
     * parameter name), never an entity or column name.
     */
    public record FieldViolation(String field, String message) {}

    public static ProblemDetail validation(String detail, List<FieldViolation> violations) {
        ProblemDetail problem = of(ProblemType.VALIDATION_FAILED, detail);
        problem.setProperty(ERRORS, violations.stream()
                .map(violation -> Map.of("field", violation.field(), "message", violation.message()))
                .toList());
        return problem;
    }
}
