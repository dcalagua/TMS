package com.ebim.tms.costing.api;

import com.ebim.tms.costing.application.OwnFleetCostProfileRequest;
import com.ebim.tms.costing.application.OwnFleetCostProfileService;
import com.ebim.tms.costing.application.OwnFleetCostProfileView;
import com.ebim.tms.costing.application.OwnFleetCostingService;
import com.ebim.tms.costing.application.OwnFleetQuoteView;
import com.ebim.tms.shared.security.CompanyScope;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Own-fleet cost profiles and the estimates they produce (V48, JOB 22).
 *
 * <p>Its own permission pair rather than {@code rates.rate_card:*}: a tariff is a commercial
 * agreement with a carrier and this is a finance model of our own operation, and an installation
 * will want them in different hands. A planner reads it - choosing between a carrier and our own
 * truck is their decision - and does not set it.
 */
@RestController
@RequestMapping("${tms.api.base-path}/costing/own-fleet")
public class OwnFleetCostingController {

    private final OwnFleetCostProfileService profileService;
    private final OwnFleetCostingService costingService;

    public OwnFleetCostingController(OwnFleetCostProfileService profileService,
            OwnFleetCostingService costingService) {
        this.profileService = profileService;
        this.costingService = costingService;
    }

    @GetMapping("/profiles")
    @PreAuthorize("hasAuthority('costing.own_fleet:read')")
    public List<OwnFleetCostProfileView> list(CompanyScope scope) {
        return profileService.list(scope);
    }

    @GetMapping("/profiles/{id}")
    @PreAuthorize("hasAuthority('costing.own_fleet:read')")
    public OwnFleetCostProfileView get(CompanyScope scope, @PathVariable UUID id) {
        return profileService.get(scope, id);
    }

    @PostMapping("/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('costing.own_fleet:write')")
    public OwnFleetCostProfileView create(CompanyScope scope,
            @Valid @RequestBody OwnFleetCostProfileRequest request) {
        return profileService.create(scope, request);
    }

    @PutMapping("/profiles/{id}")
    @PreAuthorize("hasAuthority('costing.own_fleet:write')")
    public OwnFleetCostProfileView update(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody OwnFleetCostProfileRequest request) {
        return profileService.update(scope, id, request);
    }

    @PutMapping("/profiles/{id}/active")
    @PreAuthorize("hasAuthority('costing.own_fleet:write')")
    public OwnFleetCostProfileView setActive(CompanyScope scope, @PathVariable UUID id,
            @RequestBody ActiveRequest request) {
        return profileService.setActive(scope, id, request.active());
    }

    /**
     * What this shipment is modelled to cost us.
     *
     * <p>Returns 200 with a stated reason when there is no cost - an unassigned shipment, a
     * subcontracted one, or a truck nobody has configured rates for. Those are answers a planner
     * needs to see beside the options that could be costed, and a 404 would leave the screen with
     * nothing to say and no way to tell them apart.
     */
    @GetMapping("/trips/{tripId}/quote")
    @PreAuthorize("hasAuthority('costing.own_fleet:read')")
    public OwnFleetQuoteView quote(CompanyScope scope, @PathVariable UUID tripId) {
        return costingService.quote(scope, tripId);
    }

    public record ActiveRequest(boolean active) {
    }
}
