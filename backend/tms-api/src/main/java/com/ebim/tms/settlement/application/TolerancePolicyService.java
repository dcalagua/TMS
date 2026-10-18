package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.TolerancePolicy;
import com.ebim.tms.settlement.infrastructure.TolerancePolicyRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code tms.tolerance_policy} (V46 table, endpoint since V64, ADR-024 part 1, item 2).
 *
 * <p>V46 read the table on every match and gave nobody a way to write it: the seeded
 * {@code settlement.tolerance:manage} guarded nothing, so every company matched at
 * {@code Tolerance.NONE}. This is the missing writer, and nothing more - the table, its CHECKs and its
 * two partial unique indexes (one active policy per company, one per carrier) are used as they are.
 *
 * <p><b>A policy is never edited out of history.</b> A match snapshots the tolerance it was judged
 * against, so changing a policy restates nothing already decided; and every write is audited as
 * {@code TOLERANCE_POLICY_CHANGED} with the bounds before and after, because "who widened the
 * tolerance" is the first question after an overpayment.
 */
@Service
public class TolerancePolicyService {

    private final TolerancePolicyRepository repository;
    private final CarrierLookupPort carrierLookupPort;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public TolerancePolicyService(TolerancePolicyRepository repository, CarrierLookupPort carrierLookupPort,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.repository = repository;
        this.carrierLookupPort = carrierLookupPort;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    /** The company default first, then carriers by name; retired policies only when asked for. */
    @Transactional(readOnly = true)
    public List<TolerancePolicyView> list(CompanyScope scope, boolean includeInactive) {
        List<TolerancePolicy> policies = includeInactive
                ? repository.findByCompanyId(scope.companyId())
                : repository.findByCompanyIdAndActiveTrue(scope.companyId());
        Map<UUID, String> names = carrierNames(scope, policies);
        return policies.stream()
                .map(policy -> TolerancePolicyView.of(policy,
                        policy.carrierId() == null ? null : names.get(policy.carrierId())))
                .sorted(Comparator.comparing((TolerancePolicyView view) -> view.carrierId() != null)
                        .thenComparing(view -> view.carrierName() == null ? "" : view.carrierName(),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(view -> !view.active()))
                .toList();
    }

    @Transactional
    public TolerancePolicyView create(CompanyScope scope, TolerancePolicyRequest request) {
        UUID actorId = auditActorProvider.requireAppUserId();
        if (request.carrierId() != null
                && carrierLookupPort.findAllInCompany(Set.of(request.carrierId()), scope.companyId()).isEmpty()) {
            throw new InvalidRequestException("That carrier does not belong to this company.");
        }
        requireBounds(request);
        // Checked so the refusal names the scope; uq_tolerance_policy_active_* close the race.
        boolean taken = request.carrierId() == null
                ? repository.findByCompanyIdAndCarrierIdIsNullAndActiveTrue(scope.companyId()).isPresent()
                : repository.findByCompanyIdAndCarrierIdAndActiveTrue(scope.companyId(), request.carrierId())
                        .isPresent();
        if (taken) {
            throw alreadyActive(request.carrierId());
        }
        TolerancePolicy policy = new TolerancePolicy(scope.companyId(), request.carrierId(),
                request.absoluteAmount(), request.percentage(), currencyOf(request), actorId);
        TolerancePolicy saved = saveWithBackstop(policy, request.carrierId());
        audit(scope, saved, "CREATED", null);
        return view(scope, saved);
    }

    @Transactional
    public TolerancePolicyView update(CompanyScope scope, UUID id, TolerancePolicyRequest request) {
        UUID actorId = auditActorProvider.requireAppUserId();
        TolerancePolicy policy = find(scope, id);
        if (!policy.active()) {
            throw new ConflictException("This tolerance policy is retired. Create a new one instead.");
        }
        if (request.carrierId() != null && !request.carrierId().equals(policy.carrierId())) {
            throw new InvalidRequestException("A policy stays with the carrier it was created for."
                    + " Retire it and create one for the other carrier.");
        }
        requireBounds(request);
        String before = bounds(policy);
        policy.update(request.absoluteAmount(), request.percentage(), currencyOf(request), actorId);
        TolerancePolicy saved = repository.saveAndFlush(policy);
        audit(scope, saved, "UPDATED", before);
        return view(scope, saved);
    }

    /** Retires the policy. Matching then falls back to the company default, or to no tolerance. */
    @Transactional
    public TolerancePolicyView deactivate(CompanyScope scope, UUID id) {
        UUID actorId = auditActorProvider.requireAppUserId();
        TolerancePolicy policy = find(scope, id);
        if (!policy.active()) {
            return view(scope, policy);
        }
        policy.deactivate(actorId);
        TolerancePolicy saved = repository.saveAndFlush(policy);
        audit(scope, saved, "DEACTIVATED", null);
        return view(scope, saved);
    }

    // ------------------------------------------------------------------ internals

    private TolerancePolicy find(CompanyScope scope, UUID id) {
        return repository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Tolerance policy not found."));
    }

    /** The sentences behind V46's CHECKs, so the refusal is readable rather than a constraint name. */
    private static void requireBounds(TolerancePolicyRequest request) {
        if (request.absoluteAmount() == null && request.percentage() == null) {
            throw new InvalidRequestException("A tolerance states an absolute amount, a percentage, or both.");
        }
        if (request.absoluteAmount() != null && (request.currency() == null || request.currency().isBlank())) {
            throw new InvalidRequestException("An absolute tolerance is a sum of money and needs its currency.");
        }
    }

    private static String currencyOf(TolerancePolicyRequest request) {
        return request.currency() == null || request.currency().isBlank()
                ? null
                : request.currency().trim().toUpperCase(Locale.ROOT);
    }

    private TolerancePolicy saveWithBackstop(TolerancePolicy policy, UUID carrierId) {
        try {
            return repository.saveAndFlush(policy);
        } catch (DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (message.contains("uq_tolerance_policy_active")) {
                throw alreadyActive(carrierId);
            }
            throw e;
        }
    }

    private static ConflictException alreadyActive(UUID carrierId) {
        return new ConflictException(carrierId == null
                ? "The company already has an active tolerance policy. Change it, or retire it first."
                : "This carrier already has an active tolerance policy. Change it, or retire it first.");
    }

    private void audit(CompanyScope scope, TolerancePolicy policy, String operation, String before) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("operation", operation);
        metadata.put("scope", policy.carrierId() == null ? "COMPANY" : "CARRIER");
        if (policy.carrierId() != null) {
            metadata.put("carrierId", policy.carrierId().toString());
        }
        metadata.put("bounds", bounds(policy));
        if (before != null) {
            metadata.put("previousBounds", before);
        }
        auditRecorder.record(scope, AuditAggregateType.TOLERANCE_POLICY, policy.id(),
                AuditAction.TOLERANCE_POLICY_CHANGED, metadata);
    }

    private static String bounds(TolerancePolicy policy) {
        return "absolute=" + plain(policy.absoluteAmount())
                + (policy.currency() == null ? "" : " " + policy.currency())
                + ", percentage=" + plain(policy.percentage());
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private TolerancePolicyView view(CompanyScope scope, TolerancePolicy policy) {
        String name = policy.carrierId() == null ? null
                : carrierNames(scope, List.of(policy)).get(policy.carrierId());
        return TolerancePolicyView.of(policy, name);
    }

    private Map<UUID, String> carrierNames(CompanyScope scope, List<TolerancePolicy> policies) {
        Set<UUID> ids = policies.stream().map(TolerancePolicy::carrierId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return carrierLookupPort.findAllInCompany(ids, scope.companyId()).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().name()));
    }
}
