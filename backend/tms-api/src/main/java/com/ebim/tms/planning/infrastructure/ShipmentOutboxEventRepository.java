package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.ShipmentOutboxEvent;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link ShipmentOutboxEvent}. Every finder is scoped by {@code companyId}. */
public interface ShipmentOutboxEventRepository extends JpaRepository<ShipmentOutboxEvent, UUID> {

    /**
     * The change feed a partner polls: everything at or after {@code since} (or everything, when
     * {@code since} is null), oldest first with {@code id} as the tie-breaker so a page boundary
     * that lands on two events with the same {@code occurred_at} is still deterministic.
     */
    @Query("SELECT e FROM ShipmentOutboxEvent e WHERE e.companyId = :companyId "
            + "AND (:since IS NULL OR e.occurredAt >= :since) ORDER BY e.occurredAt ASC, e.id ASC")
    Page<ShipmentOutboxEvent> findPublishable(
            @Param("companyId") UUID companyId, @Param("since") OffsetDateTime since, Pageable pageable);
}
