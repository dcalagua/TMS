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
     * The change feed a partner polls: everything at or after {@code since}, oldest first with
     * {@code id} as the tie-breaker so a page boundary that lands on two events with the same
     * {@code occurred_at} is still deterministic. {@code since} must not be null: the unbounded
     * read is the overload below.
     */
    @Query("SELECT e FROM ShipmentOutboxEvent e WHERE e.companyId = :companyId "
            + "AND e.occurredAt >= :since ORDER BY e.occurredAt ASC, e.id ASC")
    Page<ShipmentOutboxEvent> findPublishable(
            @Param("companyId") UUID companyId, @Param("since") OffsetDateTime since, Pageable pageable);

    /**
     * {@link #findPublishable(UUID, OffsetDateTime, Pageable)} without a lower bound. Two queries
     * rather than {@code (:since IS NULL OR ...)}: PostgreSQL cannot type a bind parameter that
     * appears only in an {@code IS NULL} test, so the single-query form failed every call with
     * {@code could not determine data type of parameter $2} (QAS, 2026-10-22).
     */
    @Query("SELECT e FROM ShipmentOutboxEvent e WHERE e.companyId = :companyId "
            + "ORDER BY e.occurredAt ASC, e.id ASC")
    Page<ShipmentOutboxEvent> findPublishable(@Param("companyId") UUID companyId, Pageable pageable);
}
