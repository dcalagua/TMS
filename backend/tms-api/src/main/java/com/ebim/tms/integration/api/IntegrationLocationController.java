package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.IntegrationExecution;
import com.ebim.tms.integration.application.IntegrationLocationService;
import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.integration.application.IntegrationRequestExecutor;
import com.ebim.tms.integration.application.LocationBatchRequest;
import com.ebim.tms.integration.application.LocationBatchResult;
import com.ebim.tms.integration.application.LocationUpsertRequest;
import com.ebim.tms.integration.application.LocationUpsertResult;
import com.ebim.tms.integration.domain.IntegrationOperation;
import com.ebim.tms.shared.api.ApiHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound locations/stores, API version 1.
 *
 * <p>There is no company parameter and no {@code X-Company-Id}: the tenant comes from the
 * credential ({@link IntegrationPrincipal}), which is the strongest form of ADR-003's rule -
 * the client is never asked, so there is nothing to validate and nothing to get wrong.
 *
 *
 * <p><b>The request body carries no {@code @Valid}, deliberately.</b> Bean Validation runs inside
 * {@link IntegrationRequestExecutor}, because Spring refuses a {@code @Valid} argument before the
 * controller body executes - which would make a constraint failure the one outcome the integration
 * inbox never records. Validating inside the executor puts every rejection on the same audited
 * path; the document the caller receives is unchanged.
 * <p>Both endpoints are upserts rather than a create/update pair. A sending system generally does
 * not know whether TMS has seen a store before, and making it find out first would add a round
 * trip and a race for no benefit; the identity rules in {@code LocationIntakeCommand} decide.
 */
@RestController
@RequestMapping(IntegrationApiPaths.V1 + "/locations")
@Tag(name = "Integration v1 - Locations",
        description = "Machine-to-machine intake of locations and stores. Authenticated with an "
                + "integration credential, never a user token.")
public class IntegrationLocationController {

    private final IntegrationLocationService locationService;
    private final IntegrationRequestExecutor executor;

    public IntegrationLocationController(IntegrationLocationService locationService,
            IntegrationRequestExecutor executor) {
        this.locationService = locationService;
        this.executor = executor;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('integration.location:write')")
    @Operation(summary = "Create or update one location",
            description = "201 when the location did not exist, 200 when it did. Identity is "
                    + "(externalSystem, externalReference) when present, otherwise code.")
    @Parameter(name = ApiHeaders.IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
            description = "Optional. Repeating it with the same payload replays the first response; "
                    + "repeating it with a different payload is refused with 409.")
    public ResponseEntity<LocationUpsertResult> upsert(
            IntegrationPrincipal principal,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody LocationUpsertRequest request) {

        IntegrationExecution<LocationUpsertResult> execution = executor.execute(principal,
                IntegrationOperation.LOCATION_UPSERT, idempotencyKey, request, LocationUpsertResult.class,
                () -> locationService.upsert(principal, request));
        return IntegrationResponses.of(execution);
    }

    @PostMapping("/batch")
    @PreAuthorize("hasAuthority('integration.location:write')")
    @Operation(summary = "Create or update many locations",
            description = "Items are independent: 200 when every item landed, 207 when any was "
                    + "refused, with a per-item reason at the index it was sent.")
    @Parameter(name = ApiHeaders.IDEMPOTENCY_KEY, in = ParameterIn.HEADER, description = "Optional.")
    public ResponseEntity<LocationBatchResult> batch(
            IntegrationPrincipal principal,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody LocationBatchRequest request) {

        IntegrationExecution<LocationBatchResult> execution = executor.execute(principal,
                IntegrationOperation.LOCATION_BATCH, idempotencyKey, request, LocationBatchResult.class,
                () -> locationService.batch(principal, request));
        return IntegrationResponses.of(execution);
    }
}
