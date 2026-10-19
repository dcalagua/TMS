package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.IntegrationExecution;
import com.ebim.tms.integration.application.IntegrationOrderReadService;
import com.ebim.tms.integration.application.IntegrationOrderService;
import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.integration.application.IntegrationRequestExecutor;
import com.ebim.tms.integration.application.OrderBatchRequest;
import com.ebim.tms.integration.application.OrderBatchResult;
import com.ebim.tms.integration.application.OrderStatusV1;
import com.ebim.tms.integration.application.OrderUpsertRequest;
import com.ebim.tms.integration.application.OrderUpsertResult;
import com.ebim.tms.integration.domain.IntegrationOperation;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound transport orders, API version 1. The order counterpart of
 * {@link IntegrationLocationController}; see that class for the tenancy and upsert reasoning,
 * which is identical.
 *
 * <p>The batch endpoint is the one this API is actually built for. A day's dispatch plan arrives
 * as one delivery of a few hundred orders, and per-item independence means a single order naming
 * a store created this morning does not cost the other 199 - see {@code OrderBatchResult}.
 *
 * <p><b>The request body carries no {@code @Valid}, deliberately.</b> Bean Validation runs inside
 * {@link IntegrationRequestExecutor}, because Spring refuses a {@code @Valid} argument before the
 * controller body executes - which would make a constraint failure the one outcome the integration
 * inbox never records. Validating inside the executor puts every rejection on the same audited
 * path; the document the caller receives is unchanged.
 */
@RestController
@RequestMapping(IntegrationApiPaths.V1 + "/orders")
@Tag(name = "Integration v1 - Orders",
        description = "Machine-to-machine intake of transport orders. Authenticated with an "
                + "integration credential, never a user token.")
@SecurityRequirement(name = OpenApiConfig.INTEGRATION_SCHEME)
public class IntegrationOrderController {

    private final IntegrationOrderService orderService;
    private final IntegrationOrderReadService readService;
    private final IntegrationRequestExecutor executor;

    public IntegrationOrderController(IntegrationOrderService orderService, IntegrationOrderReadService readService,
            IntegrationRequestExecutor executor) {
        this.orderService = orderService;
        this.readService = readService;
        this.executor = executor;
    }

    @GetMapping("/{orderNumber}")
    @PreAuthorize("hasAuthority('integration.order:read')")
    @Operation(summary = "One transport order: its key, status and cancellation (WAREHOUSE_EXECUTION_V1 7.4)",
            description = "What a warehouse reads after an ORDER_CANCELLED webhook. 404 for another company's "
                    + "order exactly as for an unknown one.")
    public OrderStatusV1 find(IntegrationPrincipal principal, @PathVariable String orderNumber) {
        return readService.find(principal, orderNumber);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('integration.order:read')")
    @Operation(summary = "The order feed by status and updatedSince",
            description = "Defaults to CANCELLED. Ordered oldest-touched first (updatedAt, then id) so a partner "
                    + "can page a reconciliation deterministically.")
    public PageResponse<OrderStatusV1> search(
            IntegrationPrincipal principal,
            @Parameter(description = "One or more order statuses. Defaults to CANCELLED.")
            @RequestParam(required = false) Set<String> status,
            @Parameter(description = "Only orders touched at or after this instant (ISO-8601).")
            @RequestParam(required = false) OffsetDateTime updatedSince,
            @ModelAttribute PageQuery pageQuery) {
        return readService.search(principal, status, updatedSince, pageQuery);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('integration.order:write')")
    @Operation(summary = "Create or update one transport order",
            description = "Identity is (externalSource, externalReference), which is mandatory. "
                    + "201 when the order did not exist, 200 when it did. An order that is already "
                    + "PLANNED or CANCELLED is refused with 409.")
    @Parameter(name = ApiHeaders.IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
            description = "Optional. Repeating it with the same payload replays the first response; "
                    + "repeating it with a different payload is refused with 409.")
    public ResponseEntity<OrderUpsertResult> upsert(
            IntegrationPrincipal principal,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody OrderUpsertRequest request) {

        IntegrationExecution<OrderUpsertResult> execution = executor.execute(principal,
                IntegrationOperation.ORDER_UPSERT, idempotencyKey, request, OrderUpsertResult.class,
                () -> orderService.upsert(principal, request));
        return IntegrationResponses.of(execution);
    }

    @PostMapping("/batch")
    @PreAuthorize("hasAuthority('integration.order:write')")
    @Operation(summary = "Create or update many transport orders",
            description = "Items are independent: 200 when every item landed, 207 when any was "
                    + "refused, with a per-item reason at the index it was sent.")
    @Parameter(name = ApiHeaders.IDEMPOTENCY_KEY, in = ParameterIn.HEADER, description = "Optional.")
    public ResponseEntity<OrderBatchResult> batch(
            IntegrationPrincipal principal,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody OrderBatchRequest request) {

        IntegrationExecution<OrderBatchResult> execution = executor.execute(principal,
                IntegrationOperation.ORDER_BATCH, idempotencyKey, request, OrderBatchResult.class,
                () -> orderService.batch(principal, request));
        return IntegrationResponses.of(execution);
    }
}
