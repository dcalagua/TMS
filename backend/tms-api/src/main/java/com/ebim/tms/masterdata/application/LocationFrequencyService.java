package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Frequency;
import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.masterdata.domain.LocationFrequency;
import com.ebim.tms.masterdata.infrastructure.FrequencyRepository;
import com.ebim.tms.masterdata.infrastructure.LocationFrequencyRepository;
import com.ebim.tms.masterdata.infrastructure.LocationRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases for a {@link Location}'s service-calendar associations (migration V15). Each
 * association is an independent calendar fact managed one at a time through its own sub-resource,
 * not diffed as a whole collection - the same reasoning {@code FrequencyService} gives for
 * {@code FrequencyException}.
 */
@Service
public class LocationFrequencyService {

    private final LocationRepository locationRepository;
    private final LocationFrequencyRepository locationFrequencyRepository;
    private final FrequencyRepository frequencyRepository;
    private final AuditActorProvider auditActorProvider;

    public LocationFrequencyService(LocationRepository locationRepository,
            LocationFrequencyRepository locationFrequencyRepository, FrequencyRepository frequencyRepository,
            AuditActorProvider auditActorProvider) {
        this.locationRepository = locationRepository;
        this.locationFrequencyRepository = locationFrequencyRepository;
        this.frequencyRepository = frequencyRepository;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional(readOnly = true)
    public List<LocationFrequencyView> list(CompanyScope scope, UUID locationId) {
        findLocation(scope, locationId);
        List<LocationFrequency> associations =
                locationFrequencyRepository.findByLocationIdAndCompanyIdOrderByEffectiveFromAsc(
                        locationId, scope.companyId());
        Map<UUID, Frequency> frequenciesById = loadFrequencies(scope, associations);
        return associations.stream()
                .map(association -> LocationFrequencyView.from(association, frequenciesById.get(association.frequencyId())))
                .toList();
    }

    @Transactional
    public LocationFrequencyView create(CompanyScope scope, UUID locationId, LocationFrequencyRequest request) {
        findLocation(scope, locationId);
        Frequency frequency = requireFrequencyInScope(scope, request.frequencyId());
        requireValidRange(request.effectiveFrom(), request.effectiveTo());
        if (locationFrequencyRepository.existsByLocationIdAndFrequencyIdAndActiveTrue(locationId, request.frequencyId())) {
            throw duplicateAssociation();
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        LocationFrequency association = new LocationFrequency(scope.companyId(), locationId, request.frequencyId(),
                request.effectiveFrom(), request.effectiveTo(), actorId);
        return LocationFrequencyView.from(locationFrequencyRepository.save(association), frequency);
    }

    @Transactional
    public LocationFrequencyView update(
            CompanyScope scope, UUID locationId, UUID id, LocationFrequencyDateRangeRequest request) {
        findLocation(scope, locationId);
        LocationFrequency association = find(scope, locationId, id);
        requireValidRange(request.effectiveFrom(), request.effectiveTo());

        association.applyChanges(request.effectiveFrom(), request.effectiveTo(), auditActorProvider.requireAppUserId());
        Frequency frequency = frequencyRepository.findByIdAndCompanyId(association.frequencyId(), scope.companyId())
                .orElse(null);
        return LocationFrequencyView.from(locationFrequencyRepository.save(association), frequency);
    }

    @Transactional
    public LocationFrequencyView activate(CompanyScope scope, UUID locationId, UUID id) {
        findLocation(scope, locationId);
        LocationFrequency association = find(scope, locationId, id);
        if (locationFrequencyRepository.existsByLocationIdAndFrequencyIdAndActiveTrueAndIdNot(
                locationId, association.frequencyId(), id)) {
            throw duplicateAssociation();
        }
        association.activate(auditActorProvider.requireAppUserId());
        return toView(scope, locationFrequencyRepository.save(association));
    }

    @Transactional
    public LocationFrequencyView deactivate(CompanyScope scope, UUID locationId, UUID id) {
        findLocation(scope, locationId);
        LocationFrequency association = find(scope, locationId, id);
        association.deactivate(auditActorProvider.requireAppUserId());
        return toView(scope, locationFrequencyRepository.save(association));
    }

    @Transactional
    public void delete(CompanyScope scope, UUID locationId, UUID id) {
        findLocation(scope, locationId);
        LocationFrequency association = find(scope, locationId, id);
        locationFrequencyRepository.delete(association);
    }

    private LocationFrequencyView toView(CompanyScope scope, LocationFrequency association) {
        Frequency frequency = frequencyRepository.findByIdAndCompanyId(association.frequencyId(), scope.companyId())
                .orElse(null);
        return LocationFrequencyView.from(association, frequency);
    }

    private Location findLocation(CompanyScope scope, UUID locationId) {
        return locationRepository.findByIdAndCompanyId(locationId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Location not found."));
    }

    private LocationFrequency find(CompanyScope scope, UUID locationId, UUID id) {
        return locationFrequencyRepository.findByIdAndLocationIdAndCompanyId(id, locationId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Location frequency association not found."));
    }

    private Frequency requireFrequencyInScope(CompanyScope scope, UUID frequencyId) {
        return frequencyRepository.findByIdAndCompanyId(frequencyId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException("frequencyId does not reference a frequency in this company."));
    }

    private static void requireValidRange(LocalDate effectiveFrom, LocalDate effectiveTo) {
        if (effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new InvalidRequestException("effectiveTo must not be before effectiveFrom.");
        }
    }

    private static ConflictException duplicateAssociation() {
        return new ConflictException(
                "This location already has an active association with that frequency. Edit its date range instead "
                        + "of creating a duplicate.");
    }

    /** One batched lookup per list call instead of one query per association. */
    private Map<UUID, Frequency> loadFrequencies(CompanyScope scope, List<LocationFrequency> associations) {
        Set<UUID> frequencyIds =
                associations.stream().map(LocationFrequency::frequencyId).collect(Collectors.toSet());
        Map<UUID, Frequency> byId = new HashMap<>();
        if (frequencyIds.isEmpty()) {
            return byId;
        }
        for (Frequency frequency : frequencyRepository.findAllById(frequencyIds)) {
            if (frequency.companyId().equals(scope.companyId())) {
                byId.put(frequency.id(), frequency);
            }
        }
        return byId;
    }
}
