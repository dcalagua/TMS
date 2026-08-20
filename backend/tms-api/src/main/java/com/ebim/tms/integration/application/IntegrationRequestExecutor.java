package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.IntegrationOperation;
import com.ebim.tms.integration.domain.IntegrationRequest;
import com.ebim.tms.integration.domain.IntegrationRequestStatus;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.IdempotencyConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.web.CorrelationId;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The one path every inbound integration request takes: fingerprint the payload, honour an
 * {@code Idempotency-Key} if one was sent, run the operation, and record what happened - whatever
 * happened.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Two independent mechanisms, and they are not alternatives:
 *
 * <ol>
 *   <li><b>Business identity</b> - {@code (externalSystem, externalReference)} - is the primary
 *       one and always applies. Redelivering the same object updates it rather than duplicating
 *       it, which is what makes an at-least-once sender safe without any header at all.</li>
 *   <li><b>{@code Idempotency-Key}</b> is optional and covers what business identity cannot: the
 *       case where the sender never learned the outcome. The first response is stored and
 *       replayed byte for byte, so a partner that timed out and retried sees the answer they
 *       missed instead of a second, possibly different one.</li>
 * </ol>
 *
 * <p>A key replayed with a <em>different</em> payload is refused
 * ({@link IdempotencyConflictException}). Silently returning the first response would tell the
 * caller their second object was accepted when nothing was written.
 *
 * <h2>Why the inbox row always exists</h2>
 *
 * <p>{@link IntegrationInboxService#record} runs in its own transaction, so a rejected or failed
 * delivery still leaves a record. Every non-2xx answer this executor produces is therefore
 * explainable afterwards from the database alone, which is the difference between an integration
 * a support engineer can reason about and one where the only evidence is the partner's word.
 *
 * <h2>What is never recorded</h2>
 *
 * <p>No credential material, and no raw payload unless the deployment opted in
 * ({@link IntegrationProperties#retainPayloads}). The error summary is either a caller-safe
 * business message - the same text the API returned - or, for an unexpected failure, a fixed
 * string plus the correlation id. An exception message, a stack frame or a SQL fragment never
 * reaches this table, exactly as they never reach a response body.
 */
@Service
public class IntegrationRequestExecutor {

    private static final Logger log = LoggerFactory.getLogger(IntegrationRequestExecutor.class);

    /** Mirrors {@code ck_integration_request_idempotency_key_shape}; rejected before any work. */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    /** {@code error_summary} is a summary. Anything longer is a log line, not a column. */
    private static final int MAX_ERROR_SUMMARY = 500;

    private final IntegrationInboxService inbox;
    private final IntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final Clock clock;

    public IntegrationRequestExecutor(IntegrationInboxService inbox, IntegrationProperties properties,
            ObjectMapper objectMapper, Validator validator, Clock clock) {
        this.inbox = inbox;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * @param payload      the deserialised request record, hashed to fingerprint the delivery
     * @param responseType the concrete response record, needed to replay a stored body
     * @param work         the operation itself. Runs outside any transaction of this class, so it
     *                     opens (and, on failure, rolls back) its own
     */
    public <T> IntegrationExecution<T> execute(IntegrationPrincipal principal, IntegrationOperation operation,
            String idempotencyKey, Object payload, Class<T> responseType, Supplier<IntegrationOutcome<T>> work) {

        String key = normalizeIdempotencyKey(idempotencyKey);
        String payloadHash = PayloadHash.of(objectMapper, payload);
        OffsetDateTime receivedAt = OffsetDateTime.now(clock);

        if (key != null) {
            Optional<IntegrationExecution<T>> replay = replay(principal, operation, key, payloadHash, responseType);
            if (replay.isPresent()) {
                return replay.get();
            }
        }

        String snapshot = properties.retainPayloads() ? snapshot(payload) : null;
        try {
            // Bean Validation runs here rather than as @Valid on the controller argument, and that
            // placement is the whole reason the inbox is complete. Spring rejects a @Valid argument
            // before the controller body executes, so the delivery a partner is most likely to get
            // wrong while onboarding would be the one delivery leaving no trace - "we sent it and
            // got a 400" would be unanswerable exactly when it is asked most often. Inside the try
            // block, a constraint failure is recorded on the same terms as every other outcome.
            requireValid(payload);
            IntegrationOutcome<T> outcome = work.get();
            inbox.record(row(principal, operation, key, payloadHash, snapshot, outcome, outcome.status(),
                    outcome.httpStatus(), outcome.errorSummary(), responseBody(outcome.body()), receivedAt));
            return new IntegrationExecution<>(outcome.body(), outcome.httpStatus(), false);
        } catch (ConstraintViolationException validationFailure) {
            recordRejection(principal, operation, key, payloadHash, snapshot, 400, summarise(validationFailure),
                    receivedAt);
            throw validationFailure;
        } catch (InvalidRequestException rejected) {
            recordRejection(principal, operation, key, payloadHash, snapshot, 400, truncate(rejected.getMessage()),
                    receivedAt);
            throw rejected;
        } catch (ResourceNotFoundException notFound) {
            recordRejection(principal, operation, key, payloadHash, snapshot, 404, truncate(notFound.getMessage()),
                    receivedAt);
            throw notFound;
        } catch (ConflictException conflict) {
            recordRejection(principal, operation, key, payloadHash, snapshot, 409, truncate(conflict.getMessage()),
                    receivedAt);
            throw conflict;
        } catch (RuntimeException unexpected) {
            // Ours, not the caller's. The cause goes to the log under the correlation id; the
            // inbox and the response both say only that it failed.
            String correlationId = CorrelationId.current().orElse("-");
            log.error("Integration operation {} failed for client {} [correlationId={}]", operation.code(),
                    principal.clientId(), correlationId, unexpected);
            inbox.record(row(principal, operation, key, payloadHash, snapshot, null,
                    IntegrationRequestStatus.FAILED, 500,
                    "The request could not be completed. Correlation id: " + correlationId, null, receivedAt));
            throw unexpected;
        }
    }

    /**
     * The stored answer for this key, when there is one.
     *
     * <p>A prior delivery that was itself rejected is <em>not</em> replayed: the reason it failed
     * may since have been fixed - the missing store finally created, the master activated - and
     * answering "still 400" forever would make the key a permanent tombstone. Only a stored
     * response body, which exists exactly for the deliveries that produced one, is replayed.
     */
    private <T> Optional<IntegrationExecution<T>> replay(IntegrationPrincipal principal,
            IntegrationOperation operation, String key, String payloadHash, Class<T> responseType) {

        Optional<IntegrationRequest> prior = inbox.findByIdempotencyKey(
                principal.companyId(), principal.id(), operation, key);
        if (prior.isEmpty()) {
            return Optional.empty();
        }

        IntegrationRequest recorded = prior.get();
        if (!recorded.payloadHash().equals(payloadHash)) {
            throw new IdempotencyConflictException("Idempotency-Key '" + key + "' was already used by this client "
                    + "for a different payload. Use a new key for a new object.");
        }
        if (recorded.responseBody() == null) {
            return Optional.empty();
        }

        try {
            T body = objectMapper.readValue(recorded.responseBody(), responseType);
            return Optional.of(new IntegrationExecution<>(body, recorded.httpStatus(), true));
        } catch (JacksonException unreadable) {
            // A stored response this build can no longer parse - a response record changed shape
            // between deployments. Re-executing is safe (the operations are idempotent) and is
            // strictly better than failing a delivery over a schema change of our own making.
            log.warn("Stored integration response for key {} could not be replayed; re-executing", key, unreadable);
            return Optional.empty();
        }
    }

    /**
     * Applies the request record's own constraints, exactly as {@code @Valid} would have.
     *
     * <p>The violations are wrapped in the same {@link ConstraintViolationException} the service
     * layer throws, so the response document is byte-identical to the one a {@code @Validated}
     * service produces - this moves <em>where</em> validation happens, never what the caller sees.
     */
    private void requireValid(Object payload) {
        Set<ConstraintViolation<Object>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private void recordRejection(IntegrationPrincipal principal, IntegrationOperation operation, String key,
            String payloadHash, String snapshot, int httpStatus, String errorSummary, OffsetDateTime receivedAt) {
        inbox.record(row(principal, operation, key, payloadHash, snapshot, null, IntegrationRequestStatus.REJECTED,
                httpStatus, errorSummary, null, receivedAt));
    }

    private IntegrationRequest row(IntegrationPrincipal principal, IntegrationOperation operation, String key,
            String payloadHash, String snapshot, IntegrationOutcome<?> outcome, IntegrationRequestStatus status,
            int httpStatus, String errorSummary, String responseBody, OffsetDateTime receivedAt) {
        return new IntegrationRequest(
                principal.companyId(),
                principal.id(),
                operation,
                key,
                outcome == null ? null : outcome.externalSystem(),
                outcome == null ? null : outcome.externalReference(),
                payloadHash,
                snapshot,
                status,
                httpStatus,
                outcome == null ? 1 : outcome.itemCount(),
                outcome == null ? 0 : outcome.succeededCount(),
                outcome == null ? 1 : outcome.failedCount(),
                outcome == null ? null : outcome.resourceId(),
                responseBody,
                errorSummary,
                CorrelationId.current().orElse(null),
                receivedAt,
                OffsetDateTime.now(clock));
    }

    private String responseBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException notSerialisable) {
            // The same object is about to be written to the response by the same mapper, so this
            // cannot happen. If it somehow did, losing the replay copy must not lose the delivery.
            log.warn("An integration response could not be stored for replay", notSerialisable);
            return null;
        }
    }

    private String snapshot(Object payload) {
        try {
            return truncateSnapshot(objectMapper.writeValueAsString(payload));
        } catch (JacksonException notSerialisable) {
            return null;
        }
    }

    private static String normalizeIdempotencyKey(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String key = header.trim();
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new InvalidRequestException("Idempotency-Key must be 8 to 128 characters of letters, digits, "
                    + "'.', '_', ':' or '-'.");
        }
        return key;
    }

    /**
     * Field-level violations flattened into one caller-safe line. The same information the
     * response carries as {@code errors[]}, in the form a support query can grep.
     */
    private static String summarise(ConstraintViolationException failure) {
        return truncate(failure.getConstraintViolations().stream()
                .map(IntegrationRequestExecutor::describe)
                .sorted()
                .collect(Collectors.joining("; ")));
    }

    private static String describe(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return (lastDot >= 0 ? path.substring(lastDot + 1) : path) + ": " + violation.getMessage();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_SUMMARY ? value : value.substring(0, MAX_ERROR_SUMMARY - 3) + "...";
    }

    /** A snapshot is for debugging one delivery, not for archiving a megabyte of it. */
    private static String truncateSnapshot(String value) {
        int limit = 64 * 1024;
        return value != null && value.length() > limit ? value.substring(0, limit) : value;
    }
}
