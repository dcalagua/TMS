package com.ebim.tms.orders.api;

import com.ebim.tms.orders.application.OrderDocumentQueryService;
import com.ebim.tms.shared.reference.LogisticsDocumentPort;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The documents that travel with an order (ADR-015). Read-only. */
@RestController
@RequestMapping("${tms.api.base-path}/orders/{orderId}/documents")
@Tag(name = "Orders", description = "Transport orders")
public class OrderDocumentController {

    private final OrderDocumentQueryService queryService;

    public OrderDocumentController(OrderDocumentQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('orders.order:read')")
    @Operation(summary = "The invoices, delivery notes and remission guides recorded for this order")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<LogisticsDocumentPort.Document> list(CompanyScope scope, @PathVariable UUID orderId) {
        return queryService.documentsOf(scope, orderId);
    }
}
