package com.ebim.tms.orders.api;

import com.ebim.tms.orders.application.IssuedTrackingLinkView;
import com.ebim.tms.orders.application.RevokeTrackingLinkRequest;
import com.ebim.tms.orders.application.TrackingLinkService;
import com.ebim.tms.orders.application.TrackingLinkView;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer tracking links of an order (migration V74, ADR-029): the office side. Every handler needs
 * both {@code orders.order:read} and {@code orders.tracking_link:manage} - publishing a view of an order
 * starts with being allowed to see it.
 */
@RestController
@RequestMapping("${tms.api.base-path}/orders/{orderId}/tracking-links")
@Tag(name = "Order tracking links", description = "Generate, list and revoke the customer tracking links of an order")
public class TrackingLinkController {

    private static final String GUARD =
            "hasAuthority('orders.order:read') and hasAuthority('orders.tracking_link:manage')";

    private final TrackingLinkService service;

    public TrackingLinkController(TrackingLinkService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(GUARD)
    @Operation(summary = "List an order's tracking links, newest first, with their state, expiry and views")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<TrackingLinkView> list(CompanyScope scope, @PathVariable UUID orderId) {
        return service.list(scope, orderId);
    }

    @PostMapping
    @PreAuthorize(GUARD)
    @Operation(summary = "Generate a tracking link for the order",
            description = "The response is the only place the token ever appears: it is stored hashed. The link "
                    + "expires 30 days after creation or 7 days after the order closes, whichever is first.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<IssuedTrackingLinkView> issue(CompanyScope scope, @PathVariable UUID orderId) {
        // no-store: the body carries a bearer secret, and no cache between here and the office's
        // browser has any business keeping it.
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(service.issue(scope, orderId));
    }

    @PostMapping("/{linkId}/revoke")
    @PreAuthorize(GUARD)
    @Operation(summary = "Revoke a tracking link, with the reason; it stops working at once")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TrackingLinkView revoke(CompanyScope scope, @PathVariable UUID orderId, @PathVariable UUID linkId,
            @Valid @RequestBody RevokeTrackingLinkRequest request) {
        return service.revoke(scope, orderId, linkId, request);
    }
}
