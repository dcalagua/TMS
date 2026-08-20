package com.ebim.tms.integration.application;

import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;

/**
 * Why one item of a batch was refused, in the same vocabulary a single-object failure uses.
 *
 * <p>{@code code} is one of {@link ProblemType}'s machine codes, so a partner branches on the same
 * string whether the failure arrived as an RFC 9457 document (single object) or as an element of a
 * batch result. Two error vocabularies for the same failures is the kind of thing that makes an
 * integration client full of special cases.
 *
 * @param fields field-level violations, present only for a validation failure. Field names are the
 *     request's own, never an entity or column name
 */
public record IntegrationItemError(String code, String message, List<Map<String, String>> fields) {

    public IntegrationItemError {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /**
     * Classifies a business failure. Anything not listed here is <em>not</em> classified: it is
     * rethrown, so an unexpected fault becomes a 500 with a correlation id and a log entry rather
     * than a per-item error message that quietly hides a bug in TMS.
     */
    public static IntegrationItemError from(RuntimeException failure) {
        if (failure instanceof ConstraintViolationException violations) {
            return new IntegrationItemError(ProblemType.VALIDATION_FAILED.code(),
                    "One or more fields are not valid.",
                    violations.getConstraintViolations().stream()
                            .map(IntegrationItemError::describe)
                            .sorted(java.util.Comparator.comparing(field -> field.get("field")))
                            .toList());
        }
        if (failure instanceof InvalidRequestException invalid) {
            return new IntegrationItemError(ProblemType.MALFORMED_REQUEST.code(), invalid.getMessage(), List.of());
        }
        if (failure instanceof ResourceNotFoundException notFound) {
            return new IntegrationItemError(ProblemType.RESOURCE_NOT_FOUND.code(), notFound.getMessage(), List.of());
        }
        if (failure instanceof ConflictException conflict) {
            return new IntegrationItemError(ProblemType.CONFLICT.code(), conflict.getMessage(), List.of());
        }
        throw failure;
    }

    private static Map<String, String> describe(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return Map.of("field", lastDot >= 0 ? path.substring(lastDot + 1) : path,
                "message", violation.getMessage());
    }
}
