package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.WorkAssignment;
import com.ebim.tms.fleet.domain.WorkAssignmentTrip;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Company-scoped persistence for {@link WorkAssignment}. Every finder is scoped by {@code companyId}. */
public interface WorkAssignmentRepository extends JpaRepository<WorkAssignment, UUID> {

    Optional<WorkAssignment> findByIdAndCompanyId(UUID id, UUID companyId);

    List<WorkAssignment> findByCompanyIdAndOperationalDateOrderByCreatedAtAsc(UUID companyId, LocalDate date);

    /**
     * The assignment under a write lock, for every operation that revalidates the day.
     *
     * <p>Adding, removing or reordering a shipment reads the whole sequence, checks it and writes it
     * back. Two dispatchers doing that at the same second would each validate against a day the
     * other is about to change; the lock serialises them, and the {@code @Version} column catches
     * anything that still slips past.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    /**
     * The sequenced position of one shipment in whatever assignment holds it (V48, JOB 22).
     *
     * <p>Read by own-fleet costing to charge the driver and the vehicle for the empty run into the
     * shipment. Returns the row rather than the minutes so the caller can tell "position 0, nothing
     * to reposition from" apart from "sequenced, and the join could not be measured" - which cost
     * differently and are repaired by different people.
     *
     * <p>A shipment appears in at most one assignment: {@code uq_work_assignment_trip} (V47) makes
     * a second one impossible, which is why this returns one row and not a list.
     */
    @Query("select t from WorkAssignment a join a.trips t "
            + "where t.tripId = :tripId and a.companyId = :companyId")
    Optional<WorkAssignmentTrip> findSequencedTrip(@Param("tripId") UUID tripId,
            @Param("companyId") UUID companyId);

    @Query("select a from WorkAssignment a where a.id = :id and a.companyId = :companyId")
    Optional<WorkAssignment> findByIdAndCompanyIdForUpdate(@Param("id") UUID id,
            @Param("companyId") UUID companyId);
}
