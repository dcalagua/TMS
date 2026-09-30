package com.ebim.tms.orders.api;

import com.ebim.tms.orders.application.OrderHoldService;
import com.ebim.tms.orders.application.OrderHoldView;
import com.ebim.tms.orders.application.PlaceHoldRequest;
import com.ebim.tms.orders.application.ReleaseHoldRequest;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Holds on an order (migration V54, ADR-014 section 7). Reading them needs only
 * {@code orders.order:read}; placing and lifting them is {@code orders.hold:manage}, a commercial
 * authority separate from keying orders. There is no delete: a hold is lifted, with a reason.
 */
@RestController
@RequestMapping("${tms.api.base-path}/orders/{orderId}/holds")
@Tag(name = "Order holds", description = "Place and lift holds that stop an order's release, planning and dispatch")
public class OrderHoldController {

    private final OrderHoldService holdService;

    public OrderHoldController(OrderHoldService holdService) {
        this.holdService = holdService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('orders.order:read')")
    @Operation(summary = "List an order's holds, newest first, active and lifted")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<OrderHoldView> list(CompanyScope scope, @PathVariable UUID orderId) {
        return holdService.list(scope, orderId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('orders.hold:manage')")
    @Operation(summary = "Place a hold on an order",
            description = "A blocking hold stops the order's release and planning. On an order already on a trip it "
                    + "unplans nothing: it blocks the trip's dispatch and raises ORDER_HOLD_ON_COMMITTED_TRIP.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OrderHoldView place(CompanyScope scope, @PathVariable UUID orderId,
            @Valid @RequestBody PlaceHoldRequest request) {
        return holdService.place(scope, orderId, request);
    }

    @PostMapping("/{holdId}/release")
    @PreAuthorize("hasAuthority('orders.hold:manage')")
    @Operation(summary = "Lift a hold, with the reason it no longer applies")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OrderHoldView release(CompanyScope scope, @PathVariable UUID orderId, @PathVariable UUID holdId,
            @Valid @RequestBody ReleaseHoldRequest request) {
        return holdService.release(scope, orderId, holdId, request);
    }
}
