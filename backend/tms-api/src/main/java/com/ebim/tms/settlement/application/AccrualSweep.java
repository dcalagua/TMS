package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.FreightAccrual;
import com.ebim.tms.settlement.infrastructure.FreightAccrualRepository;
import com.ebim.tms.shared.reference.AccruableTripLookupPort;
import com.ebim.tms.shared.reference.AccruableTripLookupPort.AccruableTrip;
import com.ebim.tms.shared.reference.TripCostLookupPort;
import com.ebim.tms.shared.reference.TripCostLookupPort.TripCostSummary;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.CompanyScopeLoader;
import com.ebim.tms.shared.security.SystemJobAuthentication;
import com.ebim.tms.shared.security.TenantOperationGate;
import com.ebim.tms.shared.settings.AccrualTrigger;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How a freight accrual is born and adjusted: a sweep over committed facts, not a listener
 * (migration V65, ADR-023 section 1).
 *
 * <h2>Why a sweep</h2>
 *
 * <p>"Which trips should be accrued and are not" is a query over departures (or completions) that
 * are already committed, so nothing can be lost: an in-memory listener loses a fact when the node
 * dies between commit and delivery, and a hook in the dispatch transaction would make a dispatch fail
 * because accounting could not price it. The same query is the "unaccrued" report.
 *
 * <h2>Tenancy</h2>
 *
 * <p>The scheduled pass asks one cross-tenant question - which companies have work - on the owner
 * connection, and then does each company's work under {@link SystemJobAuthentication}: the
 * connections become {@code tms_app} bound to that company, so RLS covers the job exactly as it
 * covers a request, and the audit trail names the job as a machine actor.
 *
 * <h2>Concurrency and idempotency</h2>
 *
 * <p>Each trip is accrued in its own transaction ({@link AccrualService#accrue}); a second node that
 * got there first is a unique violation on {@code uq_freight_accrual_trip_currency}, caught here and
 * counted as done. Adjustments claim open accruals {@code FOR UPDATE SKIP LOCKED}, so two nodes split
 * the work and an approval holding a row is passed over until the next pass.
 */
@Service
public class AccrualSweep {

    public static final String ACTOR_LABEL = "system:accrual-sweep";

    private static final Logger log = LoggerFactory.getLogger(AccrualSweep.class);

    private static final int PAGE = 200;

    private static final UUID FIRST = new UUID(0L, 0L);

    private final AccruableTripLookupPort tripLookupPort;
    private final TripCostLookupPort tripCostLookupPort;
    private final FreightAccrualRepository accrualRepository;
    private final AccrualService accrualService;
    private final CompanySettingsPort companySettingsPort;
    private final CompanyScopeLoader companyScopeLoader;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration lookback;
    private final ObjectProvider<TenantOperationGate> operationGate;

    public AccrualSweep(AccruableTripLookupPort tripLookupPort, TripCostLookupPort tripCostLookupPort,
            FreightAccrualRepository accrualRepository, AccrualService accrualService,
            CompanySettingsPort companySettingsPort, CompanyScopeLoader companyScopeLoader,
            PlatformTransactionManager transactionManager, Clock clock,
            @Value("${tms.settlement.accrual.lookback:31d}") Duration lookback,
            ObjectProvider<TenantOperationGate> operationGate) {
        this.operationGate = operationGate;
        this.tripLookupPort = tripLookupPort;
        this.tripCostLookupPort = tripCostLookupPort;
        this.accrualRepository = accrualRepository;
        this.accrualService = accrualService;
        this.companySettingsPort = companySettingsPort;
        this.companyScopeLoader = companyScopeLoader;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.lookback = lookback;
    }

    /**
     * The scheduled pass: every company with a trip executed within the lookback, or with an open
     * accrual to adjust. A company that fails is logged and the pass moves on. An inactive company is
     * not swept, and neither is one whose organization MasterAdmin has suspended (ADR-021): a
     * suspension stops every write of the tenant, and a machine writing on its behalf is no exception.
     */
    public AccrualSweepResult sweepAll() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime since = now.minus(lookback);
        Set<UUID> companies = new HashSet<>(tripLookupPort.companiesWithExecutionSince(since));
        companies.addAll(accrualRepository.findCompaniesWithOpenAccruals());
        AccrualSweepResult total = AccrualSweepResult.NONE;
        for (UUID companyId : companies) {
            Optional<CompanyScope> scope = companyScopeLoader.loadActiveCompanyScope(companyId);
            if (scope.isEmpty()) {
                continue; // A deactivated tenant is not swept.
            }
            try {
                total = total.plus(SystemJobAuthentication.runAs(scope.get(), ACTOR_LABEL,
                        () -> suspended(scope.get()) ? AccrualSweepResult.NONE
                                : sweepCompany(scope.get(), since, now.plusSeconds(1), null)));
            } catch (RuntimeException failure) {
                log.error("The accrual sweep failed for company {}; the next pass retries it", companyId, failure);
            }
        }
        return total;
    }

    /**
     * A person's "accrue now" over company calendar days {@code [from, to]}: the same work as the
     * scheduled pass, for any range, attributed to the person.
     */
    public AccrualSweepResult sweepRange(CompanyScope scope, LocalDate from, LocalDate to, UUID actorId) {
        AccrualService.requireRange(from, to);
        return sweepCompany(scope, from.atStartOfDay(scope.zoneId()).toOffsetDateTime(),
                to.plusDays(1).atStartOfDay(scope.zoneId()).toOffsetDateTime(), actorId);
    }

    /** One company: accrue what executed in {@code [from, to)} and has no accrual, then adjust. */
    AccrualSweepResult sweepCompany(CompanyScope scope, OffsetDateTime from, OffsetDateTime to, UUID actorId) {
        AccrualTrigger trigger = companySettingsPort.settingsOf(scope.companyId()).accrualTrigger();
        int created = 0;
        int unaccrued = 0;
        int failed = 0;
        AccruableTrip after = null;
        while (true) {
            List<AccruableTrip> page = tripLookupPort.findExecuted(scope.companyId(), trigger, from, to, after, PAGE);
            if (page.isEmpty()) {
                break;
            }
            after = page.get(page.size() - 1);
            Set<UUID> ids = page.stream().map(AccruableTrip::tripId).collect(Collectors.toSet());
            Set<UUID> accrued = new HashSet<>(accrualRepository.findAccruedTripIds(scope.companyId(), ids));
            ids.removeAll(accrued);
            Map<UUID, TripCostSummary> costs = ids.isEmpty() ? Map.of()
                    : tripCostLookupPort.findCosts(ids, scope.companyId());
            for (AccruableTrip trip : page) {
                if (!ids.contains(trip.tripId())) {
                    continue;
                }
                AccrualPricing pricing = AccrualPricing.of(trip, costs.get(trip.tripId()));
                if (pricing instanceof AccrualPricing.Priced priced) {
                    // One trip that throws must not abort the company's pass: it is logged, counted
                    // and retried on the next pass, and the others go on.
                    try {
                        if (accrueOne(scope, trigger, trip, priced, actorId)) {
                            created++;
                        }
                    } catch (RuntimeException failure) {
                        failed++;
                        log.warn("The accrual of trip {} of company {} failed; the next pass retries it",
                                trip.tripId(), scope.companyId(), failure);
                    }
                } else if (pricing instanceof AccrualPricing.Unpriced) {
                    unaccrued++;
                }
            }
            if (page.size() < PAGE) {
                break;
            }
        }
        int adjusted = adjustOpen(scope);
        return new AccrualSweepResult(created, adjusted, 0, unaccrued, failed);
    }

    /** Whether MasterAdmin has suspended the company's organization; asked on the company's own connection. */
    private boolean suspended(CompanyScope scope) {
        TenantOperationGate gate = operationGate.getIfAvailable();
        if (gate == null || gate.currentSuspension(scope).isEmpty()) {
            return false;
        }
        log.debug("Accrual sweep skipped for suspended company {}", scope.companyId());
        return true;
    }

    private boolean accrueOne(CompanyScope scope, AccrualTrigger trigger, AccruableTrip trip,
            AccrualPricing.Priced priced, UUID actorId) {
        try {
            return accrualService.accrue(scope, trigger, trip, priced, actorId);
        } catch (DataIntegrityViolationException concurrent) {
            String message = String.valueOf(concurrent.getMostSpecificCause().getMessage());
            if (message.contains("uq_freight_accrual_trip_currency") || message.contains("uq_freight_accrual_entry_source")) {
                return false; // Another node accrued it first: done.
            }
            throw concurrent;
        }
    }

    /** Walks every open accrual of the company once, in claimed batches. */
    private int adjustOpen(CompanyScope scope) {
        int adjusted = 0;
        UUID afterId = FIRST;
        while (afterId != null) {
            UUID cursor = afterId;
            int[] count = {0};
            afterId = transactionTemplate.execute(status -> {
                List<FreightAccrual> claimed = accrualRepository.claimOpen(scope.companyId(), cursor, PAGE);
                if (claimed.isEmpty()) {
                    return null;
                }
                Map<UUID, TripCostSummary> costs = tripCostLookupPort.findCosts(
                        claimed.stream().map(FreightAccrual::tripId).collect(Collectors.toSet()), scope.companyId());
                for (FreightAccrual accrual : claimed) {
                    if (accrualService.adjustToClosedActual(scope, accrual, costs.get(accrual.tripId()))) {
                        count[0]++;
                    }
                }
                return claimed.size() < PAGE ? null : claimed.get(claimed.size() - 1).id();
            });
            adjusted += count[0];
        }
        return adjusted;
    }
}
