package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way a business module other than {@code planning} may read a planned trip as an
 * external <em>Shipment</em>, without depending on {@code com.ebim.tms.planning} directly
 * ({@code ModuleBoundaryTest}).
 *
 * <p>Job 08's outbound integration is the only caller today: {@code com.ebim.tms.integration}
 * maps what this port returns into the versioned {@code ShipmentPlan V1} wire contract
 * (see {@code docs/integrations/OUTBOUND_SHIPMENT_V1.md}), and the mapping is deliberately a
 * separate step - this port's records may change for reasons internal to how {@code planning}
 * models a trip, and the wire contract must not move just because they did.
 *
 * <p>Only {@link com.ebim.tms.planning.domain.TripStatus#CONFIRMED} and {@code CANCELLED} trips
 * are ever returned: a {@code DRAFT} trip is a planner's work in progress, not yet a commitment
 * to run, and publishing one outside TMS would tell an ERP about a shipment that might still be
 * torn up. {@code planning.infrastructure.ShipmentPublicationAdapter} is the only implementation.
 */
public interface ShipmentPublicationPort {

    /**
     * The header row of every publishable shipment matching the filter, paginated and batched -
     * never one query per row, following the discipline {@code TripViewAssembler} established for
     * the internal board. Never carries stops or orders; see {@link #findDetail} for those.
     */
    PageResponse<PublishedShipment> search(ShipmentPublicationQuery query, PageQuery pageQuery);

    /**
     * One shipment with its ordered stops and the orders assigned to it, or empty when no
     * publishable (confirmed or cancelled) shipment of that number exists in this company - a
     * draft trip answers empty too, exactly as if it did not exist, rather than exposing a plan
     * that might still change.
     */
    Optional<PublishedShipmentDetail> findDetail(UUID companyId, String shipmentNumber);

    /**
     * The outbox feed: what changed since {@link ShipmentEventQuery#since()}, oldest first, so a
     * partner can poll a cheap watermark instead of re-listing every confirmed shipment on every
     * call. See migration V20 and {@code docs/integrations/OUTBOUND_SHIPMENT_V1.md}, "Change
     * feed".
     */
    PageResponse<PublishedShipmentEvent> searchEvents(ShipmentEventQuery query, PageQuery pageQuery);
}
