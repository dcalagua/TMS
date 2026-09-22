package com.ebim.tms.iam.provisioning.api;

import com.ebim.tms.iam.provisioning.application.PlatformProvisioningException;
import com.ebim.tms.iam.provisioning.application.PlatformProvisioningException.FieldIssue;
import com.ebim.tms.iam.provisioning.application.ProvisioningErrorCode;
import com.ebim.tms.shared.web.CorrelationId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns every failure of {@link PlatformProvisioningController} into the contract's error body.
 *
 * <p>Scoped to that controller and ordered ahead of {@code ApiExceptionHandler}, so the rest of the
 * API keeps its RFC 9457 documents and this surface keeps the {@code code} MasterAdmin reads.
 * Nothing from an exception message reaches the body: the codes and messages are the fixed ones of
 * {@link ProvisioningErrorCode}.
 */
@RestControllerAdvice(assignableTypes = PlatformProvisioningController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlatformProvisioningExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PlatformProvisioningExceptionHandler.class);

    @ExceptionHandler(PlatformProvisioningException.class)
    public ResponseEntity<ProvisioningErrorResponse> contract(PlatformProvisioningException failure) {
        return respond(failure.code(), failure.details());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProvisioningErrorResponse> unreadable(HttpMessageNotReadableException failure) {
        return respond(ProvisioningErrorCode.INVALID_REQUEST,
                List.of(new FieldIssue("$", "malformed JSON or a field of the wrong type")));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProvisioningErrorResponse> mediaType(HttpMediaTypeNotSupportedException failure) {
        return respond(ProvisioningErrorCode.UNSUPPORTED_MEDIA_TYPE, List.of());
    }

    /** {@code @PreAuthorize} refusing: the chain let the token in, the handler's scope did not. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProvisioningErrorResponse> denied(AccessDeniedException failure) {
        return respond(ProvisioningErrorCode.MISSING_SCOPE, List.of());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ProvisioningErrorResponse> unexpected(RuntimeException failure) {
        log.error("Unexpected failure on the MasterAdmin provisioning surface", failure);
        return respond(ProvisioningErrorCode.PROVISIONING_FAILED, List.of());
    }

    private static ResponseEntity<ProvisioningErrorResponse> respond(ProvisioningErrorCode code, List<FieldIssue> details) {
        return ResponseEntity.status(code.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(ProvisioningErrorResponse.of(code, details, CorrelationId.current().orElse(null)));
    }
}
