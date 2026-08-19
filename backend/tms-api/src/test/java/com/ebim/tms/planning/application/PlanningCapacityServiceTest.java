package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.api.ConflictException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The capacity arithmetic, exercised without a database - the same way
 * {@code EffectiveCapacityResolverTest} covers fleet's resolver.
 *
 * <p>The cases that matter are the edges the step brief names explicitly: a null (unlimited)
 * limit, a zero limit, and a zero used quantity. None of them may produce a division by zero, and
 * none of them may quietly turn into "no limit".
 */
class PlanningCapacityServiceTest {

    private static final UUID TRIP = UUID.fromString("11111111-0000-4000-8000-000000000001");

    private final PlanningCapacityService service = new PlanningCapacityService();

    private static CapacityLoad load(String weight, String volume, String pallets) {
        return new CapacityLoad(new BigDecimal(weight), new BigDecimal(volume), new BigDecimal(pallets), 1);
    }

    private static CapacityLimits limits(String weight, String volume, String pallets) {
        return new CapacityLimits(weight == null ? null : new BigDecimal(weight),
                volume == null ? null : new BigDecimal(volume), pallets == null ? null : new BigDecimal(pallets));
    }

    @Test
    @DisplayName("percentages are used/limit to one decimal, per dimension")
    void percentagesArePerDimension() {
        TripCapacityView view = service.summarize(TRIP, CapacitySource.LIVE,
                limits("10000", "40", "20"), load("5000", "10", "15"));

        assertThat(view.weight().percentUsed()).isEqualByComparingTo("50.0");
        assertThat(view.volume().percentUsed()).isEqualByComparingTo("25.0");
        assertThat(view.pallets().percentUsed()).isEqualByComparingTo("75.0");
        assertThat(view.weight().remaining()).isEqualByComparingTo("5000");
        assertThat(view.withinCapacity()).isTrue();
        assertThat(view.source()).isEqualTo(CapacitySource.LIVE);
    }

    @Test
    @DisplayName("a null limit is unlimited: no percentage, no remaining, never exceeded")
    void nullLimitIsUnlimited() {
        TripCapacityView view = service.summarize(TRIP, CapacitySource.NONE,
                CapacityLimits.unlimited(), load("99999", "999", "999"));

        assertThat(view.weight().unlimited()).isTrue();
        assertThat(view.weight().limit()).isNull();
        assertThat(view.weight().percentUsed()).isNull();
        assertThat(view.weight().remaining()).isNull();
        assertThat(view.weight().exceeded()).isFalse();
        assertThat(view.withinCapacity()).isTrue();
    }

    @Test
    @DisplayName("a zero limit is a real limit: never a division by zero, and anything above zero is exceeded")
    void zeroLimitIsARealLimit() {
        TripCapacityView empty = service.summarize(TRIP, CapacitySource.LIVE,
                limits("5000", "20", "0"), load("100", "1", "0"));

        assertThat(empty.pallets().unlimited()).isFalse();
        assertThat(empty.pallets().limit()).isEqualByComparingTo("0");
        assertThat(empty.pallets().percentUsed()).as("no meaningful percentage of zero").isNull();
        assertThat(empty.pallets().exceeded()).isFalse();
        assertThat(empty.withinCapacity()).isTrue();

        TripCapacityView loaded = service.summarize(TRIP, CapacitySource.LIVE,
                limits("5000", "20", "0"), load("100", "1", "1"));

        assertThat(loaded.pallets().percentUsed()).isNull();
        assertThat(loaded.pallets().exceeded()).isTrue();
        assertThat(loaded.withinCapacity()).isFalse();
    }

    @Test
    @DisplayName("an empty trip reports zero used, not null, and 0% of a real limit")
    void emptyTripReportsZero() {
        TripCapacityView view = service.summarize(TRIP, CapacitySource.LIVE, limits("10000", "40", "20"),
                CapacityLoad.EMPTY);

        assertThat(view.weight().used()).isEqualByComparingTo("0");
        assertThat(view.weight().percentUsed()).isEqualByComparingTo("0.0");
        assertThat(view.orderCount()).isZero();
        assertThat(view.withinCapacity()).isTrue();
    }

    @Test
    @DisplayName("exactly at the limit fits; one unit over does not")
    void boundaryIsInclusive() {
        assertThat(service.summarize(TRIP, CapacitySource.LIVE, limits("1000", "5", "2"), load("1000", "5", "2"))
                .withinCapacity()).isTrue();
        assertThat(service.summarize(TRIP, CapacitySource.LIVE, limits("1000", "5", "2"), load("1000.001", "5", "2"))
                .withinCapacity()).isFalse();
    }

    @Test
    @DisplayName("a refusal names every dimension that failed, not only the first")
    void refusalNamesEveryFailedDimension() {
        assertThatThrownBy(() -> service.requireWithinCapacity("Order TO-1 does not fit trip 2",
                limits("1000", "5", "2"), load("1500", "1", "3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("weight 1500 kg exceeds the capacity of 1000 kg")
                .hasMessageContaining("pallets 3 pallets exceeds the capacity of 2 pallets")
                .hasMessageNotContainingAny("volume");
    }

    @Test
    @DisplayName("an unlimited dimension never refuses, whatever is loaded into it")
    void unlimitedNeverRefuses() {
        service.requireWithinCapacity("anything", CapacityLimits.unlimited(), load("99999", "999", "999"));
    }
}
