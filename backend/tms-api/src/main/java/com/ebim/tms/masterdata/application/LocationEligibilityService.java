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
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the eligibility question the job 03 brief asks for - "can this location be
 * serviced/dispatched on this date" - by loading a location's associations, their frequencies and
 * the matching date exception (if any), then delegating the actual decision to
 * {@link LocationEligibilityEvaluator}, which needs no repository and is unit-tested directly.
 */
@Service
public class LocationEligibilityService {

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
                .map(association -> toCandidate(scope, association, date))
                .filter(Objects::nonNull)
                .toList();

        EligibilityDecision decision = LocationEligibilityEvaluator.evaluate(location.active(), candidates, date);
        return EligibilityView.from(date, decision);
    }

    /** {@code null} when the association's frequency no longer resolves in this company (a stale link). */
    private Candidate toCandidate(CompanyScope scope, LocationFrequency association, LocalDate date) {
        Frequency frequency = frequencyRepository.findByIdAndCompanyId(association.frequencyId(), scope.companyId())
                .orElse(null);
        if (frequency == null) {
            return null;
        }
        FrequencyException exceptionOnDate =
                frequencyExceptionRepository.findByFrequencyIdAndExceptionDate(frequency.id(), date).orElse(null);
        return new Candidate(association, frequency, exceptionOnDate);
    }
}
