package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.application.CostAllocationSweep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The timer behind {@link CostAllocationSweep} (V68, ADR-026), shaped like {@link AccrualSweepScheduler}. */
@Component
public class CostAllocationSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(CostAllocationSweepScheduler.class);

    private final CostAllocationSweep sweep;
    private final boolean enabled;

    public CostAllocationSweepScheduler(CostAllocationSweep sweep,
            @Value("${tms.finance.cost-allocation.sweep-enabled:false}") boolean enabled) {
        this.sweep = sweep;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${tms.finance.cost-allocation.sweep-interval:10m}",
            initialDelayString = "${tms.finance.cost-allocation.sweep-interval:10m}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            CostAllocationSweep.Result result = sweep.sweepAll();
            if (result.allocated() > 0) {
                log.info("Cost allocation sweep: {} trips allocated, {} executed trips without a cost",
                        result.allocated(), result.withoutCost());
            }
        } catch (RuntimeException failure) {
            log.error("A cost allocation sweep pass failed; the next pass retries", failure);
        }
    }
}
