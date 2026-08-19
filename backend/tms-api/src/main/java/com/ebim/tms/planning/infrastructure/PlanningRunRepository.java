package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.PlanningRunStatus;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/**
 * Company-scoped persistence for {@link PlanningRun}. See {@code OriginRepository} for the
 * isolation rule every finder here follows: no method returns a row without a {@code companyId}
 * in its own predicate.
 */
public interface PlanningRunRepository extends JpaRepository<PlanningRun, UUID>, JpaSpecificationExecutor<PlanningRun> {

    Optional<PlanningRun> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * The caller-facing half of {@code uq_planning_run_open_scope}: one open draft per
     * company/origin/planning date. Checked before the insert so the planner gets a sentence
     * instead of a constraint name; the partial unique index remains the backstop for a race.
     */
    boolean existsByCompanyIdAndOriginIdAndPlanningDateAndStatus(
            UUID companyId, UUID originId, LocalDate planningDate, PlanningRunStatus status);

    /**
     * The next value of {@code tms.planning_run_number_seq} - {@code PlanningRunService} formats
     * it into {@code plan_number}. A plain {@code nextval()} call, not an entity read, so it
     * never participates in optimistic locking or the persistence context (the same shape as
     * {@code TransportOrderRepository.nextOrderNumberValue}).
     */
    @Query(value = "SELECT nextval('tms.planning_run_number_seq')", nativeQuery = true)
    long nextPlanNumberValue();
}
