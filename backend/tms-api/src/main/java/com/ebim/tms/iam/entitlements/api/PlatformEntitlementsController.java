package com.ebim.tms.iam.entitlements.api;

import com.ebim.tms.iam.entitlements.application.ApplyEntitlementSnapshotService;
import com.ebim.tms.iam.entitlements.application.EntitlementQueryService;
import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.entitlements.domain.EntitlementRejection;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * EBIM MasterAdmin's commercial entitlements contract ({@code ebim.entitlements/v1}, FIX-ENT-v1).
 *
 * <pre>
 *   PUT /internal/platform-provisioning/tenants/{controlPlaneTenantId}/entitlements   tms:entitlements:write
 *   GET /internal/platform-provisioning/tenants/{controlPlaneTenantId}/entitlements   tms:entitlements:read
 *   GET /internal/platform-provisioning/entitlements/manifest                         tms:entitlements:read
 * </pre>
 *
 * <p>Additive routes on the MasterAdmin chain, with scopes of their own: a token that may create a
 * tenant cannot write entitlements, and one that writes entitlements cannot create a tenant. Tenant
 * creation ({@code PlatformProvisioningController}) is unchanged (INV-1). As there, each scope is
 * enforced twice - structurally by the chain, and here by {@code @PreAuthorize}.
 *
 * <p>Errors are the contract's {@code {error, message, appliedVersion?}} and never echo the snapshot.
 * Every request spends its {@code jti}, including one refused for its Content-Type, so a replay cannot
 * try again with another header.
 */
@RestController
@RequestMapping(PlatformProvisioningPaths.BASE)
public class PlatformEntitlementsController {

    private final ApplyEntitlementSnapshotService apply;
    private final EntitlementQueryService queries;
    private final JtiGuard jti;
    private final PlatformProvisioningProperties properties;
    private final Clock clock;

    public PlatformEntitlementsController(ApplyEntitlementSnapshotService apply, EntitlementQueryService queries,
            JtiGuard jti, PlatformProvisioningProperties properties, Clock clock) {
        this.apply = apply;
        this.queries = queries;
        this.jti = jti;
        this.properties = properties;
        this.clock = clock;
    }

    @PutMapping("/tenants/{controlPlaneTenantId}/entitlements")
    @PreAuthorize("hasAuthority(T(com.ebim.tms.iam.provisioning.security.PlatformScopes).AUTHORITY_ENTITLEMENTS_WRITE)")
    public ResponseEntity<Map<String, Object>> apply(
            @AuthenticationPrincipal PlatformCaller caller,
            @PathVariable String controlPlaneTenantId,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(name = "X-MasterAdmin-Contract", required = false) String contract) {
        JtiGuard.Call call = call(caller);
        if (!isJson(contentType)) {
            jti.consume(call);
            throw new EntitlementRejection(EntitlementRejection.Code.UNSUPPORTED_MEDIA_TYPE,
                    "The snapshot is application/json");
        }
        return noStore(apply.apply(controlPlaneTenantId, body, contract, CorrelationId.current().orElse(null), call));
    }

    @GetMapping("/tenants/{controlPlaneTenantId}/entitlements")
    @PreAuthorize("hasAuthority(T(com.ebim.tms.iam.provisioning.security.PlatformScopes).AUTHORITY_ENTITLEMENTS_READ)")
    public ResponseEntity<Map<String, Object>> applied(
            @AuthenticationPrincipal PlatformCaller caller,
            @PathVariable String controlPlaneTenantId) {
        return noStore(queries.applied(controlPlaneTenantId, call(caller)));
    }

    @GetMapping("/entitlements/manifest")
    @PreAuthorize("hasAuthority(T(com.ebim.tms.iam.provisioning.security.PlatformScopes).AUTHORITY_ENTITLEMENTS_READ)")
    public ResponseEntity<byte[]> manifest(@AuthenticationPrincipal PlatformCaller caller) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(queries.manifest(call(caller)));
    }

    @ExceptionHandler(EntitlementRejection.class)
    public ResponseEntity<Map<String, Object>> rejected(EntitlementRejection rejection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", rejection.code().name());
        body.put("message", rejection.getMessage());
        if (rejection.appliedVersion() != null) {
            body.put("appliedVersion", rejection.appliedVersion());
        }
        return ResponseEntity.status(rejection.code().status())
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * The verified caller. The decoder bounds every accepted token to {@code maxTokenLifetime} plus the
     * clock skew, so remembering the {@code jti} that long from now outlives any token that carries it.
     */
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
