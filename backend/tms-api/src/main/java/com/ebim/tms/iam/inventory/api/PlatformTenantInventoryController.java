package com.ebim.tms.iam.inventory.api;

import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.inventory.application.PlatformTenantInventoryService;
import com.ebim.tms.iam.inventory.domain.InventoryRejection;
import com.ebim.tms.iam.provisioning.api.PlatformProvisioningPaths;
import com.ebim.tms.iam.provisioning.security.PlatformCaller;
import com.ebim.tms.iam.provisioning.security.PlatformProvisioningProperties;
import com.ebim.tms.shared.web.CorrelationId;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EBIM MasterAdmin's tenant inventory ({@code tenant-inventory.v1}, V76).
 *
 * <pre>
 *   GET /internal/platform-provisioning/tenants?limit=&amp;cursor=                  TMS_PLATFORM_M2M_INVENTORY_READ_SCOPE
 *   PUT /internal/platform-provisioning/tenants/{controlPlaneTenantId}/link    TMS_PLATFORM_M2M_TENANT_LINK_SCOPE
 * </pre>
 *
 * <p>Additive routes on the MasterAdmin chain beside tenant creation ({@code POST /tenants}), status
 * ({@code GET /tenants/{id}}), entitlements and lifecycle. Their scopes are configuration
 * ({@code PlatformInventoryProperties}), enforced by the chain and again here by {@code @PreAuthorize}; an
 * unconfigured route answers {@code 503} to every authenticated caller. Every request spends its {@code jti}.
 *
 * <p>Errors are {@code {error, message}} with the contract's stable codes, and never echo the request.
 */
@RestController
@RequestMapping(PlatformProvisioningPaths.BASE)
public class PlatformTenantInventoryController {

    private final PlatformTenantInventoryService inventory;
    private final PlatformProvisioningProperties properties;
    private final Clock clock;

    public PlatformTenantInventoryController(PlatformTenantInventoryService inventory,
            PlatformProvisioningProperties properties, Clock clock) {
        this.inventory = inventory;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/tenants")
    @PreAuthorize("@platformInventoryAccess.mayList(authentication)")
    public ResponseEntity<Map<String, Object>> list(
            @AuthenticationPrincipal PlatformCaller caller,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestHeader(name = "X-MasterAdmin-Contract", required = false) String contract) {
        return noStore(inventory.list(limit, cursor, contract, CorrelationId.current().orElse(null), caller,
                call(caller)));
    }

    @PutMapping("/tenants/{controlPlaneTenantId}/link")
    @PreAuthorize("@platformInventoryAccess.mayLink(authentication)")
    public ResponseEntity<Map<String, Object>> link(
            @AuthenticationPrincipal PlatformCaller caller,
            @PathVariable String controlPlaneTenantId,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(name = "X-MasterAdmin-Contract", required = false) String contract) {
        return noStore(inventory.link(controlPlaneTenantId, body, isJson(contentType), contract,
                CorrelationId.current().orElse(null), caller, call(caller)));
    }

    @ExceptionHandler(InventoryRejection.class)
    public ResponseEntity<Map<String, Object>> rejected(InventoryRejection rejection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", rejection.code().name());
        body.put("message", rejection.getMessage());
        return ResponseEntity.status(rejection.code().status())
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** The verified caller; the {@code jti} is remembered as long as a token carrying it could be accepted. */
    private JtiGuard.Call call(PlatformCaller caller) {
        return new JtiGuard.Call(properties.issuer(), caller.subject(), caller.jti(),
                clock.instant().plus(properties.maxTokenLifetime()).plus(properties.clockSkew()));
    }

    private static boolean isJson(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        try {
            return MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON);
        } catch (InvalidMediaTypeException malformed) {
            return false;
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
