package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.security.CompanyScope;
import java.util.List;

/**
 * The one way a module other than {@code tracking} writes a position - the tracking sibling of
 * {@link OrderIntakePort} and {@link LocationIntakePort}, and the reason
 * {@code com.ebim.tms.integration} can host a tracking endpoint without depending on
 * {@code com.ebim.tms.tracking} ({@code ModuleBoundaryTest}).
 *
 * <p><b>Batch-shaped, unlike its two siblings, and that is a deliberate difference.</b> An order
 * upsert is a business decision per object, so {@code OrderIntakePort} takes one and the caller
 * loops. A position is a measurement, they arrive in runs of tens to hundreds, and two of the three
 * rules that decide what happens to one - the sampling interval and the staleness cut-off - are
 * statements about the <em>sequence</em>. A one-at-a-time port would either re-read the newest
 * stored position per ping or leave the caller to thin the run itself, and the second is how the
 * policy ends up implemented twice and differently.
 *
 * <p><b>Shipments are named by number, never by uuid.</b> A partner learns shipment numbers from
 * the outbound {@code ShipmentPlan V1} contract and from the paperwork; it never learns our
 * primary keys, and an API that required one would force every telematics integration to keep a
 * mapping table it has no other use for. The number is resolved inside the caller's company, so a
 * payload naming another tenant's shipment sees exactly what a payload naming a nonexistent one
 * sees - the same discipline every cross-module lookup in TMS follows.
 */
public interface TrackingIntakePort {

    /**
     * Records a run of positions, one result per report, at the index it was sent.
     *
     * <p>Per-item independence: a report naming an unknown shipment costs the other 199 nothing,
     * for the reason {@code OrderBatchResult} gives. Nothing here throws for a bad <em>item</em> -
     * a refusal is a value, because a run of positions has no all-or-nothing meaning and rolling
     * back 200 good points over one typo would lose data that is correct.
     *
     * <p>Runs in one transaction. A position is not a business commitment and nothing else in TMS
     * observes it, so the whole run standing or falling together is both cheap and irrelevant to
     * anybody - unlike a shipment publication, which is why this makes no promise about ordering
     * against other work.
     */
    List<TrackingIntakeResult> record(CompanyScope scope, List<TrackingReport> reports);
}
