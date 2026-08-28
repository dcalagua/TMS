package com.ebim.tms.shared.reference;

import java.util.Optional;
import java.util.UUID;

/**
 * What time zone a place keeps (migration V41).
 *
 * <p>Its own port rather than a field on {@link MasterReference}, which five modules read and none
 * of the others needs a zone from. It matters for exactly one thing so far - reading a dock's local
 * opening hours - and the alternative is either widening a shared record for one caller or letting
 * appointments read {@code tms.location}, which {@code ModuleBoundaryTest} refuses and should.
 *
 * <p><b>Never the server's zone.</b> A dock in Arequipa opens at 07:00 in Arequipa whatever the
 * application happens to run on, and a deployment that moved regions would otherwise silently move
 * every site's opening hours.
 */
public interface LocationTimeZonePort {

    /**
     * The IANA zone of a location in this company, or empty when it cannot be resolved.
     *
     * <p>Empty is a real answer and not a failure: the caller falls back to the company's zone,
     * which is the same default {@code tms.location}'s own generated column uses.
     */
    Optional<String> findTimeZone(UUID locationId, UUID companyId);
}
