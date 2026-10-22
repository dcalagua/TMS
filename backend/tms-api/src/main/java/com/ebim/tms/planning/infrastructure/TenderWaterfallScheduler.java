package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.application.TenderWaterfallSweep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer behind {@link TenderWaterfallSweep} (ITEM 10). Same shape as {@code AccrualSweepScheduler}:
 * {@code fixedDelay}, so a slow pass runs less often instead of overlapping itself; registered always,
 * with the flag read on each tick.
 *
 * <p><b>OFF by default</b> ({@code tms.planning.tender-waterfall.auto-advance.enabled=false}). Turning
 * it on sends offers to carriers with nobody clicking, signed with the person who started each
 * waterfall; that is a decision for the deployment, not a side effect of upgrading.
 */
@Component
public class TenderWaterfallScheduler {

    private static final Logger log = LoggerFactory.getLogger(TenderWaterfallScheduler.class);

    private final TenderWaterfallSweep sweep;
    private final boolean enabled;

    public TenderWaterfallScheduler(TenderWaterfallSweep sweep,
            @Value("${tms.planning.tender-waterfall.auto-advance.enabled:false}") boolean enabled) {
        this.sweep = sweep;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${tms.planning.tender-waterfall.auto-advance.interval:1m}",
            initialDelayString = "${tms.planning.tender-waterfall.auto-advance.interval:1m}")
    public void advance() {
        if (!enabled) {
            return;
        }
        try {
            TenderWaterfallSweep.Result result = sweep.sweepAll();
            if (result.didSomething()) {
                log.info("Tender waterfall sweep: {} advanced, {} exhausted, {} failed",
                        result.advanced(), result.exhausted(), result.failed());
            }
        } catch (RuntimeException failure) {
            log.error("A tender waterfall sweep pass failed; the next pass retries", failure);
        }
    }
}
