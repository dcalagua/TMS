package com.ebim.tms.shared.security;

import com.ebim.tms.shared.security.TenantRuntimeRoleCheck.Outcome;
import com.ebim.tms.shared.security.TenantRuntimeRoleCheck.Probe;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The re-evaluation policy behind {@link TenantRuntimeRoleCheck}: which verdict is served, when
 * the database is asked again, and what gets logged. Kept apart from the Spring bean so that the
 * probe, the clock and the executor can be replaced in a unit test without giving the bean a
 * second constructor.
 *
 * <ul>
 *   <li>{@link #evaluateNow()} probes synchronously - the application-ready event, which Spring
 *       Boot publishes before readiness starts accepting traffic, so the first verdict is in place
 *       before the first probe that matters.</li>
 *   <li>{@link #current()} never waits for the database. When the last evaluation is at least the
 *       interval old it hands a re-evaluation to the executor - at most one in flight - and answers
 *       with the verdict it already has; the new verdict is served from the next call. This is not
 *       a nicety: against a stopped database the probe waits out the pool's connection timeout
 *       (thirty seconds by default, observed in the local reproduction), and a health request that
 *       waited with it would time out at the load balancer - reporting a database outage through
 *       this indicator after all.</li>
 *   <li>An inconclusive probe - the database could not be asked - leaves the last conclusive
 *       verdict in place, and still counts as an evaluation, so a failing database is not hammered
 *       either.</li>
 *   <li>The log follows transitions: the first conclusive verdict, every change of verdict, and
 *       the first inconclusive probe after a conclusive one.</li>
 * </ul>
 */
final class TenantRuntimeRoleVerdicts {

    private final Supplier<Probe> probe;
    private final Clock clock;
    private final Duration reevaluationInterval;
    private final Executor executor;
    private final AtomicBoolean reevaluationInFlight = new AtomicBoolean();
    private final ReentrantLock evaluating = new ReentrantLock();

    private volatile Snapshot snapshot = Snapshot.NEVER_EVALUATED;

    TenantRuntimeRoleVerdicts(Supplier<Probe> probe, Clock clock, Duration reevaluationInterval,
            Executor executor) {
        this.probe = probe;
        this.clock = clock;
        this.reevaluationInterval = reevaluationInterval;
        this.executor = executor;
    }

    /** Probes on a dedicated virtual thread per re-evaluation. */
    static Executor virtualThreads() {
        return task -> Thread.ofVirtual().name("tenant-runtime-role-probe").start(task);
    }

    void evaluateNow() {
        evaluate();
    }

    Optional<Outcome> current() {
        if (snapshot.isDue(clock.instant(), reevaluationInterval)
                && reevaluationInFlight.compareAndSet(false, true)) {
            try {
                executor.execute(() -> {
                    try {
                        if (snapshot.isDue(clock.instant(), reevaluationInterval)) {
                            evaluate();
                        }
                    } finally {
                        reevaluationInFlight.set(false);
                    }
                });
            } catch (RejectedExecutionException notStarted) {
                reevaluationInFlight.set(false);
            }
        }
        return Optional.ofNullable(snapshot.outcome());
    }

    private void evaluate() {
        evaluating.lock();
        try {
            Probe result;
            try {
                result = probe.get();
            } catch (RuntimeException unexpected) {
                result = new Probe.Inconclusive(unexpected.toString());
            }
            Snapshot previous = snapshot;
            Instant now = clock.instant();
            switch (result) {
                case Probe.Concluded concluded -> {
                    if (previous.outcome() != concluded.outcome()) {
                        concluded.outcome().logTo(TenantRuntimeRoleCheck.log, concluded.roles());
                    }
                    snapshot = new Snapshot(concluded.outcome(), now, false);
                }
                case Probe.Inconclusive inconclusive -> {
                    if (!previous.lastProbeInconclusive()) {
                        TenantRuntimeRoleCheck.log.warn("Could not determine whether this application "
                                + "can enter '{}', so the ADR-005 runtime role verdict is unchanged ({}). "
                                + "This is a database availability problem, not a role problem. Cause: {}",
                                TenantScopedDataSource.RUNTIME_ROLE,
                                previous.outcome() == null ? "none yet" : previous.outcome(),
                                inconclusive.cause());
                    }
                    snapshot = new Snapshot(previous.outcome(), now, true);
                }
            }
        } finally {
            evaluating.unlock();
        }
    }

    /**
     * The verdict being served and when the database was last asked. {@code outcome} is the last
     * <em>conclusive</em> one and survives inconclusive probes.
     */
    private record Snapshot(Outcome outcome, Instant evaluatedAt, boolean lastProbeInconclusive) {

        static final Snapshot NEVER_EVALUATED = new Snapshot(null, null, false);

        boolean isDue(Instant now, Duration interval) {
            return evaluatedAt == null || !now.isBefore(evaluatedAt.plus(interval));
        }
    }
}
