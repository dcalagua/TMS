package com.ebim.tms.planning.application;

/**
 * Proposes how a day's orders could be loaded onto a day's vehicles.
 *
 * <p>The interface exists before there is a second implementation, and that is the point. V1 is
 * {@link HeuristicPlanningEngine}: a few explainable rules a dispatcher can argue with. A solver
 * (OR-Tools) is deferred by decision, and when it arrives it must be able to sit beside this one
 * without planning being rebuilt around it - which means everything that is <em>not</em> the
 * choice of algorithm has to live outside the engine. It does:
 *
 * <ul>
 *   <li>eligibility, loading and materialisation are {@code AutoPlanningService}'s;</li>
 *   <li>capacity limits, double-booking, stop derivation and order state transitions are
 *       {@code TripService}'s, and an engine's proposal is put through them like any other
 *       write;</li>
 *   <li>what remains here is one pure function from a snapshot to a proposal.</li>
 * </ul>
 *
 * <p>Pure is not an accident either. An implementation takes no repository, touches no clock and
 * draws no random number, so it is provable by unit test on a machine with no database - which is
 * this one (BASELINE E-1). The same property makes a proposal reproducible: the same input plans
 * the same way twice, which is what lets a planner ask "why did it do that" and get an answer.
 *
 * <p>An engine never confirms anything. It returns draft trips for a planner to edit, and
 * {@code docs/domain/PLANNING_SHIPMENT.md} says so as a product rule, not an implementation note.
 */
public interface PlanningEngine {

    /**
     * A stable identifier for the algorithm, recorded on the proposal so a plan can be traced
     * back to the rules that produced it after those rules have changed.
     */
    String name();

    /** One snapshot in, one proposal out. Never mutates {@code input}, never touches the world. */
    PlanningProposal plan(PlanningInput input);
}
