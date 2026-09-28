package com.ebim.tms.orders.api;

import com.ebim.tms.orders.application.BulkReleaseRequest;
import com.ebim.tms.orders.application.BulkReleaseResult;
import com.ebim.tms.orders.application.OrderReleaseService;
import com.ebim.tms.orders.application.OrderSchedulingService;
import com.ebim.tms.orders.application.SchedulingFilter;
import com.ebim.tms.orders.application.SchedulingRowView;
import com.ebim.tms.orders.application.SchedulingSummaryView;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Scheduling and Release board's reads (ADR-014): which orders may be released, and why. All of
 * it is derived on read - eligibility, the release deadline and the resolved route are never stored.
 * The release itself stays {@code POST /orders/{id}/mark-ready} and, in bulk, {@code POST /orders/release}.
 */
@RestController
@RequestMapping("${tms.api.base-path}/orders")
@Tag(name = "Order scheduling", description = "Release eligibility of orders for planning (ADR-014)")
public class OrderSchedulingController {

    private final OrderSchedulingService schedulingService;
    private final OrderReleaseService releaseService;

    public OrderSchedulingController(OrderSchedulingService schedulingService, OrderReleaseService releaseService) {
        this.schedulingService = schedulingService;
        this.releaseService = releaseService;
    }

    @GetMapping("/scheduling")
    @PreAuthorize("hasAuthority('orders.order:read')")
    @Operation(summary = "The Scheduling and Release board: orders with their derived eligibility",
            description = "Defaults to NOT_READY and READY_FOR_PLANNING orders. routeCode (NONE for no route), "
                    + "eligibility and frequency are derived filters and evaluate at most 5000 matching orders.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<SchedulingRowView> search(CompanyScope scope, @ModelAttribute SchedulingFilter filter,
            @ModelAttribute PageQuery pageQuery) {
        return schedulingService.search(scope, filter, pageQuery);
    }

    @GetMapping("/scheduling/summary")
    @PreAuthorize("hasAuthority('orders.order:read')")
    @Operation(summary = "Board totals by origin, derived route and dispatch date")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public SchedulingSummaryView summary(CompanyScope scope, @ModelAttribute SchedulingFilter filter) {
        return schedulingService.summary(scope, filter);
    }

    @PostMapping("/release")
    @PreAuthorize("hasAuthority('orders.order:manage')")
    @Operation(summary = "Release several orders for planning, each judged by the single-order rule",
            description = "Items are independent: 200 when every order was released, 207 when any was refused, "
                    + "each refusal with its eligibility and reasons. One overrideReason applies to every item "
                    + "that needs one.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<BulkReleaseResult> release(CompanyScope scope,
            @Valid @RequestBody BulkReleaseRequest request) {
        BulkReleaseResult result = releaseService.releaseAll(scope, request);
        return ResponseEntity.status(result.anyRefused() ? HttpStatus.MULTI_STATUS : HttpStatus.OK).body(result);
    }

    @GetMapping("/{id}/scheduling")
    @PreAuthorize("hasAuthority('orders.order:read')")
    @Operation(summary = "One order's release eligibility and the reasons behind it")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public SchedulingRowView get(CompanyScope scope, @PathVariable UUID id) {
        return schedulingService.row(scope, id);
    }
}
