package com.ebim.tms.appointments.infrastructure;

import com.ebim.tms.appointments.domain.Appointment;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Company-scoped persistence for dock bookings (migration V41). */
public interface AppointmentRepository
        extends JpaRepository<Appointment, UUID>, JpaSpecificationExecutor<Appointment> {

    Optional<Appointment> findByIdAndCompanyId(UUID id, UUID companyId);

    List<Appointment> findByCompanyIdAndTripIdOrderByWindowStartAsc(UUID companyId, UUID tripId);

    /**
     * Bookings that already hold this door across a window - the service-level half of the
     * no-double-booking rule.
     *
     * <p>The statuses match {@code ex_appointment_no_double_booking}'s {@code WHERE} clause exactly:
     * everything except {@code CANCELLED} and {@code NO_SHOW}, the two where nobody used the door.
     * This exists to give a <b>readable refusal</b>, not to be the guarantee - two dispatchers
     * booking the same slot in the same instant both pass it, and the constraint is what stops the
     * second.
     */
    @Query("""
            select a from Appointment a
            where a.companyId = :companyId and a.resourceId = :resourceId
              and a.status not in (com.ebim.tms.appointments.domain.AppointmentStatus.CANCELLED,
                                   com.ebim.tms.appointments.domain.AppointmentStatus.NO_SHOW)
              and a.windowStart < :windowEnd and a.windowEnd > :windowStart
              and (:excludeId is null or a.id <> :excludeId)
            """)
    List<Appointment> findConflicting(
            @Param("companyId") UUID companyId,
            @Param("resourceId") UUID resourceId,
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd,
            @Param("excludeId") UUID excludeId);

    /** A door's day, in visiting order - what a dock board renders. */
    @Query("""
            select a from Appointment a
            where a.companyId = :companyId and a.resourceId = :resourceId
              and a.windowStart < :to and a.windowEnd > :from
            order by a.windowStart asc
            """)
    List<Appointment> findForResourceBetween(
            @Param("companyId") UUID companyId,
            @Param("resourceId") UUID resourceId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    /** A whole site's day, across every door. */
    @Query("""
            select a from Appointment a
            where a.companyId = :companyId and a.locationId = :locationId
              and a.windowStart < :to and a.windowEnd > :from
            order by a.windowStart asc
            """)
    List<Appointment> findForLocationBetween(
            @Param("companyId") UUID companyId,
            @Param("locationId") UUID locationId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);
}
