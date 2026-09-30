package com.ebim.tms.iam.provisioning.api;

import com.ebim.tms.iam.provisioning.application.GenericProvisioningRequest;
import com.ebim.tms.iam.provisioning.application.PlatformProvisioningService;
import com.ebim.tms.iam.provisioning.application.ProvisionedTenantView;
import com.ebim.tms.iam.provisioning.security.PlatformCaller;
import com.ebim.tms.iam.provisioning.security.PlatformProvisioningProperties;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.web.CorrelationId;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * EBIM MasterAdmin's provisioning contract (GENERIC v1), served by the TMS backend.
 *
 * <pre>
 *   GET  /internal/platform-provisioning/health                          no authentication
 *   POST /internal/platform-provisioning/tenants                         scope tms:tenant:create
 *   GET  /internal/platform-provisioning/tenants/{controlPlaneTenantId}  scope tms:tenant:read
 * </pre>
 *
 * <p>The scope is enforced twice: structurally by {@code PlatformProvisioningSecurityConfig}, where
 * an unlisted method under the prefix is denied, and here by {@code @PreAuthorize}, so a handler
 * moved or copied out of that matcher still refuses a token without it. The caller is a
 * {@link PlatformCaller}, never a company scope - see {@code EndpointContractTest}.
 */
@RestController
@RequestMapping(PlatformProvisioningPaths.BASE)
public class PlatformProvisioningController {

    private final PlatformProvisioningService service;
    private final PlatformProvisioningProperties properties;

    public PlatformProvisioningController(PlatformProvisioningService service,
            PlatformProvisioningProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /**
     * For MasterAdmin's CHECK_HEALTH. A fixed body with nothing in it - no version, no issuer, no
     * audience, no configuration - so an anonymous caller learns only whether the surface is on.
     * When it is on, the key was parsed at start-up (a bad one stops the application), so "on" means
     * "able to verify".
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean available = properties.enabled();
        return ResponseEntity.status(available ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("status", available ? "ok" : "unavailable"));
    }

    @PostMapping("/tenants")
    @PreAuthorize("hasAuthority(T(com.ebim.tms.iam.provisioning.security.PlatformScopes).AUTHORITY_TENANT_CREATE)")
    public ResponseEntity<ProvisionedTenantView> create(
            @AuthenticationPrincipal PlatformCaller caller,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestHeader(name = "X-MasterAdmin-Contract", required = false) String contract,
            @RequestBody GenericProvisioningRequest request) {
        ProvisionedTenantView view = service.create(caller, idempotencyKey, contract, request, correlationId());
        return ResponseEntity.status(Boolean.TRUE.equals(view.replayed()) ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(view);
    }

    @GetMapping("/tenants/{controlPlaneTenantId}")
    @PreAuthorize("hasAuthority(T(com.ebim.tms.iam.provisioning.security.PlatformScopes).AUTHORITY_TENANT_READ)")
    public ResponseEntity<ProvisionedTenantView> status(
            @AuthenticationPrincipal PlatformCaller caller,
            @PathVariable String controlPlaneTenantId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.status(caller, controlPlaneTenantId, correlationId()));
    }

    private static String correlationId() {
        return CorrelationId.current().orElse(null);
    }
}
