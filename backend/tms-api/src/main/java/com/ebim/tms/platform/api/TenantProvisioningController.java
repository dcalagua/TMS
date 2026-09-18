package com.ebim.tms.platform.api;

import com.ebim.tms.platform.application.ProvisioningCaller;
import com.ebim.tms.platform.application.ProvisioningExecutor.ProvisioningOutcome;
import com.ebim.tms.platform.application.TenantProvisioningRequest;
import com.ebim.tms.platform.application.TenantProvisioningService;
import com.ebim.tms.platform.application.TenantProvisioningView;
import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.api.ProblemTypedException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Control-plane tenant provisioning: the two calls EBIM MasterAdmin makes into TMS.
 *
 * <p>V1 has no delete, no suspend and no activate, and their absence is a decision rather than a
 * gap. Deleting a tenant would mean deleting a customer's transport history, which no control plane
 * should be able to do over an API; suspending one means deciding what happens to a trip that is
 * already on the road. Both need a design conversation this endpoint does not get to short-circuit.
 */
@RestController
@RequestMapping(PlatformApiPaths.V1 + "/tenants")
@Tag(name = "Platform provisioning", description = "Tenant provisioning for the EBIM control plane")
public class TenantProvisioningController {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningController.class);

    private final TenantProvisioningService service;

    public TenantProvisioningController(TenantProvisioningService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('tms:tenant:create')")
    @Operation(summary = "Provision a tenant",
            description = "Creates the organization, its first company and - optionally - a preprovisioned "
                    + "administrator. Requires Idempotency-Key. Replaying the same key with the same body "
                    + "returns the original answer with 200; a different body is refused with 409.")
    public ResponseEntity<TenantProvisioningView> provision(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TenantProvisioningRequest request,
            ProvisioningCaller caller) {

        String key = requireIdempotencyKey(idempotencyKey);
        ProvisioningOutcome outcome = service.provision(request, caller, key,
                CorrelationId.current().orElse("unknown"));

        // 201 for the call that created the tenant, 200 for a replay: a control plane that retried
        // after a timeout should be able to tell which of its calls did the work.
        return ResponseEntity
                .status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(outcome.view());
    }

    @GetMapping("/{controlPlaneTenantId}")
    @PreAuthorize("hasAuthority('tms:tenant:read')")
    @Operation(summary = "Read a provisioned tenant",
            description = "Provisioning state and the identifier mapping. Never business data: no orders, "
                    + "no routes, no vehicles.")
    public TenantProvisioningView get(@PathVariable UUID controlPlaneTenantId, ProvisioningCaller caller) {
        // Reads are not recorded in the database - only writes produce a provisioning row - so the
        // log is where "who asked about this tenant, and when" lives.
        log.debug("Control-plane read of tenant {} by subject {}", controlPlaneTenantId, caller.subject());
        return service.find(controlPlaneTenantId)
                .orElseThrow(() -> new ResourceNotFoundException("No tenant is provisioned for that identifier."));
    }

    private static String requireIdempotencyKey(String header) {
        if (header == null || header.isBlank()) {
            throw new ProblemTypedException(ProblemType.IDEMPOTENCY_KEY_REQUIRED,
                    "Idempotency-Key is required on this operation.");
        }
        String key = header.trim();
        if (key.length() > 255) {
            throw new ProblemTypedException(ProblemType.IDEMPOTENCY_KEY_REQUIRED,
                    "Idempotency-Key must be at most 255 characters.");
        }
        return key;
    }

}
