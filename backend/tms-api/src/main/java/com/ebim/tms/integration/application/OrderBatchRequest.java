package com.ebim.tms.integration.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * A batch of transport orders. Same shape and reasoning as {@link LocationBatchRequest}.
 */
public record OrderBatchRequest(
        @NotEmpty(message = "orders must contain at least one item")
        List<@Valid @NotNull OrderUpsertRequest> orders) {

    public OrderBatchRequest {
        orders = orders == null ? List.of() : List.copyOf(orders);
    }
}
