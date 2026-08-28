package com.ebim.tms.shared.reference;

import java.util.Optional;
import java.util.UUID;

/**
 * How long a resource had to reposition before it could start a trip (V48, JOB 22, over V47).
 *
 * <p>Read from the work assignment JOB 21 built, where the figure was <b>frozen when the sequence
 * was validated</b> rather than derived on read. Costing uses the same number the feasibility check
 * used, so a day that was called feasible and a day that was costed cannot disagree about the same
 * empty leg.
 *
 * <p>Empty means either that no work assignment places this trip after another - so there is no
 * reposition and the trip's duty is its execution - or that the reposition could not be measured, in
 * which case the caller has to tell the two apart, which is why
 * {@link #findRepositionMinutes} returns the distinction rather than a number.
 */
public interface ResourceDutyLookupPort {

    /**
     * @return empty when no assignment sequences this trip behind another; a present
     *         {@link Reposition} whose {@code minutes} may still be null when the assignment records
     *         a join it could not measure - a day built on an unmeasured reposition, which V47
     *         reports and does not repair
     */
    Optional<Reposition> findRepositionMinutes(UUID tripId, UUID companyId);

    /** @param minutes the frozen reposition, or null when the join could not be measured */
    record Reposition(Integer minutes) {

        public boolean isMeasured() {
            return minutes != null;
        }
    }
}
