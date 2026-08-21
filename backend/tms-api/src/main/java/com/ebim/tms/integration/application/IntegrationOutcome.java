package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.IntegrationRequestStatus;
import java.util.UUID;

/**
 * What one inbound operation produced: the body to return, the status to answer with, and the
 * facts the integration inbox records about it.
 *
 * <p>Built by the operation itself rather than inferred by the executor, because only the
 * operation knows whether a batch was wholly or partly accepted, which business row a single
 * upsert touched, and which external reference it was about.
 *
 * @param errorSummary sanitised and caller-safe, or {@code null} on success. Never an exception
 *     message from an unexpected failure - that is the executor's job to keep generic
 */
public record IntegrationOutcome<T>(
        T body,
        IntegrationRequestStatus status,
        int httpStatus,
        UUID resourceId,
        String externalSystem,
        String externalReference,
        int itemCount,
        int succeededCount,
        int failedCount,
        String errorSummary) {

    /** One object, accepted. */
    public static <T> IntegrationOutcome<T> single(T body, int httpStatus, UUID resourceId, String externalSystem,
            String externalReference) {
        return new IntegrationOutcome<>(body, IntegrationRequestStatus.SUCCEEDED, httpStatus, resourceId,
                externalSystem, externalReference, 1, 1, 0, null);
    }

    /** A batch. {@code PARTIAL} - and HTTP 207 - exactly when at least one item was refused. */
    public static <T> IntegrationOutcome<T> batch(T body, int itemCount, int succeededCount, int failedCount,
            String externalSystem) {
        boolean everythingLanded = failedCount == 0;
        return new IntegrationOutcome<>(body,
                everythingLanded ? IntegrationRequestStatus.SUCCEEDED : IntegrationRequestStatus.PARTIAL,
                everythingLanded ? 200 : 207,
                null, externalSystem, null, itemCount, succeededCount, failedCount,
                everythingLanded ? null : failedCount + " of " + itemCount + " items were refused");
    }
}
