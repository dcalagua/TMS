package com.ebim.tms.costing.infrastructure;

import com.ebim.tms.costing.domain.OwnFleetCostCalculator;
import com.ebim.tms.costing.domain.OwnFleetCostEstimate;
import com.ebim.tms.costing.domain.OwnFleetCostInputs;
import com.ebim.tms.costing.domain.OwnFleetCostProfile;
import com.ebim.tms.costing.domain.OwnFleetProfileResolver;
import com.ebim.tms.costing.domain.OwnFleetQuantitySource;
import com.ebim.tms.shared.reference.OwnFleetProposalCostingPort;
import com.ebim.tms.shared.reference.TransportCostNature;
import com.ebim.tms.shared.reference.TransportCostQuote;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link OwnFleetProposalCostingPort} (V48, JOB 22).
 *
 * <p>Resolves the profile, runs the pure calculator, and hands back the amount with its nature
 * attached. Adds no arithmetic of its own - every economic rule stays in
 * {@link OwnFleetCostCalculator}, where it is testable without any of this.
 */
@Component
public class OwnFleetProposalCostingAdapter implements OwnFleetProposalCostingPort {

    private final OwnFleetCostProfileRepository repository;
    private final com.ebim.tms.shared.reference.VehicleLookupPort vehicleLookupPort;

    public OwnFleetProposalCostingAdapter(OwnFleetCostProfileRepository repository,
            com.ebim.tms.shared.reference.VehicleLookupPort vehicleLookupPort) {
        this.repository = repository;
        this.vehicleLookupPort = vehicleLookupPort;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TransportCostQuote> costProposedTrip(UUID companyId, UUID vehicleId, LocalDate on,
            BigDecimal distanceKm, Long dutyMinutes) {
        if (vehicleId == null || on == null) {
            return Optional.empty();
        }
        UUID vehicleTypeId = vehicleTypeIdOf(vehicleId, companyId);
        Optional<OwnFleetCostProfile> resolved = OwnFleetProfileResolver.resolve(
                repository.findCandidates(companyId, vehicleId, vehicleTypeId), vehicleId, vehicleTypeId, on);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }

        OwnFleetCostProfile profile = resolved.get();
        OwnFleetCostEstimate estimate = OwnFleetCostCalculator.calculate(profile.rates(),
                new OwnFleetCostInputs(
                        distanceKm,
                        distanceKm == null ? null : OwnFleetQuantitySource.MEASURED_ROUTE,
                        dutyMinutes,
                        dutyMinutes == null ? null : OwnFleetQuantitySource.TRIP_EXECUTION_WINDOW));

        // comparableTotal is null when a charged component had no quantity, and it is passed
        // through as null rather than substituted. TransportCostQuote is built for that: an
        // uncosted option takes no part in a comparison and cannot win one.
        return Optional.of(new TransportCostQuote(TransportCostNature.OWN_FLEET_INTERNAL_COST,
                estimate.comparableTotal(), estimate.currency(), "Own fleet"));
    }

    private UUID vehicleTypeIdOf(UUID vehicleId, UUID companyId) {
        var vehicle = vehicleLookupPort.findAllInCompany(java.util.Set.of(vehicleId), companyId).get(vehicleId);
        return vehicle == null ? null : vehicle.vehicleTypeId();
    }
}
