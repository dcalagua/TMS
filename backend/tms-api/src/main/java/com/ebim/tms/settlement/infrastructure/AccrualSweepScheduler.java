package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.application.AccrualSweep;
import com.ebim.tms.settlement.application.AccrualSweepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer behind {@link AccrualSweep} (V65, ADR-023). {@code fixedDelay}, like the webhook
 * dispatcher, so a slow pass runs less often instead of overlapping itself. Registered always; the
 * flag is read on each tick, so "disabled" cannot be misread from an empty property.
 */
@Component
public class AccrualSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccrualSweepScheduler.class);

    private final AccrualSweep sweep;
    private final boolean enabled;

    public AccrualSweepScheduler(AccrualSweep sweep,
            @Value("${tms.settlement.accrual.sweep-enabled:true}") boolean enabled) {
        this.sweep = sweep;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${tms.settlement.accrual.sweep-interval:5m}",
            initialDelayString = "${tms.settlement.accrual.sweep-interval:5m}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            AccrualSweepResult result = sweep.sweepAll();
            if (result.created() > 0 || result.adjusted() > 0) {
                log.info("Accrual sweep: {} created, {} adjusted, {} executed trips not accruable",
                        result.created(), result.adjusted(), result.unaccrued());
            }
        } catch (RuntimeException failure) {
            log.error("An accrual sweep pass failed; the next pass retries", failure);
        }
    }
}
