package com.ebim.tms.tracking.infrastructure;

import com.ebim.tms.shared.reference.TrackedTrip;
import com.ebim.tms.shared.reference.TrackingReport;
import com.ebim.tms.tracking.domain.TrackingProviderPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The default provider: there isn't one.
 *
 * <p>{@link #lastKnownPosition} answers empty and {@link #isEnabled} answers false. That is the
 * whole implementation, and - as with {@code DisabledEvidenceStorage} - it is the point of it. TMS
 * ships with no telematics vendor, no credential for one and no adapter that speaks anybody's
 * protocol, because writing one against a vendor no customer has bought is how a vendor's data
 * model becomes the internal one (ADR-007).
 *
 * <p>Unlike the evidence default, this one does <em>not</em> throw. Refusing loudly is right when
 * somebody is trying to store a customer's signed delivery note and TMS has nowhere to put it -
 * they must not believe it was filed. It is wrong here: nobody asked this class for anything, a
 * read did, and "we have no position for this shipment" is a complete and true answer that the
 * screen already knows how to show. A 503 in its place would break a page over a feature the
 * deployment never turned on.
 *
 * <p>Reachable only through {@link TrackingProviderPort}, and pushed positions are entirely
 * unaffected: a deployment receiving a feed over the inbound API has full tracking with this bean
 * in place. This is the <em>pull</em> half - see the port's class comment for why the two halves
 * are different shapes.
 */
@Component
class DisabledTrackingProvider implements TrackingProviderPort {

    /**
     * Never stored anywhere: this adapter produces no positions. It exists because the port
     * promises a code and a null would be the one value a caller might record.
     */
    private static final String CODE = "none";

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String providerCode() {
        return CODE;
    }

    @Override
    public Optional<TrackingReport> lastKnownPosition(UUID companyId, TrackedTrip trip) {
        return Optional.empty();
    }
}
