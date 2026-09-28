package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.DispatchConfirmationDelivery;
import com.ebim.tms.integration.application.DispatchConfirmationResponseV1;
import com.ebim.tms.integration.application.DispatchConfirmationV1;
import com.ebim.tms.integration.application.IntegrationExecution;
import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.integration.application.IntegrationRequestExecutor;
import com.ebim.tms.integration.application.IntegrationWarehouseService;
import com.ebim.tms.integration.application.WarehouseMilestoneBatchResultV1;
import com.ebim.tms.integration.application.WarehouseMilestoneBatchV1;
import com.ebim.tms.integration.domain.IntegrationOperation;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PayloadTooLargeException;
import com.ebim.tms.shared.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * What a warehouse system physically did (WAREHOUSE_EXECUTION_V1 §4, ADR-013): its dispatch
 * documents and its milestones. Authenticated with an integration credential of the TMS company;
 * the company is the credential's, never a header's.
 */
@RestController
@RequestMapping(IntegrationApiPaths.V1)
@Tag(name = "Integration v1 - Warehouse execution",
        description = "A warehouse system's dispatch (SLS) and milestones. Discrepancies with the plan are "
                + "business outcomes answered 200/201, never 4xx.")
@SecurityRequirement(name = OpenApiConfig.INTEGRATION_SCHEME)
public class IntegrationWarehouseController {

    /** The documented body limit of a dispatch document (WAREHOUSE_EXECUTION_V1 §4.1). */
    static final int MAX_DISPATCH_BODY_BYTES = 2 * 1024 * 1024;

    private final IntegrationWarehouseService warehouseService;
    private final IntegrationRequestExecutor executor;
    private final ObjectMapper objectMapper;

    public IntegrationWarehouseController(IntegrationWarehouseService warehouseService,
            IntegrationRequestExecutor executor, ObjectMapper objectMapper) {
        this.warehouseService = warehouseService;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/dispatch-confirmations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('integration.dispatch:write')")
    @Operation(summary = "Report a dispatch document (DISPATCH_CONFIRMED)",
            description = "201 on first receipt, 200 on a repeat (UNCHANGED) or an older revision (STALE). The "
                    + "outcome says what TMS did: APPLIED, RECONCILED, UNAPPLIED or RECORDED_UNMATCHED. Business "
                    + "idempotency is (sourceSystem, dispatchReference, revision); the same revision with other "
                    + "content is 409. Body limit 2 MB (413).")
    @Parameter(name = ApiHeaders.IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
            description = "Recommended. Repeating it with the same payload replays the first response.")
    public ResponseEntity<DispatchConfirmationResponseV1> dispatch(
            IntegrationPrincipal principal,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            HttpServletRequest request) {
        String rawBody = readBounded(request);
        DispatchConfirmationV1 document;
        try {
            document = objectMapper.readValue(rawBody, DispatchConfirmationV1.class);
        } catch (JacksonException malformed) {
            throw new InvalidRequestException("The body is not a valid dispatch document: "
                    + malformed.getOriginalMessage());
        }
        DispatchConfirmationDelivery delivery = new DispatchConfirmationDelivery(document, rawBody);
        IntegrationExecution<DispatchConfirmationResponseV1> execution = executor.execute(principal,
                IntegrationOperation.DISPATCH_CONFIRMATION, idempotencyKey, delivery,
                DispatchConfirmationResponseV1.class,
                () -> warehouseService.dispatch(principal, delivery, idempotencyKey));
        return IntegrationResponses.of(execution);
    }

    @PostMapping("/warehouse-milestones")
    @PreAuthorize("hasAuthority('integration.warehouse-milestone:write')")
    @Operation(summary = "Report warehouse milestones (WAREHOUSE_MILESTONE)",
            description = "Up to 200 per request: LOADING_STARTED, LOAD_READY, LOAD_CANCELLED. Informative - a "
                    + "milestone never moves a trip. 200 when every item was accepted, 207 when any was INVALID. "
                    + "Idempotent per eventId (DUPLICATE).")
    public ResponseEntity<WarehouseMilestoneBatchResultV1> milestones(
            IntegrationPrincipal principal,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            // No @Valid, for the reason IntegrationOrderController documents: validation runs inside
            // the executor so a refusal is recorded in the inbox like every other outcome.
            @RequestBody WarehouseMilestoneBatchV1 batch) {
        IntegrationExecution<WarehouseMilestoneBatchResultV1> execution = executor.execute(principal,
                IntegrationOperation.WAREHOUSE_MILESTONE_BATCH, idempotencyKey, batch,
                WarehouseMilestoneBatchResultV1.class, () -> warehouseService.milestones(principal, batch));
        return IntegrationResponses.of(execution);
    }

    /**
     * Reads at most the limit plus one byte, so an oversized body is refused without being held in
     * memory whole - and without trusting a Content-Length the sender may not have set.
     */
    private static String readBounded(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_DISPATCH_BODY_BYTES) {
            throw tooLarge();
        }
        try (InputStream body = request.getInputStream()) {
            byte[] bytes = body.readNBytes(MAX_DISPATCH_BODY_BYTES + 1);
            if (bytes.length > MAX_DISPATCH_BODY_BYTES) {
                throw tooLarge();
            }
            if (bytes.length == 0) {
                throw new InvalidRequestException("A dispatch document is required.");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new InvalidRequestException("The request body could not be read.");
        }
    }

    private static PayloadTooLargeException tooLarge() {
        return new PayloadTooLargeException("A dispatch document may be at most 2 MB.");
    }
}
