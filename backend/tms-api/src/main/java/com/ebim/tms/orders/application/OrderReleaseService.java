package com.ebim.tms.orders.application;

import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Bulk release (ADR-014 section 8, approved 2026-09-27): the single-order rule, called once per item.
 *
 * <p><b>Deliberately not {@code @Transactional}</b>, exactly like {@code IntegrationOrderService}:
 * each item is its own transaction through {@link OrderService#markReadyForPlanning}, so one refused
 * or failing order never rolls back the ones released beside it, and a 207 means precisely "these
 * were released, these were not, and here is why".
 */
@Service
public class OrderReleaseService {

    private final OrderService orderService;

    public OrderReleaseService(OrderService orderService) {
        this.orderService = orderService;
    }

    public BulkReleaseResult releaseAll(CompanyScope scope, BulkReleaseRequest request) {
        List<BulkReleaseResult.Item> items = new ArrayList<>(request.orderIds().size());
        for (int index = 0; index < request.orderIds().size(); index++) {
            UUID orderId = request.orderIds().get(index);
            try {
                OrderDetailView released = orderService.markReadyForPlanning(scope, orderId, request.overrideReason());
                items.add(new BulkReleaseResult.Item(index, orderId, released.orderNumber(), true,
                        released.status().name(), null, false, List.of(), null));
            } catch (ReleaseRefusal refusal) {
                items.add(new BulkReleaseResult.Item(index, orderId, refusal.orderNumber(), false, "NOT_READY",
                        refusal.assessment().eligibility(), refusal.overrideRequired(),
                        refusal.assessment().reasons(), refusal.getMessage()));
            } catch (ConflictException | ResourceNotFoundException | InvalidRequestException refused) {
                items.add(new BulkReleaseResult.Item(index, orderId, null, false, null, null, false, List.of(),
                        refused.getMessage()));
            }
        }
        return BulkReleaseResult.of(items);
    }
}
