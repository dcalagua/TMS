package com.ebim.tms.orders.api;

import com.ebim.tms.orders.application.OrderDetailView;
import com.ebim.tms.orders.application.OrderFilter;
import com.ebim.tms.orders.application.OrderRequest;
import com.ebim.tms.orders.application.OrderService;
import com.ebim.tms.orders.application.OrderView;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Company-scoped CRUD and lifecycle transitions for transport orders. Follows the
 * {@code RouteController} template: the {@link CompanyScope} parameter is only ever supplied by
 * the framework once {@code CompanyScopeFilter} has validated {@code X-Company-Id} against an
 * active membership.
 *
 * <p>List returns {@link OrderView} (a line count, not the lines themselves); every other
 * endpoint returns {@link OrderDetailView} (the full line list) - see those records' class
 * comments for why the shapes differ.
 *
 * <p>There is no delete endpoint and no generic status-set endpoint: {@code mark-ready} and
 * {@code cancel} are the only two transitions this step exposes, matching
 * {@code docs/domain/ORDER_LIFECYCLE_V1.md}. Nothing here sets {@code PLANNED} - that is
 * reserved for a future Planning module (step 10).
 */
@RestController
@RequestMapping("${tms.api.base-path}/orders")
@Tag(name = "Orders", description = "Transport orders: header, lines and the V1 status lifecycle")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('orders.order:read')")
    @Operation(summary = "List orders, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<OrderView> list(
            CompanyScope scope, @ModelAttribute OrderFilter filter, @ModelAttribute PageQuery pageQuery) {
        return orderService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('orders.order:read')")
    @Operation(summary = "Get one order, including its lines")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OrderDetailView get(CompanyScope scope, @PathVariable UUID id) {
        return orderService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('orders.order:manage')")
    @Operation(summary = "Create an order with its lines")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OrderDetailView create(CompanyScope scope, @Valid @RequestBody OrderRequest request) {
        return orderService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('orders.order:manage')")
    @Operation(summary = "Update an order while it is still editable, transactionally replacing its lines")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OrderDetailView update(CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody OrderRequest request) {
        return orderService.update(scope, id, request);
    }

    @PostMapping("/{id}/mark-ready")
    @PreAuthorize("hasAuthority('orders.order:manage')")
    @Operation(summary = "Mark a not-ready order as ready for planning, after a completeness check")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OrderDetailView markReadyForPlanning(CompanyScope scope, @PathVariable UUID id) {
        return orderService.markReadyForPlanning(scope, id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('orders.order:manage')")
    @Operation(summary = "Cancel an order that is not already planned or cancelled")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OrderDetailView cancel(
            CompanyScope scope, @PathVariable UUID id, @RequestParam(name = "reason", required = false) String reason) {
        return orderService.cancel(scope, id, reason);
    }
}
