package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.shared.reference.ResourceDutyLookupPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link ResourceDutyLookupPort} (V48, JOB 22, over V47).
 *
 * <p>Returns the reposition <b>as it was frozen when the sequence was validated</b>, never
 * re-derived. A re-derived figure would drift as the routing cache changes, and a day called
 * feasible on one number and costed on another is two answers about one empty leg.
 */
@Component
public class ResourceDutyLookupAdapter implements ResourceDutyLookupPort {

    private final WorkAssignmentRepository workAssignmentRepository;

    public ResourceDutyLookupAdapter(WorkAssignmentRepository workAssignmentRepository) {
        this.workAssignmentRepository = workAssignmentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Reposition> findRepositionMinutes(UUID tripId, UUID companyId) {
        return workAssignmentRepository.findSequencedTrip(tripId, companyId)
                // Position 0 is the first shipment of the resource's day: it repositions from
                // nowhere, so its duty is its execution and there is nothing to add.
                .filter(assignmentTrip -> assignmentTrip.sequence() > 0)
                .map(assignmentTrip -> new Reposition(assignmentTrip.repositionMinutes()));
    }
}
