package com.ebim.tms.shared.reference;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way {@code tracking} resolves a trip without depending on {@code com.ebim.tms.planning}
 * (rule 10 in {@code docs/database/DATA_MODEL.md} section 13), and the counterpart of
 * {@link ShipmentPublicationPort} for a reader that needs six fields rather than a shipment
 * document.
 *
 * <p>Both methods take the company and return empty rather than throwing for a trip of another
 * tenant, so tracking answers 404 without ever learning whether the shipment exists somewhere
 * else - the discipline {@link DriverLookupPort} states and every cross-module lookup here keeps.
 */
public interface TripTrackingLookupPort {

    /**
     * Resolves one trip by its primary key, for the read side: a screen already holds the id it
     * navigated with. Returns any status, including {@code DRAFT} - reading where a shipment got
     * to is not the same permission as reporting where it is, and a completed trip's track is
     * exactly what somebody reviewing yesterday wants.
     */
    Optional<TrackedTrip> findById(UUID companyId, UUID tripId);

    /**
     * Resolves shipment numbers to trips in one call, for the write side - see
     * {@link TrackingIntakePort} for why a partner names shipments and not uuids.
     *
     * <p>Batched and not one-by-one because a run of positions is routinely one shipment repeated
     * two hundred times, or five shipments repeated forty: resolving per report would be two
     * hundred queries for at most a handful of distinct answers. Numbers with no trip in this
     * company are simply absent from the map, which is what the caller reports as
     * {@link TrackingIntakeOutcome#UNKNOWN_SHIPMENT}.
     */
    Map<String, TrackedTrip> findByShipmentNumbers(UUID companyId, List<String> shipmentNumbers);
}
