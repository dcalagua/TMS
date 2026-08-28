package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.ResourceUnavailability;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Company-scoped persistence for {@link ResourceUnavailability}. Every finder is scoped by {@code companyId}. */
public interface ResourceUnavailabilityRepository extends JpaRepository<ResourceUnavailability, UUID> {

    Optional<ResourceUnavailability> findByIdAndCompanyId(UUID id, UUID companyId);

    List<ResourceUnavailability> findByCompanyIdAndVehicleIdOrderByStartsAtDesc(UUID companyId, UUID vehicleId);

    List<ResourceUnavailability> findByCompanyIdAndDriverIdOrderByStartsAtDesc(UUID companyId, UUID driverId);

    /**
     * The blocks covering an instant for either of two resources, in one round trip.
     *
     * <p>One query rather than two because it answers one question - "can this shipment leave" -
     * and two queries would let a caller forget the second. The half-open comparison matches
     * {@code tstzrange(starts_at, ends_at)} exactly: a block ending at 10:00 does not cover 10:00.
     */
    @Query("""
            select b from ResourceUnavailability b
            where b.companyId = :companyId
              and b.startsAt <= :at and b.endsAt > :at
              and ((:vehicleId is not null and b.vehicleId = :vehicleId)
                or (:driverId is not null and b.driverId = :driverId))
            order by b.endsAt desc
            """)
    List<ResourceUnavailability> findCovering(@Param("companyId") UUID companyId,
            @Param("vehicleId") UUID vehicleId, @Param("driverId") UUID driverId, @Param("at") OffsetDateTime at);

    /**
     * Whether anything blocks these resources over a window - the question planning asks about a
     * whole day, as against {@link #findCovering}'s single instant. Overlap, not containment: a
     * two-hour workshop slot in the middle of a shift blocks the shift.
     */
    @Query("""
            select b from ResourceUnavailability b
            where b.companyId = :companyId
              and b.startsAt < :until and b.endsAt > :from
              and ((:vehicleId is not null and b.vehicleId = :vehicleId)
                or (:driverId is not null and b.driverId = :driverId))
            """)
    List<ResourceUnavailability> findOverlapping(@Param("companyId") UUID companyId,
            @Param("vehicleId") UUID vehicleId, @Param("driverId") UUID driverId,
            @Param("from") OffsetDateTime from, @Param("until") OffsetDateTime until);
}
