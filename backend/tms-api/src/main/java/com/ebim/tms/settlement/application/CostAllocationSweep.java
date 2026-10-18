package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.AllocationCostSource;
import com.ebim.tms.settlement.domain.AllocationTrigger;
import com.ebim.tms.settlement.domain.CostAllocationRun;
import com.ebim.tms.settlement.infrastructure.CostAllocationRepository;
import com.ebim.tms.shared.reference.AccruableTripLookupPort;
import com.ebim.tms.shared.reference.AccruableTripLookupPort.AccruableTrip;
import com.ebim.tms.shared.reference.TripCostLookupPort;
import com.ebim.tms.shared.reference.TripCostLookupPort.TripCostSummary;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.CompanyScopeLoader;
import com.ebim.tms.shared.security.SystemJobAuthentication;
import com.ebim.tms.shared.security.TenantOperationGate;
import com.ebim.tms.shared.settings.AccrualTrigger;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
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

/**
 * Keeps the cost-to-serve current without a listener (V68, ADR-026 section 5): a sweep over trips
 * that left within the lookback, for the two facts nobody calls in - a trip that left and was never
 * allocated, and a trip cost closed after its current run. Invoice approval allocates in its own
 * transaction and a person's "Recalcular" in theirs; neither waits for this.
 *
 * <p>Same tenancy and concurrency shape as {@link AccrualSweep}: one cross-tenant question on the
 * owner connection, then each company under {@link SystemJobAuthentication}, one transaction per
 * trip, and a unique violation from a second node counted as done.
 */
@Service
public class CostAllocationSweep {

    public static final String ACTOR_LABEL = "system:cost-allocation-sweep";

    private static final Logger log = LoggerFactory.getLogger(CostAllocationSweep.class);

    private static final int PAGE = 200;

    private final AccruableTripLookupPort tripLookupPort;
    private final TripCostLookupPort tripCostLookupPort;
    private final CostAllocationRepository repository;
    private final CostAllocationService allocationService;
    private final CompanyScopeLoader companyScopeLoader;
    private final Clock clock;
    private final Duration lookback;
    private final ObjectProvider<TenantOperationGate> operationGate;

    public CostAllocationSweep(AccruableTripLookupPort tripLookupPort, TripCostLookupPort tripCostLookupPort,
            CostAllocationRepository repository, CostAllocationService allocationService,
            CompanyScopeLoader companyScopeLoader, Clock clock,
            @Value("${tms.finance.cost-allocation.lookback:62d}") Duration lookback,
            ObjectProvider<TenantOperationGate> operationGate) {
        this.operationGate = operationGate;
        this.tripLookupPort = tripLookupPort;
        this.tripCostLookupPort = tripCostLookupPort;
        this.repository = repository;
        this.allocationService = allocationService;
        this.companyScopeLoader = companyScopeLoader;
        this.clock = clock;
        this.lookback = lookback;
    }

    /**
     * What one pass did. {@code withoutCost}: trips that left with no figure to split, counted;
     * {@code failed}: trips whose allocation threw and were skipped for this pass (logged, retried next).
     */
    public record Result(int allocated, int withoutCost, int failed) {

        static final Result NONE = new Result(0, 0, 0);

        Result plus(Result other) {
            return new Result(allocated + other.allocated, withoutCost + other.withoutCost, failed + other.failed);
        }
    }

    public Result sweepAll() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime since = now.minus(lookback);
        Result total = Result.NONE;
        for (UUID companyId : tripLookupPort.companiesWithExecutionSince(since)) {
            Optional<CompanyScope> scope = companyScopeLoader.loadActiveCompanyScope(companyId);
            if (scope.isEmpty()) {
                continue;
            }
            try {
                // A suspended organization (ADR-021) is not written to, by a person or by a machine.
                total = total.plus(SystemJobAuthentication.runAs(scope.get(), ACTOR_LABEL,
                        () -> suspended(scope.get()) ? Result.NONE : sweepCompany(scope.get(), since, now.plusSeconds(1))));
            } catch (RuntimeException failure) {
                log.error("The cost allocation sweep failed for company {}; the next pass retries it", companyId,
                        failure);
            }
        }
        return total;
    }

    Result sweepCompany(CompanyScope scope, OffsetDateTime from, OffsetDateTime to) {
        int allocated = 0;
        int withoutCost = 0;
        int failed = 0;
        AccruableTrip after = null;
        while (true) {
            List<AccruableTrip> page = tripLookupPort.findExecuted(scope.companyId(), AccrualTrigger.DISPATCHED,
                    from, to, after, PAGE);
            if (page.isEmpty()) {
                break;
            }
            after = page.get(page.size() - 1);
            Set<UUID> ids = page.stream().map(AccruableTrip::tripId).collect(Collectors.toSet());
            Map<UUID, CostAllocationRun> runs = repository.findCurrent(scope.companyId(), ids);
            Map<UUID, TripCostSummary> costs = tripCostLookupPort.findCosts(ids, scope.companyId());
            for (AccruableTrip trip : page) {
                Optional<AllocationTrigger> trigger = due(runs.get(trip.tripId()), costs.get(trip.tripId()));
                if (trigger.isEmpty()) {
                    if (!runs.containsKey(trip.tripId())) {
                        withoutCost++;
                    }
                    continue;
                }
                // One trip that throws must not abort the company's pass: logged, counted, retried.
                try {
                    if (allocateOne(scope, trip.tripId(), trigger.get())) {
                        allocated++;
                    }
                } catch (RuntimeException failure) {
                    failed++;
                    log.warn("The cost allocation of trip {} of company {} failed; the next pass retries it",
                            trip.tripId(), scope.companyId(), failure);
                }
            }
            if (page.size() < PAGE) {
                break;
            }
        }
        return new Result(allocated, withoutCost, failed);
    }

    /** Whether MasterAdmin has suspended the company's organization; asked on the company's own connection. */
    private boolean suspended(CompanyScope scope) {
        TenantOperationGate gate = operationGate.getIfAvailable();
        if (gate == null || gate.currentSuspension(scope).isEmpty()) {
            return false;
        }
        log.debug("Cost allocation sweep skipped for suspended company {}", scope.companyId());
        return true;
    }

    /**
     * Whether the trip needs a run now, and why. Never after an invoice: once the carrier billed the
     * trip, a re-close of the cost does not move what it cost to serve.
     */
    static Optional<AllocationTrigger> due(CostAllocationRun current, TripCostSummary cost) {
        boolean closedActual = cost != null && cost.closed() && positive(cost.actualAmount());
        if (current == null) {
            if (closedActual) {
                return Optional.of(AllocationTrigger.TRIP_COST_CLOSED);
            }
            return cost != null && positive(cost.expectedAmount())
                    ? Optional.of(AllocationTrigger.TRIP_EXECUTED)
                    : Optional.empty();
        }
        if (current.costSource() == AllocationCostSource.INVOICED || !closedActual) {
            return Optional.empty();
        }
        if (current.costSource() == AllocationCostSource.ESTIMATED) {
            return Optional.of(AllocationTrigger.TRIP_COST_CLOSED);
        }
        // ACTUAL: closed again (after a reopen) since this run was computed.
        return cost.closedAt() != null && cost.closedAt().isAfter(current.computedAt())
                ? Optional.of(AllocationTrigger.TRIP_COST_CLOSED)
                : Optional.empty();
    }

    private boolean allocateOne(CompanyScope scope, UUID tripId, AllocationTrigger trigger) {
        try {
            return allocationService.allocateBySweep(scope, tripId, trigger);
        } catch (DataIntegrityViolationException concurrent) {
            String message = String.valueOf(concurrent.getMostSpecificCause().getMessage());
            if (message.contains("uq_trip_cost_allocation_run_current")
                    || message.contains("uq_trip_cost_allocation_run_version")) {
                return false; // Another node allocated it first: done.
            }
            throw concurrent;
        }
    }

    private static boolean positive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }
}
