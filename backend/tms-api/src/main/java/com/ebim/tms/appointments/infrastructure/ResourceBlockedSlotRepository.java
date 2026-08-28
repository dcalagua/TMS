package com.ebim.tms.appointments.infrastructure;

import com.ebim.tms.appointments.domain.ResourceBlockedSlot;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Closures on one door. */
public interface ResourceBlockedSlotRepository extends JpaRepository<ResourceBlockedSlot, UUID> {

    /**
     * Closures overlapping a window.
     *
     * <p>Half-open on both sides, matching {@code tstzrange}'s {@code &&}: a booking that ends
     * exactly when a closure begins does not overlap it. Any other convention here would give a
     * different answer from the database on the one case that is hardest to reason about.
     */
    @Query("""
            select b from ResourceBlockedSlot b
            where b.companyId = :companyId and b.resourceId = :resourceId
              and b.startsAt < :windowEnd and b.endsAt > :windowStart
            """)
    List<ResourceBlockedSlot> findOverlapping(
            @Param("companyId") UUID companyId,
            @Param("resourceId") UUID resourceId,
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd);

    List<ResourceBlockedSlot> findByCompanyIdAndResourceIdOrderByStartsAtAsc(UUID companyId, UUID resourceId);
}
