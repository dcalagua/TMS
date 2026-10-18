package com.ebim.tms.settlement.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.settlement.domain.AllocationCostSource;
import com.ebim.tms.settlement.domain.AllocationTrigger;
import com.ebim.tms.settlement.domain.CostAllocationRun;
import com.ebim.tms.shared.reference.TripCostLookupPort.TripCostSummary;
import com.ebim.tms.shared.settings.CostAllocationBasis;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** When the sweep writes a new version (V68, ADR-026 section 5), and when it leaves the current one alone. */
class CostAllocationSweepTest {

    private static final UUID TRIP = UUID.randomUUID();
    private static final OffsetDateTime COMPUTED = OffsetDateTime.parse("2026-10-18T10:00:00Z");

    private static TripCostSummary cost(String expected, String actual, OffsetDateTime closedAt) {
        return new TripCostSummary(TRIP, expected == null ? null : new BigDecimal(expected),
                actual == null ? null : new BigDecimal(actual), "PEN", closedAt != null, Map.of(), closedAt);
    }

    private static CostAllocationRun run(AllocationCostSource source) {
        return new CostAllocationRun(UUID.randomUUID(), TRIP, 1, CostAllocationBasis.WEIGHT, source, "PEN",
                BigDecimal.TEN, BigDecimal.TEN, 1, 0, null, LocalDate.of(2026, 10, 18), "SH-1", null, null, null,
                COMPUTED, null, AllocationTrigger.TRIP_EXECUTED, null);
    }

    @Test
    @DisplayName("never allocated: an estimate starts it, a closed actual starts it, nothing leaves it alone")
    void firstRun() {
        assertThat(CostAllocationSweep.due(null, cost("100", null, null))).contains(AllocationTrigger.TRIP_EXECUTED);
        assertThat(CostAllocationSweep.due(null, cost("100", "120", COMPUTED)))
                .contains(AllocationTrigger.TRIP_COST_CLOSED);
        assertThat(CostAllocationSweep.due(null, cost(null, null, null))).isEmpty();
        assertThat(CostAllocationSweep.due(null, null)).isEmpty();
    }

    @Test
    @DisplayName("on the estimate: the close moves it; an open actual does not")
    void estimateRun() {
        CostAllocationRun current = run(AllocationCostSource.ESTIMATED);
        assertThat(CostAllocationSweep.due(current, cost("100", "120", COMPUTED.minusDays(1))))
                .contains(AllocationTrigger.TRIP_COST_CLOSED);
        assertThat(CostAllocationSweep.due(current, cost("100", "120", null))).isEmpty();
    }

    @Test
    @DisplayName("on the actual: only a close after the run moves it")
    void actualRun() {
        CostAllocationRun current = run(AllocationCostSource.ACTUAL);
        assertThat(CostAllocationSweep.due(current, cost("100", "120", COMPUTED.minusMinutes(1)))).isEmpty();
        assertThat(CostAllocationSweep.due(current, cost("100", "130", COMPUTED.plusMinutes(1))))
                .contains(AllocationTrigger.TRIP_COST_CLOSED);
    }

    @Test
    @DisplayName("on the invoice: the sweep never touches it")
    void invoicedRun() {
        assertThat(CostAllocationSweep.due(run(AllocationCostSource.INVOICED), cost("100", "130",
                COMPUTED.plusDays(1)))).isEqualTo(Optional.empty());
    }
}
