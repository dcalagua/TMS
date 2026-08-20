package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.EligibilityDecision;
import com.ebim.tms.masterdata.domain.Frequency;
import com.ebim.tms.masterdata.domain.FrequencyException;
import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.masterdata.domain.LocationEligibilityEvaluator;
import com.ebim.tms.masterdata.domain.LocationEligibilityEvaluator.Candidate;
import com.ebim.tms.masterdata.domain.LocationFrequency;
import com.ebim.tms.masterdata.infrastructure.FrequencyExceptionRepository;
import com.ebim.tms.masterdata.infrastructure.FrequencyRepository;
import com.ebim.tms.masterdata.infrastructure.LocationFrequencyRepository;
import com.ebim.tms.masterdata.infrastructure.LocationRepository;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.reference.ServiceCalendarPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the eligibility question "can this location be serviced/dispatched on this date"
 * by loading a location's associations, their frequencies and the matching date exception (if
 * any), then delegating the actual decision to {@link LocationEligibilityEvaluator}, which needs
 * no repository and is unit-tested directly.
 *
 * <p>Also {@code masterdata}'s implementation of {@link ServiceCalendarPort}, the batched form
 * automatic planning asks. Implementing the port here rather than in an infrastructure adapter is
 * the same judgment {@code fleet.application.VehicleLookupService} makes: what crosses the module
 * boundary is a <em>rule</em> (which calendar covers which date), not a row, and a rule must exist
 * in one place.
 */
@Service
public class LocationEligibilityService implements ServiceCalendarPort {

    private final LocationRepository locationRepository;
    private final LocationFrequencyRepository locationFrequencyRepository;
    private final FrequencyRepository frequencyRepository;
    private final FrequencyExceptionRepository frequencyExceptionRepository;

    public LocationEligibilityService(LocationRepository locationRepository,
            LocationFrequencyRepository locationFrequencyRepository, FrequencyRepository frequencyRepository,
            FrequencyExceptionRepository frequencyExceptionRepository) {
        this.locationRepository = locationRepository;
        this.locationFrequencyRepository = locationFrequencyRepository;
        this.frequencyRepository = frequencyRepository;
        this.frequencyExceptionRepository = frequencyExceptionRepository;
    }

    @Transactional(readOnly = true)
    public EligibilityView evaluate(CompanyScope scope, UUID locationId, LocalDate date) {
        Location location = locationRepository.findByIdAndCompanyId(locationId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Location not found."));

        List<LocationFrequency> associations = locationFrequencyRepository
                .findByLocationIdAndCompanyIdOrderByEffectiveFromAsc(locationId, scope.companyId());

        List<Candidate> candidates = associations.stream()
                .map(association -> toCandidate(scope.companyId(), association, date))
                .filter(Objects::nonNull)
                .toList();

        EligibilityDecision decision = LocationEligibilityEvaluator.evaluate(location.active(), candidates, date);
        return EligibilityView.from(date, decision);
    }

    /**
     * The batched question planning asks: of these places, which may be served on this date.
     *
     * <p>One query per distinct location rather than one clever join. The set is the distinct
     * <em>destinations</em> of a day's backlog - tens, not thousands, even when the backlog is
     * ten thousand orders - and a hand-rolled join here would duplicate the weekly-rule and
     * exception logic that {@link LocationEligibilityEvaluator} owns, which is the trade this
     * whole class exists to avoid.
     */
    @Override
    @Transactional(readOnly = true)
    public Set<UUID> serviceableOn(Set<UUID> locationIds, LocalDate date, UUID companyId) {
        if (locationIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> serviceable = new LinkedHashSet<>();
        for (Location location : locationRepository.findByIdInAndCompanyId(locationIds, companyId)) {
            if (!location.active()) {
                // A place that is out of service is not serviceable on any date, calendar or not.
                continue;
            }
            List<LocationFrequency> associations = locationFrequencyRepository
                    .findByLocationIdAndCompanyIdOrderByEffectiveFromAsc(location.id(), companyId);
            if (associations.isEmpty()) {
                // No calendar configured is not a refusal - see ServiceCalendarPort.
                serviceable.add(location.id());
                continue;
            }
            List<Candidate> candidates = associations.stream()
                    .map(association -> toCandidate(companyId, association, date))
                    .filter(Objects::nonNull)
                    .toList();
            if (LocationEligibilityEvaluator.evaluate(true, candidates, date).eligible()) {
                serviceable.add(location.id());
            }
        }
        return serviceable;
    }

    /** {@code null} when the association's frequency no longer resolves in this company (a stale link). */
    private Candidate toCandidate(UUID companyId, LocationFrequency association, LocalDate date) {
        Frequency frequency = frequencyRepository.findByIdAndCompanyId(association.frequencyId(), companyId)
                .orElse(null);
        if (frequency == null) {
            return null;
        }
        FrequencyException exceptionOnDate =
                frequencyExceptionRepository.findByFrequencyIdAndExceptionDate(frequency.id(), date).orElse(null);
        return new Candidate(association, frequency, exceptionOnDate);
    }
}
