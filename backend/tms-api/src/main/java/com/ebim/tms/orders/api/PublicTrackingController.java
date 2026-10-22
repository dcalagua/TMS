package com.ebim.tms.orders.api;

import com.ebim.tms.orders.application.PublicTrackingService;
import com.ebim.tms.orders.application.PublicTrackingView;
import com.ebim.tms.orders.domain.TrackingToken;
import com.ebim.tms.shared.api.RateLimitedException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer tracking link, read anonymously (migration V74, ADR-029).
 *
 * <p><b>The one business endpoint with no {@code @PreAuthorize}</b>, listed as such in
 * {@code EndpointContractTest}. It is served by {@code PublicTrackingSecurityConfig}, a chain that reads no
 * bearer token and permits exactly this {@code GET}; the token in the path is the whole credential, and it
 * names one order of one company. What it returns is {@link PublicTrackingView} and nothing else.
 *
 * <p>Order of the checks: the rate limits first (no database work for a throttled caller), then the
 * service, whose every refusal is the same 404 - and each of those counts as a miss for the address.
 */
@RestController
@RequestMapping("${tms.api.base-path}/public/tracking")
@Tag(name = "Public tracking", description = "Anonymous, read-only view of one order through its tracking link")
public class PublicTrackingController {

    private final PublicTrackingService service;
    private final PublicTrackingRateLimiter rateLimiter;

    public PublicTrackingController(PublicTrackingService service, PublicTrackingRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/{token}")
    @Operation(summary = "What a customer sees through a tracking link",
            description = "No authentication: the token is the credential. Unknown, malformed, expired and revoked "
                    + "links all answer the same 404. Rate limited per client address (429 with Retry-After).")
    public ResponseEntity<PublicTrackingView> view(@PathVariable String token, HttpServletRequest request) {
        String client = request.getRemoteAddr();
        // A malformed token gets a key of its own rather than its own text: an attacker's garbage must not
        // become thousands of per-token windows.
        String tokenKey = TrackingToken.isWellFormed(token) ? TrackingToken.hashOf(token) : "malformed";
        long wait = rateLimiter.admit(client, tokenKey);
        if (wait > 0) {
            throw new RateLimitedException(wait);
        }
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(service.view(token));
        } catch (ResourceNotFoundException miss) {
            rateLimiter.recordMiss(client);
            throw miss;
        }
    }
}
