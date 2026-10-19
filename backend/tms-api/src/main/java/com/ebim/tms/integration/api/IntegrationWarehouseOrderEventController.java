package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.IntegrationExecution;
import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.integration.application.IntegrationRequestExecutor;
import com.ebim.tms.integration.application.IntegrationWarehouseOrderEventService;
import com.ebim.tms.integration.application.WarehouseOrderEventBatchResultV1;
import com.ebim.tms.integration.application.WarehouseOrderEventBatchV1;
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
 * What a warehouse system did to its order (WAREHOUSE_EXECUTION_V1 7.5, ADR-025 section 4): it
 * cancelled it or closed it short. TMS places a hold and a person decides; nothing is cancelled here
 * (D-03). The company is the credential's, never a header's.
 */
@RestController
@RequestMapping(IntegrationApiPaths.V1)
@Tag(name = "Integration v1 - Warehouse execution")
@SecurityRequirement(name = OpenApiConfig.INTEGRATION_SCHEME)
public class IntegrationWarehouseOrderEventController {

    private final IntegrationWarehouseOrderEventService service;
    private final IntegrationRequestExecutor executor;

    public IntegrationWarehouseOrderEventController(IntegrationWarehouseOrderEventService service,
            IntegrationRequestExecutor executor) {
        this.service = service;
        this.executor = executor;
    }

    @PostMapping("/warehouse-order-events")
    @PreAuthorize("hasAuthority('integration.warehouse-order-event:write')")
    @Operation(summary = "Report warehouse order cancellations and short closes (WAREHOUSE_ORDER_EVENT)",
            description = "Up to 200 per request: ORDER_CANCELLED places a blocking hold WAREHOUSE_CANCELLED, "
                    + "ORDER_SHORT_CLOSED a non-blocking WAREHOUSE_SHORT_CLOSED. Never cancels an order. Results "
                    + "HOLD_PLACED, ALREADY_HELD, NOTED, ORDER_CLOSED, UNKNOWN_ORDER, DUPLICATE (per eventId) or "
                    + "INVALID; 200, or 207 when any item is INVALID.")
    @Parameter(name = ApiHeaders.IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
            description = "Recommended. Repeating it with the same payload replays the first response; with "
                    + "another payload it is 409.")
    public ResponseEntity<WarehouseOrderEventBatchResultV1> receive(
            IntegrationPrincipal principal,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            // No @Valid, for the reason IntegrationOrderController documents: validation runs inside
            // the executor so a refusal is recorded in the inbox like every other outcome.
            @RequestBody WarehouseOrderEventBatchV1 batch) {
        IntegrationExecution<WarehouseOrderEventBatchResultV1> execution = executor.execute(principal,
                IntegrationOperation.WAREHOUSE_ORDER_EVENT_BATCH, idempotencyKey, batch,
                WarehouseOrderEventBatchResultV1.class, () -> service.receive(principal, batch));
        return IntegrationResponses.of(execution);
    }
}
