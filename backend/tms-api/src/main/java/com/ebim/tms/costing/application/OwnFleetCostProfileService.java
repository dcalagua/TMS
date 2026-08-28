package com.ebim.tms.costing.application;

import com.ebim.tms.costing.domain.OwnFleetCostProfile;
import com.ebim.tms.costing.infrastructure.OwnFleetCostProfileRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.reference.VehicleTypeLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuring own-fleet cost rates (V48, JOB 22).
 *
 * <p>Two rules live here rather than in the schema, because both need a message a finance user can
 * act on: that the profile names exactly one live target of this company, and that it does not
 * overlap one already in force. The database guarantees both -
 * {@code ck_own_fleet_cost_profile_one_target} and {@code ex_own_fleet_profile_*_no_overlap} - and
 * neither can say <em>which</em> profile collides or that a vehicle type was deactivated last week.
 */
@Service
public class OwnFleetCostProfileService {

    private final OwnFleetCostProfileRepository repository;
    private final VehicleLookupPort vehicleLookupPort;
    private final VehicleTypeLookupPort vehicleTypeLookupPort;
    private final AuditRecorder auditRecorder;
    private final AuditActorProvider auditActorProvider;

    public OwnFleetCostProfileService(OwnFleetCostProfileRepository repository,
            VehicleLookupPort vehicleLookupPort, VehicleTypeLookupPort vehicleTypeLookupPort,
            AuditRecorder auditRecorder, AuditActorProvider auditActorProvider) {
        this.repository = repository;
        this.vehicleLookupPort = vehicleLookupPort;
        this.vehicleTypeLookupPort = vehicleTypeLookupPort;
        this.auditRecorder = auditRecorder;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional(readOnly = true)
    public List<OwnFleetCostProfileView> list(CompanyScope scope) {
        List<OwnFleetCostProfile> profiles =
                repository.findByCompanyIdOrderByEffectiveFromDesc(scope.companyId());
        Map<UUID, VehicleCapacityReference> vehicles = vehicleLookupPort.findAllInCompany(
                profiles.stream().map(OwnFleetCostProfile::vehicleId).filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet()), scope.companyId());
        Map<UUID, MasterReference> types = vehicleTypeLookupPort.findAllInCompany(
                profiles.stream().map(OwnFleetCostProfile::vehicleTypeId).filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet()), scope.companyId());
        return profiles.stream().map(profile -> toView(profile, vehicles, types)).toList();
    }

    @Transactional(readOnly = true)
    public OwnFleetCostProfileView get(CompanyScope scope, UUID id) {
        return withLabels(scope, require(scope, id));
    }

    /**
     * One profile with its vehicle or type named.
     *
     * <p>Resolved rather than left null: the screen shows the label, and a create returning a blank
     * one would make the row it just added look different from every other row in the list until
     * the page reloaded.
     */
    private OwnFleetCostProfileView withLabels(CompanyScope scope, OwnFleetCostProfile profile) {
        Map<UUID, VehicleCapacityReference> vehicles = profile.vehicleId() == null ? Map.of()
                : vehicleLookupPort.findAllInCompany(Set.of(profile.vehicleId()), scope.companyId());
        Map<UUID, MasterReference> types = profile.vehicleTypeId() == null ? Map.of()
                : vehicleTypeLookupPort.findAllInCompany(Set.of(profile.vehicleTypeId()), scope.companyId());
        return toView(profile, vehicles, types);
    }

    @Transactional
    public OwnFleetCostProfileView create(CompanyScope scope, OwnFleetCostProfileRequest request) {
        validate(scope, request);
        OwnFleetCostProfile profile = new OwnFleetCostProfile(scope.companyId(), request.vehicleId(),
                request.vehicleTypeId(), request.currency(), request.effectiveFrom());
        apply(profile, request);
        profile.setCreatedBy(auditActorProvider.requireAppUserId());
        profile.setUpdatedBy(auditActorProvider.requireAppUserId());
        OwnFleetCostProfile saved = save(profile);
        auditRecorder.record(scope, AuditAggregateType.OWN_FLEET_COST_PROFILE, saved.id(),
                AuditAction.CREATE, java.util.Map.of());
        return withLabels(scope, saved);
    }

    @Transactional
    public OwnFleetCostProfileView update(CompanyScope scope, UUID id, OwnFleetCostProfileRequest request) {
        OwnFleetCostProfile profile = require(scope, id);
        validate(scope, request);
        if (!java.util.Objects.equals(profile.vehicleId(), request.vehicleId())
                || !java.util.Objects.equals(profile.vehicleTypeId(), request.vehicleTypeId())) {
            // Repointing a profile at a different truck would silently restate what every trip
            // already costed under it was costed at. Deactivate this one and configure another.
            throw new ConflictException("A cost profile cannot be moved to a different vehicle or"
                    + " vehicle type. Deactivate this one and create the profile it should be.");
        }
        profile.setCurrency(request.currency());
        profile.setWindow(request.effectiveFrom(), request.effectiveTo());
        apply(profile, request);
        profile.setUpdatedBy(auditActorProvider.requireAppUserId());
        OwnFleetCostProfile saved = save(profile);
        auditRecorder.record(scope, AuditAggregateType.OWN_FLEET_COST_PROFILE, saved.id(),
                AuditAction.UPDATE, java.util.Map.of());
        return withLabels(scope, saved);
    }

    @Transactional
    public OwnFleetCostProfileView setActive(CompanyScope scope, UUID id, boolean active) {
        OwnFleetCostProfile profile = require(scope, id);
        profile.setActive(active);
        profile.setUpdatedBy(auditActorProvider.requireAppUserId());
        OwnFleetCostProfile saved = save(profile);
        auditRecorder.record(scope, AuditAggregateType.OWN_FLEET_COST_PROFILE, saved.id(),
                active ? AuditAction.ACTIVATE : AuditAction.DEACTIVATE, java.util.Map.of());
        return withLabels(scope, saved);
    }

    private OwnFleetCostProfile save(OwnFleetCostProfile profile) {
        try {
            return repository.saveAndFlush(profile);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (message.contains("ex_own_fleet_profile_")) {
                throw new ConflictException("Another cost profile already covers this vehicle over"
                        + " part of those dates. End that one before this one starts.");
            }
            if (message.contains("ck_own_fleet_cost_profile_has_a_component")) {
                throw new InvalidRequestException("A cost profile has to charge for at least one"
                        + " thing. Leave a rate empty for what you do not model - empty is not zero.");
            }
            throw e;
        }
    }

    private void validate(CompanyScope scope, OwnFleetCostProfileRequest request) {
        if ((request.vehicleId() == null) == (request.vehicleTypeId() == null)) {
            throw new InvalidRequestException(
                    "A cost profile is about one vehicle or about one vehicle type - pick exactly one.");
        }
        if (request.effectiveTo() != null && !request.effectiveTo().isAfter(request.effectiveFrom())) {
            throw new InvalidRequestException("The end of a profile's validity has to be after its start.");
        }
        if (request.vehicleId() != null
                && vehicleLookupPort.findAllInCompany(Set.of(request.vehicleId()), scope.companyId())
                        .get(request.vehicleId()) == null) {
            throw new InvalidRequestException("That vehicle is not in this company.");
        }
        if (request.vehicleTypeId() != null
                && vehicleTypeLookupPort.findActiveInCompany(request.vehicleTypeId(), scope.companyId()).isEmpty()) {
            throw new InvalidRequestException("That vehicle type is not active in this company.");
        }
    }

    private static void apply(OwnFleetCostProfile profile, OwnFleetCostProfileRequest request) {
        // Passed through exactly as sent, nulls included: a null rate means the company does not
        // charge for that component, and coalescing it to zero here would quietly turn "not
        // modelled" into "modelled at nothing" - the one substitution this whole job forbids.
        profile.setRates(request.fixedTripAmount(), request.fuelPerKm(), request.driverPerHour(),
                request.vehiclePerHour(), request.maintenancePerKm(), request.depreciationPerKm(),
                request.tollAmount());
        profile.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim());
    }

    private OwnFleetCostProfile require(CompanyScope scope, UUID id) {
        return repository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("No cost profile " + id + " in this company"));
    }

    private static OwnFleetCostProfileView toView(OwnFleetCostProfile profile,
            Map<UUID, VehicleCapacityReference> vehicles, Map<UUID, MasterReference> types) {
        var rates = profile.rates();
        // Guarded: exactly one of the two ids is null on every profile, and Map.of() throws on a
        // null key rather than answering null.
        VehicleCapacityReference vehicle =
                profile.vehicleId() == null ? null : vehicles.get(profile.vehicleId());
        MasterReference type =
                profile.vehicleTypeId() == null ? null : types.get(profile.vehicleTypeId());
        return new OwnFleetCostProfileView(
                profile.id(), profile.vehicleId(), vehicle == null ? null : vehicle.code(),
                profile.vehicleTypeId(), type == null ? null : type.name(),
                profile.currency(), profile.effectiveFrom(), profile.effectiveTo(), profile.isActive(),
                stateOf(profile), rates.fixedTripAmount(), rates.fuelPerKm(), rates.driverPerHour(),
                rates.vehiclePerHour(), rates.maintenancePerKm(), rates.depreciationPerKm(),
                rates.tollAmount(), rates.needsDistance(), rates.needsDuty(), profile.notes());
    }

    /** Derived, never stored - a profile expires because a day passed, not because anything ran. */
    private static OwnFleetProfileState stateOf(OwnFleetCostProfile profile) {
        if (!profile.isActive()) {
            return OwnFleetProfileState.INACTIVE;
        }
        if (!profile.rates().chargesForAnything()) {
            return OwnFleetProfileState.INCOMPLETE;
        }
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        if (profile.effectiveFrom().isAfter(today)) {
            return OwnFleetProfileState.FUTURE;
        }
        if (!profile.coversDate(today)) {
            return OwnFleetProfileState.EXPIRED;
        }
        return OwnFleetProfileState.ACTIVE;
    }
}
