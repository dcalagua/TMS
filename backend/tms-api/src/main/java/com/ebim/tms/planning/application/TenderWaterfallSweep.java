package com.ebim.tms.planning.application;

import com.ebim.tms.planning.application.TenderWaterfallService.AutoAdvanceOutcome;
import com.ebim.tms.planning.infrastructure.TenderWaterfallRepository;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.CompanyScopeLoader;
import com.ebim.tms.shared.security.SystemJobAuthentication;
import com.ebim.tms.shared.security.TenantOperationGate;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Advances tender waterfalls whose offer has lapsed, without a dispatcher watching the clock
 * (ITEM 10; {@code docs/domain/CARRIER_SELECTION_AND_WATERFALL_V1.md} section 8).
 *
 * <p><b>The same steps as the "Avanzar" button.</b> Each due waterfall goes through
 * {@link TenderWaterfallService#autoAdvance}, which is {@code advance} minus the refusal: the lapsed
 * offer is closed, the tender row materialises its {@code EXPIRED}, and the next stored candidate is
 * offered through the ordinary tender path. There is no second way to tender.
 *
 * <h2>Tenancy</h2>
 *
 * <p>As {@code AccrualSweep}: one cross-tenant question on the owner connection (which companies have
 * a waterfall due), then each company's work under {@link SystemJobAuthentication}, so RLS binds the
 * connection to that company and the audit trail names {@value #ACTOR_LABEL} as the actor. An
 * inactive company is not swept, nor one MasterAdmin has suspended (ADR-021).
 *
 * <h2>Concurrency and idempotency</h2>
 *
 * <p>Each shipment is advanced in its own transaction that first takes the trip's row lock with
 * {@code FOR UPDATE SKIP LOCKED} - the lock every tender mutation already serialises on. A second
 * node, a carrier accepting at that instant or a dispatcher clicking "Avanzar" holds it or waits for
 * it; the sweep never waits, it passes the shipment over until the next pass. Inside the lock the
 * deadline is re-read, so a shipment advanced a moment ago by somebody else is {@code NOT_DUE} and
 * nothing is offered twice; {@code uq_trip_tender_live} remains the backstop. One shipment that
 * fails rolls back alone and is retried on the next pass.
 */
@Service
public class TenderWaterfallSweep {

    public static final String ACTOR_LABEL = "system:tender-waterfall";

    /** How many shipments one company may advance per pass; the rest wait for the next tick. */
    static final int PAGE = 100;

    private static final Logger log = LoggerFactory.getLogger(TenderWaterfallSweep.class);

    private final TenderWaterfallRepository waterfallRepository;
    private final TenderWaterfallService waterfallService;
    private final CompanyScopeLoader companyScopeLoader;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ObjectProvider<TenantOperationGate> operationGate;

    public TenderWaterfallSweep(TenderWaterfallRepository waterfallRepository,
            TenderWaterfallService waterfallService, CompanyScopeLoader companyScopeLoader,
            PlatformTransactionManager transactionManager, Clock clock,
            ObjectProvider<TenantOperationGate> operationGate) {
        this.waterfallRepository = waterfallRepository;
        this.waterfallService = waterfallService;
        this.companyScopeLoader = companyScopeLoader;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.operationGate = operationGate;
    }

    /**
     * What one pass did.
     *
     * @param skipped shipments another transaction held (passed over), or with nothing to do after all
     */
    public record Result(int advanced, int exhausted, int skipped, int unsigned, int failed) {

        public static final Result NONE = new Result(0, 0, 0, 0, 0);

        Result plus(Result other) {
            return new Result(advanced + other.advanced, exhausted + other.exhausted, skipped + other.skipped,
                    unsigned + other.unsigned, failed + other.failed);
        }

        public boolean didSomething() {
            return advanced > 0 || exhausted > 0 || failed > 0;
        }
    }

    /** The scheduled pass over every company with a waterfall due. */
    public Result sweepAll() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Result total = Result.NONE;
        for (UUID companyId : waterfallRepository.findCompaniesWithDueWaterfalls(now)) {
            Optional<CompanyScope> scope = companyScopeLoader.loadActiveCompanyScope(companyId);
            if (scope.isEmpty()) {
                continue;
            }
            try {
                total = total.plus(SystemJobAuthentication.runAs(scope.get(), ACTOR_LABEL,
                        () -> suspended(scope.get()) ? Result.NONE : sweepCompany(scope.get(), now)));
            } catch (RuntimeException failure) {
                log.error("The tender waterfall sweep failed for company {}; the next pass retries it",
                        companyId, failure);
            }
        }
        return total;
    }

    /** One company's due shipments, each in its own transaction. Runs under the job's authentication. */
    Result sweepCompany(CompanyScope scope, OffsetDateTime now) {
        Map<AutoAdvanceOutcome, Integer> outcomes = new EnumMap<>(AutoAdvanceOutcome.class);
        int skipped = 0;
        int failed = 0;
        List<UUID> due = waterfallRepository.findDueTripIds(scope.companyId(), now, PAGE);
        for (UUID tripId : due) {
            try {
                AutoAdvanceOutcome outcome = transactionTemplate.execute(status ->
                        waterfallRepository.lockTripIfFree(tripId, scope.companyId()).isEmpty()
                                ? null
                                : waterfallService.autoAdvance(scope, tripId));
                if (outcome == null) {
                    skipped++;
                } else {
                    outcomes.merge(outcome, 1, Integer::sum);
                }
            } catch (RuntimeException failure) {
                failed++;
                log.warn("Advancing the tender waterfall of trip {} of company {} failed; the next pass retries it",
                        tripId, scope.companyId(), failure);
            }
        }
        int unsigned = outcomes.getOrDefault(AutoAdvanceOutcome.NO_SIGNATORY, 0);
        if (unsigned > 0) {
            log.debug("{} waterfall(s) of company {} record nobody who started them and wait for a dispatcher",
                    unsigned, scope.companyId());
        }
        return new Result(outcomes.getOrDefault(AutoAdvanceOutcome.ADVANCED, 0),
                outcomes.getOrDefault(AutoAdvanceOutcome.EXHAUSTED, 0),
                skipped + outcomes.getOrDefault(AutoAdvanceOutcome.NOT_DUE, 0)
                        + outcomes.getOrDefault(AutoAdvanceOutcome.NOT_RUNNING, 0),
                unsigned, failed);
    }

    private boolean suspended(CompanyScope scope) {
        TenantOperationGate gate = operationGate.getIfAvailable();
        return gate != null && gate.currentSuspension(scope).isPresent();
    }
}
