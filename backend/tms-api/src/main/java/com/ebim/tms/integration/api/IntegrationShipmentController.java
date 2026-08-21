package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.integration.application.IntegrationShipmentService;
import com.ebim.tms.integration.application.ShipmentEventV1;
import com.ebim.tms.integration.application.ShipmentPlanHeaderV1;
import com.ebim.tms.integration.application.ShipmentPlanV1;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Outbound confirmed/cancelled shipments, API version 1 (job 08).
 *
 * <p>Pull, not push: a partner polls this controller rather than receiving a webhook. See
 * {@code docs/integrations/OUTBOUND_SHIPMENT_V1.md} for why V1 stops at the transactional outbox
 * and defers webhook delivery, and for the field-by-field contract.
 *
 * <p>Read-only, so - unlike {@link IntegrationLocationController}/{@link IntegrationOrderController} -
 * nothing here goes through {@link com.ebim.tms.integration.application.IntegrationRequestExecutor}:
 * there is no payload to fingerprint, no idempotency key to honour and nothing to record beyond
 * what the server access log already carries.
 */
@RestController
@RequestMapping(IntegrationApiPaths.V1 + "/shipments")
@Tag(name = "Integration v1 - Shipments",
        description = "Machine-to-machine read access to confirmed/cancelled shipments. "
                + "Authenticated with an integration credential, never a user token.")
@SecurityRequirement(name = OpenApiConfig.INTEGRATION_SCHEME)
public class IntegrationShipmentController {

    private final IntegrationShipmentService service;

    public IntegrationShipmentController(IntegrationShipmentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('integration.shipment:read')")
    @Operation(summary = "List confirmed/cancelled shipments",
            description = "Defaults to CONFIRMED only. Ordered oldest-touched first so a partner "
                    + "can page through a full backfill deterministically; updatedSince is the "
                    + "usual incremental filter afterwards.")
    public PageResponse<ShipmentPlanHeaderV1> search(
            IntegrationPrincipal principal,
            @Parameter(description = "One or more of CONFIRMED, CANCELLED. Defaults to CONFIRMED.")
            @RequestParam(required = false) Set<String> status,
            @Parameter(description = "Only shipments touched at or after this instant (ISO-8601).")
            @RequestParam(required = false) OffsetDateTime updatedSince,
            @ModelAttribute PageQuery pageQuery) {
        return service.search(principal, status, updatedSince, pageQuery);
    }

    @GetMapping("/{shipmentNumber:SH-\\d+}")
    @PreAuthorize("hasAuthority('integration.shipment:read')")
    @Operation(summary = "One shipment with its ordered stops and assigned orders",
            description = "404 for a draft trip exactly as for one that does not exist at all - "
                    + "a plan that might still change is never exposed.")
    public ShipmentPlanV1 find(IntegrationPrincipal principal, @PathVariable String shipmentNumber) {
        return service.find(principal, shipmentNumber);
    }

    @GetMapping("/events")
    @PreAuthorize("hasAuthority('integration.shipment:read')")
    @Operation(summary = "The shipment change feed",
            description = "Cheap watermark polling: GET .../events?updatedSince=<lastSeen>, then "
                    + "fetch full detail only for the shipment numbers that changed.")
    public PageResponse<ShipmentEventV1> events(
            IntegrationPrincipal principal,
            @Parameter(description = "Only events at or after this instant (ISO-8601).")
            @RequestParam(required = false) OffsetDateTime since,
            @ModelAttribute PageQuery pageQuery) {
        return service.searchEvents(principal, since, pageQuery);
    }
}
