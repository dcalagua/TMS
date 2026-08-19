package com.ebim.tms.shared.api;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The stable catalogue of machine-readable error identifiers returned by the API.
 *
 * <p>Every error response is an RFC 9457 {@code application/problem+json} document whose
 * {@code type} is one of these URNs and whose {@code code} extension is the same slug in
 * short form. Clients branch on {@code type}/{@code code}, never on {@code detail}, which is
 * human-readable prose and may change.
 *
 * <p>A URN is used rather than an {@code https://} URL because the identifier must be stable
 * and unambiguous, and RFC 9457 does not require the type URI to be dereferenceable.
 */
public enum ProblemType {

    /** No credentials, or credentials that could not be verified. */
    UNAUTHENTICATED("unauthenticated", "Authentication required", HttpStatus.UNAUTHORIZED),

    /** A bearer token was presented but is expired, malformed, or fails signature/issuer/audience checks. */
    INVALID_TOKEN("invalid-token", "Invalid access token", HttpStatus.UNAUTHORIZED),

    /**
     * The token is valid, but the identity behind it has no active TMS profile. Distinct from
     * {@link #UNAUTHENTICATED} on purpose: re-authenticating will not help, an administrator must act.
     */
    PRINCIPAL_NOT_PROVISIONED("principal-not-provisioned", "User is not provisioned in TMS", HttpStatus.FORBIDDEN),

    /** The endpoint is company-scoped and the request carried no {@code X-Company-Id} header. */
    COMPANY_SCOPE_REQUIRED("company-scope-required", "Company scope is required", HttpStatus.BAD_REQUEST),

    /** The {@code X-Company-Id} header is present but is not a UUID. */
    COMPANY_SCOPE_INVALID("company-scope-invalid", "Company scope is malformed", HttpStatus.BAD_REQUEST),

    /** The requested company exists as far as the client knows, but the caller holds no active membership in it. */
    COMPANY_SCOPE_FORBIDDEN("company-scope-forbidden", "Company scope is not allowed", HttpStatus.FORBIDDEN),

    /** Authenticated and scoped, but the caller lacks the permission the operation requires. */
    ACCESS_DENIED("access-denied", "Access denied", HttpStatus.FORBIDDEN),

    /** Bean Validation rejected the request body, path variables or query parameters. */
    VALIDATION_FAILED("validation-failed", "Request validation failed", HttpStatus.BAD_REQUEST),

    /** The request could not be read at all: unparseable JSON, wrong parameter type, missing parameter. */
    MALFORMED_REQUEST("malformed-request", "Malformed request", HttpStatus.BAD_REQUEST),

    /** The addressed resource does not exist inside the caller's company scope. */
    RESOURCE_NOT_FOUND("resource-not-found", "Resource not found", HttpStatus.NOT_FOUND),

    /** A business invariant or an optimistic-locking check refused the change. */
    CONFLICT("conflict", "Conflicting request", HttpStatus.CONFLICT),

    /** Anything unexpected. The detail is deliberately generic; the cause is only in the server log. */
    INTERNAL_ERROR("internal-error", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);

    private static final String URN_PREFIX = "urn:tms:problem:";

    /**
     * Best-effort classification for a failure Spring MVC already turned into a status code
     * (unsupported media type, method not allowed, unreadable body, ...). Specific handlers
     * pick their type explicitly; this only keeps the generic ones from being untyped.
     */
    public static ProblemType forStatus(org.springframework.http.HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return INTERNAL_ERROR;
        }
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return RESOURCE_NOT_FOUND;
        }
        if (status.value() == HttpStatus.CONFLICT.value()) {
            return CONFLICT;
        }
        if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
            return UNAUTHENTICATED;
        }
        if (status.value() == HttpStatus.FORBIDDEN.value()) {
            return ACCESS_DENIED;
        }
        return MALFORMED_REQUEST;
    }

    private final String code;
    private final String title;
    private final HttpStatus status;

    ProblemType(String code, String title, HttpStatus status) {
        this.code = code;
        this.title = title;
        this.status = status;
    }

    /** Short machine code, also emitted as the {@code code} extension member. */
    public String code() {
        return code;
    }

    public URI type() {
        return URI.create(URN_PREFIX + code);
    }

    public String title() {
        return title;
    }

    public HttpStatus status() {
        return status;
    }
}
