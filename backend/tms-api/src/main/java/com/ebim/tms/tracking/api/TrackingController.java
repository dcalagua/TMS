package com.ebim.tms.tracking.api;

import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.tracking.application.TrackingQueryService;
import com.ebim.tms.tracking.application.TripTrackingView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading where a shipment is. One endpoint, and there is deliberately no second.
 *
 * <p><b>{@code monitoring.transport:read} and not a new permission.</b> That permission has been in
 * the catalogue since migration V3 and granted to the seeded roles since V5, meaning exactly this -
 * "see where the transport is" - and until now it was the one entry with no endpoint behind it.
 * Minting {@code tracking.position:read} beside it would have given a company two permissions for
 * one question and a migration to grant the new one to everybody who already had the old.
 *
 * <p><b>No write endpoint here.</b> Positions arrive over the machine-to-machine API
 * ({@code IntegrationTrackingController}) and nowhere else. A user-token endpoint for reporting a
 * position would be a driver app, which TMS does not have; adding one "for testing" is how an
 * unauthenticated-in-practice write path gets into a product.
 *
 * <p>Not mounted under {@code /planning/trips/{id}} even though it is addressed by trip id.
 * Tracking is a module of its own with its own permission and its own lifecycle, and a path that
 * said {@code planning} would make it look like part of an aggregate whose service does not know
 * it exists.
 */
@RestController
@RequestMapping("${tms.api.base-path}/tracking")
@Tag(name = "Tracking", description = "Where a shipment is, from a provider-agnostic position feed")
public class TrackingController {

    private final TrackingQueryService trackingService;

    public TrackingController(TrackingQueryService trackingService) {
        this.trackingService = trackingService;
    }

    /**
     * The last known position and a bounded recent trail, in one document.
     *
     * <p>One call rather than a {@code /last} and a {@code /track}: the screen shows both together
     * and always has, so two endpoints would be two round trips for one card and two chances for
     * them to disagree about which position is the newest.
     */
    @GetMapping("/trips/{tripId}")
    @PreAuthorize("hasAuthority('monitoring.transport:read')")
    @Operation(summary = "Where this shipment is, and where it has been recently",
            description = "Answers for a trip in any status. When nothing has been reported, the "
                    + "document still says whether this deployment has a feed at all - which is a "
                    + "different situation from a feed that has said nothing.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripTrackingView get(CompanyScope scope, @PathVariable UUID tripId) {
        return trackingService.get(scope, tripId);
    }
}
