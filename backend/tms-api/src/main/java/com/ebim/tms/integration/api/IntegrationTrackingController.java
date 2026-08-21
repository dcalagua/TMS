package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.IntegrationExecution;
import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.integration.application.IntegrationRequestExecutor;
import com.ebim.tms.integration.application.IntegrationTrackingService;
import com.ebim.tms.integration.application.TrackingBatchRequest;
import com.ebim.tms.integration.application.TrackingBatchResult;
import com.ebim.tms.integration.domain.IntegrationOperation;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound vehicle positions, API version 1 (migration V29). See
 * {@link IntegrationLocationController} for the tenancy and audit reasoning, which is identical -
 * the company comes from the credential, and every delivery leaves an inbox row whatever it
 * produced.
 *
 * <p><b>One endpoint, not two.</b> The location and order APIs each offer a single-object and a
 * batch form because a partner creating one store means something different from a partner loading
 * a day's masters. A position feed has no such distinction: a device reporting live sends a batch
 * of one, a device flushing a buffer sends a batch of two hundred, and both are the same operation
 * with the same rules. A second shape would be a second thing to document, version and test for
 * nothing.
 *
 * <p><b>Refused items do not fail the delivery.</b> A run whose every position names an unknown
 * shipment still answers 207 with a reason per item, not 400 - a telematics feed reporting against
 * shipments that were cancelled an hour ago is doing exactly what it should, and a 4xx would make
 * a correct sender look broken. The idempotency contract is the ordinary one: business identity
 * (feed, shipment, instant) makes redelivery a no-op, and {@code Idempotency-Key} additionally
 * replays the original response to a sender that never learned the outcome.
 *
 * <p>The request body carries no {@code @Valid} for the reason
 * {@link IntegrationOrderController} documents: validation runs inside
 * {@link IntegrationRequestExecutor} so a constraint failure is recorded in the inbox like every
 * other outcome.
 */
@RestController
@RequestMapping(IntegrationApiPaths.V1 + "/tracking")
@Tag(name = "Integration v1 - Tracking",
        description = "Machine-to-machine intake of vehicle positions. Authenticated with an "
                + "integration credential, never a user token.")
@SecurityRequirement(name = OpenApiConfig.INTEGRATION_SCHEME)
public class IntegrationTrackingController {

    private final IntegrationTrackingService trackingService;
    private final IntegrationRequestExecutor executor;

    public IntegrationTrackingController(IntegrationTrackingService trackingService,
            IntegrationRequestExecutor executor) {
        this.trackingService = trackingService;
        this.executor = executor;
    }

    @PostMapping("/positions")
    @PreAuthorize("hasAuthority('integration.tracking:write')")
    @Operation(summary = "Report where vehicles are",
            description = "Positions are attached to shipments by shipment number - never by a TMS "
                    + "uuid. Items are independent: 200 when every one was accepted, 207 when any "
                    + "was refused, with a reason at the index it was sent. An accepted position is "
                    + "not necessarily a stored one: TMS keeps at most one point per configured "
                    + "interval per (shipment, feed) and reports the rest as THINNED, which is a "
                    + "success. Re-sending a position this feed already reported for the same "
                    + "shipment and instant is a no-op.")
    @Parameter(name = ApiHeaders.IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
            description = "Optional. Repeating it with the same payload replays the first response; "
                    + "repeating it with a different payload is refused with 409. Not needed for "
                    + "ordinary retries - a redelivered position is already a no-op.")
    public ResponseEntity<TrackingBatchResult> positions(
            IntegrationPrincipal principal,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody TrackingBatchRequest request) {

        IntegrationExecution<TrackingBatchResult> execution = executor.execute(principal,
                IntegrationOperation.TRACKING_BATCH, idempotencyKey, request, TrackingBatchResult.class,
                () -> trackingService.batch(principal, request));
        return IntegrationResponses.of(execution);
    }
}
