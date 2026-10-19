package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orders as {@code integration} publishes them to a partner (WAREHOUSE_EXECUTION_V1 7.4), read without
 * depending on {@code com.ebim.tms.orders}. Implemented by {@code orders.application.OrderPublicationService}.
 * Read-only; every answer is confined to {@code companyId}.
 */
public interface OrderPublicationPort {

    /** One order by its TMS number; empty for another company's or an unknown one. */
    Optional<PublishedOrder> findByOrderNumber(UUID companyId, String orderNumber);

    /**
     * The orders of {@code companyId} in one of {@code statuses} ({@code OrderStatus} names, already
     * validated by the caller), touched at or after {@code updatedSince} (all when null), oldest-touched
     * first with the id as tie-breaker, so a partner can page a backfill deterministically.
     */
    PageResponse<PublishedOrder> search(UUID companyId, Set<String> statuses, OffsetDateTime updatedSince,
            PageQuery pageQuery);
}
