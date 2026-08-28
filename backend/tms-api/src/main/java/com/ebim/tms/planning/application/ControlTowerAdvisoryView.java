package com.ebim.tms.planning.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Something worth knowing about a shipment that <b>does not stop it</b> (JOB 23, Control Tower V3).
 *
 * <h2>Why this is not on the blockers panel</h2>
 *
 * A {@link ControlTowerBlockerView} is a state that makes {@code dispatch} refuse: the truck is not
 * going anywhere until somebody fixes it. An advisory is a fact a supervisor should know and may
 * reasonably do nothing about today. Putting the two in one list is how a panel stops being read -
 * once a screen has cried wolf about a rounding difference, the shipment that genuinely cannot
 * depart is one row among forty.
 *
 * <p>JOB 12 kept the blockers panel to hard stops for exactly this reason and named the alternative
 * as not-built. V3 adds the advisories <b>beside</b> it rather than into it.
 *
 * <h2>Nothing here owns any state</h2>
 *
 * Every advisory is read from the module that owns the fact and is never copied. A settlement
 * discrepancy is resolved on the settlement screen; this row links to it and offers no way to close
 * it. Two records of one dispute would drift apart the first time somebody resolved the wrong one.
 *
 * @param type      what kind of thing this is - the UI groups on it and each has a different fix
 * @param tripId    the shipment it concerns, always present: an advisory about nothing in
 *                  particular belongs on a dashboard, not on a day's control tower
 * @param sourceId  the id of the record in its owning module, so the UI can link straight to it
 * @param amount    the money involved where there is any, or <b>null</b> - never zero standing in
 *                  for "the two sides could not be compared" (V46's rule, carried through)
 * @param detail    the sentence, composed by whoever owns the fact rather than assembled here
 */
public record ControlTowerAdvisoryView(
        AdvisoryType type,
        UUID tripId,
        String shipmentNumber,
        UUID sourceId,
        BigDecimal amount,
        String currency,
        String detail) {

    /**
     * The kinds of advisory V3 raises.
     *
     * <p>Two, not a catalogue. Each has a real source and a real fix; an advisory nobody can act on
     * is noise wearing a severity, and the fastest way to make this panel worthless is to fill it
     * with things that are merely true.
     */
    public enum AdvisoryType {

        /**
         * A carrier's invoice disagrees with what we expected this shipment to cost (V46).
         *
         * <p>Advisory and not a blocker on purpose: the truck ran, the goods arrived, and the money
         * question is settled afterwards by somebody else. Resolved in Settlement, never here.
         */
        SETTLEMENT_DISCREPANCY_OPEN,

        /**
         * The arrival estimate for a stop falls outside its service window (V43,
         * {@code eta_misses_window}).
         *
         * <p>An estimate, and the panel says so. It is not the same fact as a stop that <em>has</em>
         * run late, which is what {@code outstandingStops} reports - one is a prediction somebody
         * can still act on and the other is history. Keeping them apart is the difference between
         * "leave earlier" and "call the customer".
         */
        STOP_ETA_MISSES_WINDOW
    }
}
