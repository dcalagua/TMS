package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.IntegrationExecution;
import com.ebim.tms.shared.api.ApiHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/**
 * Turns an {@link IntegrationExecution} into the HTTP answer.
 *
 * <p>One place, so that every endpoint of the inbound API sets the replay header the same way and
 * none of them forgets it. A partner that cannot see {@code X-Idempotent-Replay} has no way to
 * verify their own retry logic from their side of the wire.
 */
final class IntegrationResponses {

    private IntegrationResponses() {}

    static <T> ResponseEntity<T> of(IntegrationExecution<T> execution) {
        return ResponseEntity.status(HttpStatusCode.valueOf(execution.httpStatus()))
                .header(ApiHeaders.IDEMPOTENT_REPLAY, Boolean.toString(execution.replayed()))
                .body(execution.body());
    }
}
