package com.ebim.tms.iam.lifecycle.api;

import com.ebim.tms.iam.entitlements.application.JtiGuard;
import com.ebim.tms.iam.lifecycle.application.PlatformLifecycleService;
import com.ebim.tms.iam.lifecycle.domain.LifecycleRejection;
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
 * EBIM MasterAdmin's operational lifecycle contract ({@code lifecycle.v1}, V58, ADR-021).
 *
 * <pre>
 *   PUT /internal/platform-provisioning/tenants/{controlPlaneTenantId}/lifecycle   tms:lifecycle:write
 *   GET /internal/platform-provisioning/tenants/{controlPlaneTenantId}/lifecycle   tms:lifecycle:read
 * </pre>
 *
 * <p>Additive routes on the MasterAdmin chain, beside tenant creation and entitlements, with scopes of
 * their own: neither a provisioning nor an entitlements credential suspends anybody. Each scope is
 * enforced twice - structurally by the chain, and here by {@code @PreAuthorize}. Every request spends
 * its {@code jti}, including one refused for its Content-Type.
 *
 * <p>Errors are {@code {error, message, appliedVersion?}} with the contract's stable codes, and never
 * echo the request.
 */
@RestController
@RequestMapping(PlatformProvisioningPaths.BASE)
public class PlatformLifecycleController {

    private final PlatformLifecycleService lifecycle;
    private final PlatformProvisioningProperties properties;
    private final Clock clock;

    public PlatformLifecycleController(PlatformLifecycleService lifecycle, PlatformProvisioningProperties properties,
            Clock clock) {
        this.lifecycle = lifecycle;
        this.properties = properties;
        this.clock = clock;
    }

    @PutMapping("/tenants/{controlPlaneTenantId}/lifecycle")
    @PreAuthorize("hasAuthority(T(com.ebim.tms.iam.provisioning.security.PlatformScopes).AUTHORITY_LIFECYCLE_WRITE)")
    public ResponseEntity<Map<String, Object>> apply(
            @AuthenticationPrincipal PlatformCaller caller,
            @PathVariable String controlPlaneTenantId,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(name = "X-MasterAdmin-Contract", required = false) String contract) {
        JtiGuard.Call call = call(caller);
        if (!isJson(contentType)) {
            lifecycle.consume(call);
            throw new LifecycleRejection(LifecycleRejection.Code.INVALID_LIFECYCLE, "The body is application/json");
        }
        return noStore(lifecycle.apply(controlPlaneTenantId, body, contract, CorrelationId.current().orElse(null),
                call));
    }

    @GetMapping("/tenants/{controlPlaneTenantId}/lifecycle")
    @PreAuthorize("hasAuthority(T(com.ebim.tms.iam.provisioning.security.PlatformScopes).AUTHORITY_LIFECYCLE_READ)")
    public ResponseEntity<Map<String, Object>> applied(
            @AuthenticationPrincipal PlatformCaller caller,
            @PathVariable String controlPlaneTenantId) {
        return noStore(lifecycle.applied(controlPlaneTenantId, call(caller)));
    }

    @ExceptionHandler(LifecycleRejection.class)
    public ResponseEntity<Map<String, Object>> rejected(LifecycleRejection rejection) {
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
