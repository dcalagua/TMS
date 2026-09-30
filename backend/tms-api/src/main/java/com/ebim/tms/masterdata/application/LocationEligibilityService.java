package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.EligibilityDecision;
import com.ebim.tms.masterdata.domain.Frequency;
import com.ebim.tms.masterdata.domain.FrequencyCalendar;
import com.ebim.tms.masterdata.domain.FrequencyException;
import com.ebim.tms.masterdata.domain.FrequencyWeeklyRule;
import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.masterdata.domain.LocationEligibilityEvaluator;
import com.ebim.tms.masterdata.domain.LocationEligibilityEvaluator.Candidate;
import com.ebim.tms.masterdata.domain.LocationFrequency;
import com.ebim.tms.masterdata.infrastructure.FrequencyExceptionRepository;
import com.ebim.tms.masterdata.infrastructure.FrequencyRepository;
import com.ebim.tms.masterdata.infrastructure.LocationFrequencyRepository;
import com.ebim.tms.masterdata.infrastructure.LocationRepository;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.reference.CalendarVerdict;
import com.ebim.tms.shared.reference.RouteResolution;
import com.ebim.tms.shared.reference.RouteResolutionPort;
import com.ebim.tms.shared.reference.RouteResolutionPort.OriginDestination;
import com.ebim.tms.shared.reference.ServiceCalendarPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final RouteResolutionPort routeResolutionPort;

    public LocationEligibilityService(LocationRepository locationRepository,
            LocationFrequencyRepository locationFrequencyRepository, FrequencyRepository frequencyRepository,
            FrequencyExceptionRepository frequencyExceptionRepository, RouteResolutionPort routeResolutionPort) {
        this.locationRepository = locationRepository;
        this.locationFrequencyRepository = locationFrequencyRepository;
        this.frequencyRepository = frequencyRepository;
        this.frequencyExceptionRepository = frequencyExceptionRepository;
        this.routeResolutionPort = routeResolutionPort;
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

    /**
     * The release read model's question (ADR-014 section 5), batched: a fixed number of queries for
     * any number of locations on one date - locations, associations, frequencies with their weekly
     * rules, and that date's exceptions. The decision is still {@link LocationEligibilityEvaluator}'s,
     * so this cannot disagree with {@link #serviceableOn} about whether a place is served.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<UUID, CalendarVerdict> locationCalendarsOn(Set<UUID> locationIds, LocalDate date, UUID companyId) {
        Map<UUID, CalendarVerdict> verdicts = new LinkedHashMap<>();
        if (locationIds.isEmpty()) {
            return verdicts;
        }
        Map<UUID, Location> locations = locationRepository.findByIdInAndCompanyId(locationIds, companyId).stream()
                .collect(Collectors.toMap(Location::id, Function.identity()));
        Map<UUID, List<LocationFrequency>> associations = locationFrequencyRepository
                .findByLocationIdInAndCompanyIdOrderByEffectiveFromAsc(locationIds, companyId).stream()
                .collect(Collectors.groupingBy(LocationFrequency::locationId, LinkedHashMap::new, Collectors.toList()));
        Set<UUID> frequencyIds = associations.values().stream().flatMap(List::stream)
                .map(LocationFrequency::frequencyId).collect(Collectors.toSet());
        Map<UUID, Frequency> frequencies = frequencies(frequencyIds, companyId);
        Map<UUID, FrequencyException> exceptions = exceptionsOn(frequencies.keySet(), date);

        for (UUID locationId : locationIds) {
            Location location = locations.get(locationId);
            List<LocationFrequency> linked = associations.getOrDefault(locationId, List.of());
            if (location == null) {
                // Not this company's, or gone: the order's MISSING_DESTINATION says so; a calendar
                // verdict on top would only repeat it.
                verdicts.put(locationId, CalendarVerdict.NOT_CONFIGURED);
                continue;
            }
            if (linked.isEmpty()) {
                verdicts.put(locationId, location.active()
                        ? CalendarVerdict.NOT_CONFIGURED
                        : CalendarVerdict.notRunning(null));
                continue;
            }
            List<Candidate> candidates = linked.stream()
                    .filter(association -> frequencies.containsKey(association.frequencyId()))
                    .map(association -> new Candidate(association, frequencies.get(association.frequencyId()),
                            exceptions.get(association.frequencyId())))
                    .toList();
            EligibilityDecision decision = LocationEligibilityEvaluator.evaluate(location.active(), candidates, date);
            if (decision.eligible()) {
                Frequency matched = frequencies.get(decision.matchedFrequencyId());
                verdicts.put(locationId, CalendarVerdict.running(matched == null ? null : matched.code(),
                        decision.cutoffTime(), decision.leadTimeDays()));
            } else {
                verdicts.put(locationId, CalendarVerdict.notRunning(
                        candidates.isEmpty() ? null : candidates.get(0).frequency().code()));
            }
        }
        return verdicts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, CalendarVerdict> frequencyCalendarsOn(Set<UUID> frequencyIds, LocalDate date, UUID companyId) {
        Map<UUID, CalendarVerdict> verdicts = new LinkedHashMap<>();
        if (frequencyIds.isEmpty()) {
            return verdicts;
        }
        Map<UUID, Frequency> frequencies = frequencies(frequencyIds, companyId);
        Map<UUID, FrequencyException> exceptions = exceptionsOn(frequencies.keySet(), date);
        for (UUID frequencyId : frequencyIds) {
            Frequency frequency = frequencies.get(frequencyId);
            if (frequency == null) {
                verdicts.put(frequencyId, CalendarVerdict.NOT_CONFIGURED);
                continue;
            }
            FrequencyException exception = exceptions.get(frequencyId);
            if (FrequencyCalendar.runsOn(frequency, date, exception)) {
                FrequencyWeeklyRule rule = FrequencyCalendar.weeklyRuleFor(frequency, date);
                verdicts.put(frequencyId, CalendarVerdict.running(frequency.code(),
                        FrequencyCalendar.effectiveCutoff(frequency, date, exception),
                        rule == null ? null : rule.leadTimeDays()));
            } else {
                verdicts.put(frequencyId, CalendarVerdict.notRunning(frequency.code()));
            }
        }
        return verdicts;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> idleByRouteOn(UUID originId, Set<UUID> destinationIds, LocalDate date, UUID companyId) {
        if (destinationIds.isEmpty()) {
            return Set.of();
        }
        Map<OriginDestination, RouteResolution> resolutions = routeResolutionPort.resolveAll(companyId,
                destinationIds.stream().map(destinationId -> new OriginDestination(originId, destinationId)).toList());
        Map<UUID, UUID> frequencyByDestination = new HashMap<>();
        resolutions.forEach((pair, resolution) -> resolution.route()
                .filter(route -> route.frequencyId() != null)
                .ifPresent(route -> frequencyByDestination.put(pair.destinationId(), route.frequencyId())));
        if (frequencyByDestination.isEmpty()) {
            return Set.of();
        }
        Map<UUID, CalendarVerdict> verdicts =
                frequencyCalendarsOn(Set.copyOf(frequencyByDestination.values()), date, companyId);
        Set<UUID> idle = new LinkedHashSet<>();
        frequencyByDestination.forEach((destinationId, frequencyId) -> {
            CalendarVerdict verdict = verdicts.get(frequencyId);
            if (verdict != null && verdict.configured() && !verdict.runs()) {
                idle.add(destinationId);
            }
        });
        return idle;
    }

    private Map<UUID, Frequency> frequencies(Set<UUID> frequencyIds, UUID companyId) {
        if (frequencyIds.isEmpty()) {
            return Map.of();
        }
        return frequencyRepository.findByIdInAndCompanyId(frequencyIds, companyId).stream()
                .collect(Collectors.toMap(Frequency::id, Function.identity(), (first, second) -> first));
    }

    /** Only asked for frequencies already resolved in the company, so the exceptions inherit that scope. */
    private Map<UUID, FrequencyException> exceptionsOn(Set<UUID> frequencyIds, LocalDate date) {
        if (frequencyIds.isEmpty()) {
            return Map.of();
        }
        return frequencyExceptionRepository.findByFrequencyIdInAndExceptionDate(frequencyIds, date).stream()
                .collect(Collectors.toMap(FrequencyException::frequencyId, Function.identity(),
                        (first, second) -> first));
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
