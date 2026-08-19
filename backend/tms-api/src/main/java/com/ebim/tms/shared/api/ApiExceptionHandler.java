package com.ebim.tms.shared.api;

import com.ebim.tms.shared.security.CompanyScopeDeniedException;
import com.ebim.tms.shared.security.CompanyScopeInvalidException;
import com.ebim.tms.shared.security.CompanyScopeRequiredException;
import com.ebim.tms.shared.security.UnprovisionedPrincipalException;
import com.ebim.tms.shared.web.CorrelationId;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception that escapes a controller into the same RFC 9457 document the security
 * filters produce.
 *
 * <p>Two rules govern what a client is told:
 *
 * <ol>
 *   <li><b>Validation failures are specific.</b> The caller gets the field and the message, so
 *       a form can highlight the offending input without guessing.</li>
 *   <li><b>Everything unexpected is generic.</b> A 500 carries a correlation id and nothing
 *       else - no message, no exception type, no SQL, no stack trace. The detail lives in the
 *       server log under the same correlation id, which is where an operator can read it and
 *       an attacker cannot.</li>
 * </ol>
 *
 * <p>Authentication and authorization failures are handled here too. They are raised in the
 * filter chain, long before a controller exists, and are routed back into this advice by
 * {@link ApiExceptionResponder} - so "no token", "wrong company" and "invalid page size" all
 * come back as the same kind of document.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * No credentials, or credentials that did not verify. The two are distinguished only by
     * whether an {@code Authorization} header was sent, never by why verification failed.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException failure, WebRequest request) {
        boolean credentialsPresent = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        return respond(credentialsPresent
                ? ApiProblems.of(ProblemType.INVALID_TOKEN,
                        "The access token is missing, expired or not valid for this API.")
                : ApiProblems.of(ProblemType.UNAUTHENTICATED,
                        "This endpoint requires a Supabase access token in the Authorization header."),
                request);
    }

    /** A verified token whose identity has no active TMS profile: 403, because re-authenticating will not help. */
    @ExceptionHandler(UnprovisionedPrincipalException.class)
    public ResponseEntity<ProblemDetail> handleUnprovisioned(
            UnprovisionedPrincipalException failure, WebRequest request) {
        return respond(ApiProblems.of(ProblemType.PRINCIPAL_NOT_PROVISIONED,
                "This account is authenticated but has no active TMS profile. "
                        + "Ask an administrator to provision or reactivate it."), request);
    }

    /**
     * Missing permission. The document never names the permission that was required - that
     * would let a caller map the authorization model. The log line does.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException failure, WebRequest request) {
        return respond(ApiProblems.of(ProblemType.ACCESS_DENIED,
                "You do not have permission to perform this operation in the selected company."), request);
    }

    @ExceptionHandler(CompanyScopeDeniedException.class)
    public ResponseEntity<ProblemDetail> handleCompanyScopeDenied(
            CompanyScopeDeniedException failure, WebRequest request) {
        return respond(ApiProblems.of(ProblemType.COMPANY_SCOPE_FORBIDDEN, failure.getMessage()), request);
    }

    @ExceptionHandler(CompanyScopeInvalidException.class)
    public ResponseEntity<ProblemDetail> handleCompanyScopeInvalid(
            CompanyScopeInvalidException failure, WebRequest request) {
        return respond(ApiProblems.of(ProblemType.COMPANY_SCOPE_INVALID, failure.getMessage()), request);
    }

    @ExceptionHandler(CompanyScopeRequiredException.class)
    public ResponseEntity<ProblemDetail> handleCompanyScopeRequired(
            CompanyScopeRequiredException failure, WebRequest request) {
        return respond(ApiProblems.of(ProblemType.COMPANY_SCOPE_REQUIRED, failure.getMessage()), request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(InvalidRequestException failure, WebRequest request) {
        return respond(ApiProblems.of(ProblemType.MALFORMED_REQUEST, failure.getMessage()), request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException failure, WebRequest request) {
        return respond(ApiProblems.of(ProblemType.RESOURCE_NOT_FOUND, failure.getMessage()), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflict(ConflictException failure, WebRequest request) {
        return respond(ApiProblems.of(ProblemType.CONFLICT, failure.getMessage()), request);
    }

    /**
     * A {@code @Version} check failed: someone else changed the row between this request's read
     * and its write. Services that mutate a versioned entity (Orders, the first module to use
     * one - see {@code TransportOrder}) are expected to catch this around their own explicit
     * {@code saveAndFlush} call and rethrow {@link ConflictException} with a caller-specific
     * message; this handler is the backstop for a flush that happens elsewhere (e.g. at the
     * transaction's own commit-time flush) so a versioned write never surfaces as a 500.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(
            ObjectOptimisticLockingFailureException failure, WebRequest request) {
        return respond(ApiProblems.of(ProblemType.CONFLICT,
                "This record was changed by someone else since it was loaded. Reload and try again."), request);
    }

    /** Bean Validation on a {@code @Validated} service or on query parameters. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException failure, WebRequest request) {
        List<ApiProblems.FieldViolation> violations = failure.getConstraintViolations().stream()
                .map(violation -> new ApiProblems.FieldViolation(lastNode(violation), violation.getMessage()))
                .toList();
        return respond(ApiProblems.validation("One or more values are not valid.", violations), request);
    }

    /** Bean Validation on a {@code @Valid @RequestBody} argument. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException failure,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiProblems.FieldViolation> violations = failure.getBindingResult().getAllErrors().stream()
                .map(ApiExceptionHandler::toViolation)
                .toList();
        ProblemDetail problem = ApiProblems.validation("One or more fields are not valid.", violations);
        setInstance(problem, request);
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * Anything the framework itself rejected: unreadable JSON, missing parameter, wrong method,
     * unsupported media type. Spring already produced a {@link ProblemDetail}; this adds the
     * TMS type, code and correlation id so the shape is identical to every other error.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(failure, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            ApiProblems.decorate(problem, ProblemType.forStatus(statusCode));
            setInstance(problem, request);
        }
        return response;
    }

    /**
     * The catch-all. Logged at ERROR with the correlation id and the cause; answered with a
     * document that says only that something went wrong.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception failure, WebRequest request) {
        log.error("Unhandled exception [correlationId={}] on {}", CorrelationId.current().orElse("-"),
                describe(request), failure);
        return respond(ApiProblems.of(ProblemType.INTERNAL_ERROR,
                "The request could not be completed. Quote the correlationId when reporting this."), request);
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem, WebRequest request) {
        setInstance(problem, request);
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static void setInstance(ProblemDetail problem, WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
    }

    private static String describe(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getHttpMethod() + " " + servletRequest.getRequest().getRequestURI()
                : request.getDescription(false);
    }

    private static ApiProblems.FieldViolation toViolation(ObjectError error) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
        String message = error.getDefaultMessage() != null ? error.getDefaultMessage() : "is not valid";
        return new ApiProblems.FieldViolation(field, message);
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot >= 0 ? path.substring(lastDot + 1) : path;
    }
}
