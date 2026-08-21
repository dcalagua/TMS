package com.ebim.tms.tracking.domain;

import com.ebim.tms.shared.reference.TrackedTrip;
import com.ebim.tms.shared.reference.TrackingReport;
import java.util.Optional;
import java.util.UUID;

/**
 * Where TMS <em>asks</em> a telematics provider where a vehicle is, for the providers that answer
 * questions instead of pushing answers (ADR-007).
 *
 * <p><b>Why this exists with no implementation behind it.</b> Telematics vendors split cleanly into
 * two shapes: those that push (a webhook per ping) and those that poll (an HTTP endpoint returning
 * the current fleet). The push half needs no port at all - it is an inbound endpoint, and TMS has
 * one. The pull half needs somewhere to live, and building that somewhere <em>after</em> a customer
 * arrives with a contract is how a vendor's data model ends up as the internal one: the first
 * adapter written without an interface to satisfy is the interface. So the shape is fixed here now,
 * the {@code DisabledTrackingProvider} default answers honestly, and onboarding a vendor is one
 * class in {@code tracking.infrastructure} and no change to any caller.
 *
 * <p><b>What an implementation must keep to.</b>
 *
 * <ol>
 *   <li><b>Answer or say nothing.</b> A provider that is down, slow or unsure returns empty. It
 *     never throws into a read: "where is my truck" degrades to "we do not know", never to a 500
 *     on a screen that also shows six things TMS does know.</li>
 *   <li><b>Normalise at the edge.</b> The return is a {@link TrackingReport} in TMS's own
 *     vocabulary, so nothing downstream ever learns which vendor answered - that is the whole
 *     point of the abstraction.</li>
 *   <li><b>Company first.</b> Every call names the tenant. An implementation holding one
 *     account's credentials for several tenants is expected to keep them apart; the database's
 *     scoping is the first line, this is the second, exactly as {@code EvidenceStoragePort}
 *     requires.</li>
 *   <li><b>Cost is the caller's to control.</b> An implementation makes at most one upstream call
 *     per invocation and does not poll in the background. Anything scheduled is a decision about
 *     somebody's API quota and belongs where it can be configured, not inside an adapter.</li>
 * </ol>
 *
 * <p>There is deliberately no {@code subscribe} and no {@code registerVehicle}. Both are real
 * vendor operations and neither has a caller in TMS; an interface with a method nobody may call is
 * an invitation, which is the rule {@code EvidenceStoragePort} states about its missing delete.
 */
public interface TrackingProviderPort {

    /**
     * Whether this deployment can pull positions at all. A read asks before offering "no tracking
     * configured" versus "configured, nothing reported yet" - two different answers a dispatcher
     * needs to tell apart, because only one of them is somebody's job to fix.
     */
    boolean isEnabled();

    /**
     * The slug recorded as {@code provider} on anything this adapter supplies. Lowercase, matching
     * {@code ck_tracking_position_provider_shape}, and stable across deployments: it is what a
     * stored position is attributed to years later.
     */
    String providerCode();

    /**
     * The provider's current position for the vehicle running {@code trip}, or empty when it has
     * none, when the vehicle is unknown to it, or when it could not be reached.
     *
     * <p>Takes the trip rather than a vehicle id because the vendor's key is not ours: an adapter
     * matches on {@link TrackedTrip#vehicleLicensePlate()} or on its own mapping from it, and the
     * shipment number is what it correlates with when it has one. Resolving that is the adapter's
     * job precisely so no caller has to know how a given vendor identifies a truck.
     *
     * @return a report already in TMS's vocabulary, carrying this adapter's {@link #providerCode()}
     */
    Optional<TrackingReport> lastKnownPosition(UUID companyId, TrackedTrip trip);
}
