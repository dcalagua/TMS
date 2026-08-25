package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Frequency;
import com.ebim.tms.masterdata.domain.FrequencyException;
import com.ebim.tms.masterdata.domain.FrequencyWeeklyRuleInput;
import com.ebim.tms.masterdata.infrastructure.FrequencyExceptionRepository;
import com.ebim.tms.masterdata.infrastructure.FrequencyRepository;
import com.ebim.tms.masterdata.infrastructure.FrequencySpecifications;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Frequency use cases, including the weekly-rule child collection and the date-exception
 * sub-resource. Takes a {@link CompanyScope}, never a company id - see {@code LocationService}
 * for the contract this follows.
 */
@Service
public class FrequencyService {

    private static final Set<String> SORTABLE_PROPERTIES = Set.of("code", "name", "active", "createdAt", "updatedAt");

    private final FrequencyRepository frequencyRepository;
    private final FrequencyExceptionRepository frequencyExceptionRepository;
    private final AuditActorProvider auditActorProvider;

    public FrequencyService(FrequencyRepository frequencyRepository,
            FrequencyExceptionRepository frequencyExceptionRepository, AuditActorProvider auditActorProvider) {
        this.frequencyRepository = frequencyRepository;
        this.frequencyExceptionRepository = frequencyExceptionRepository;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional(readOnly = true)
    public PageResponse<FrequencyView> list(CompanyScope scope, FrequencyFilter filter, PageQuery pageQuery) {
        var specification =
                FrequencySpecifications.matching(scope.companyId(), filter.code(), filter.name(), filter.active());
        Page<Frequency> page = frequencyRepository.findAll(specification, toPageable(pageQuery));
        List<FrequencyView> content = page.getContent().stream().map(FrequencyView::from).toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public FrequencyView get(CompanyScope scope, UUID id) {
        return FrequencyView.from(find(scope, id));
    }

    @Transactional
    public FrequencyView create(CompanyScope scope, FrequencyRequest request) {
        String code = normalizeCode(request.code());
        List<FrequencyWeeklyRuleInput> weeklyRules = toWeeklyRuleInputs(request.weeklyRules());
        if (frequencyRepository.existsByCompanyIdAndCode(scope.companyId(), code)) {
            throw duplicateCode(code);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        Frequency frequency =
                new Frequency(scope.companyId(), code, request.name().trim(), blankToNull(request.description()), actorId);
        frequency.replaceWeeklyRules(weeklyRules, actorId);
        return FrequencyView.from(saveOrConflict(frequency, code));
    }

    @Transactional
    public FrequencyView update(CompanyScope scope, UUID id, FrequencyRequest request) {
        Frequency frequency = find(scope, id);
        String code = normalizeCode(request.code());
        List<FrequencyWeeklyRuleInput> weeklyRules = toWeeklyRuleInputs(request.weeklyRules());
        if (frequencyRepository.existsByCompanyIdAndCodeAndIdNot(scope.companyId(), code, id)) {
            throw duplicateCode(code);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        frequency.applyChanges(code, request.name().trim(), blankToNull(request.description()), actorId);
        frequency.replaceWeeklyRules(weeklyRules, actorId);
        return FrequencyView.from(saveOrConflict(frequency, code));
    }

    @Transactional
    public FrequencyView activate(CompanyScope scope, UUID id) {
        Frequency frequency = find(scope, id);
        frequency.activate(auditActorProvider.requireAppUserId());
        return FrequencyView.from(frequencyRepository.save(frequency));
    }

    @Transactional
    public FrequencyView deactivate(CompanyScope scope, UUID id) {
        Frequency frequency = find(scope, id);
        frequency.deactivate(auditActorProvider.requireAppUserId());
        return FrequencyView.from(frequencyRepository.save(frequency));
    }

    @Transactional(readOnly = true)
    public List<FrequencyExceptionView> listExceptions(CompanyScope scope, UUID frequencyId) {
        find(scope, frequencyId);
        return frequencyExceptionRepository.findByFrequencyIdOrderByExceptionDateAsc(frequencyId).stream()
                .map(FrequencyExceptionView::from).toList();
    }

    @Transactional
    public FrequencyExceptionView createException(CompanyScope scope, UUID frequencyId, FrequencyExceptionRequest request) {
        find(scope, frequencyId);
        // Before the duplicate check, so a request that is wrong in two ways is told about the
        // one it can fix without first picking another date.
        if (request.cutoffTimeOverride() != null && !request.serviceOverride()) {
            throw new InvalidRequestException(
                    "cutoffTimeOverride is only valid on an open date: a closed date has no cutoff.");
        }
        if (frequencyExceptionRepository.existsByFrequencyIdAndExceptionDate(frequencyId, request.exceptionDate())) {
            throw new ConflictException(
                    "An exception for " + request.exceptionDate() + " already exists on this frequency.");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        FrequencyException exception = new FrequencyException(frequencyId, request.exceptionDate(),
                request.serviceOverride(), request.cutoffTimeOverride(), blankToNull(request.note()), actorId);
        try {
            return FrequencyExceptionView.from(frequencyExceptionRepository.saveAndFlush(exception));
        } catch (DataIntegrityViolationException raced) {
            throw new ConflictException(
                    "An exception for " + request.exceptionDate() + " already exists on this frequency.");
        }
    }

    @Transactional
    public void deleteException(CompanyScope scope, UUID frequencyId, UUID exceptionId) {
        find(scope, frequencyId);
        FrequencyException exception = frequencyExceptionRepository.findByIdAndFrequencyId(exceptionId, frequencyId)
                .orElseThrow(() -> new ResourceNotFoundException("Frequency exception not found."));
        frequencyExceptionRepository.delete(exception);
    }

    private Frequency find(CompanyScope scope, UUID id) {
        return frequencyRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Frequency not found."));
    }

    /** Saves and translates a race with a concurrent duplicate write into the same conflict a pre-check would give. */
    private Frequency saveOrConflict(Frequency frequency, String code) {
        try {
            return frequencyRepository.saveAndFlush(frequency);
        } catch (DataIntegrityViolationException raced) {
            throw duplicateCode(code);
        }
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("A frequency with code '" + code + "' already exists in this company.");
    }

    /** Bean validation checks each row in isolation; a duplicate day across rows needs a cross-field check here. */
    private static List<FrequencyWeeklyRuleInput> toWeeklyRuleInputs(List<FrequencyRequest.WeeklyRuleRequest> rules) {
        Set<Integer> seenDays = new HashSet<>();
        for (FrequencyRequest.WeeklyRuleRequest rule : rules) {
            if (!seenDays.add(rule.dayOfWeek())) {
                throw new InvalidRequestException("dayOfWeek " + rule.dayOfWeek() + " is repeated in weeklyRules.");
            }
        }
        return rules.stream()
                .map(rule -> new FrequencyWeeklyRuleInput(rule.dayOfWeek(), rule.enabled(), rule.cutoffTime(), rule.leadTimeDays()))
                .toList();
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static Pageable toPageable(PageQuery pageQuery) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE_PROPERTIES);
        Sort sort = terms.isEmpty()
                ? Sort.by(Sort.Direction.ASC, "code")
                : Sort.by(terms.stream()
                        .map(term -> new Sort.Order(
                                term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, term.property()))
                        .toList());
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort);
    }
}
