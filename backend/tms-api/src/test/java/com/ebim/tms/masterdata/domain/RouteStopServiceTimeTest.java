package com.ebim.tms.masterdata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The per-stop service-time override (V24) and the rule that resolves it:
 *
 * <pre>{@code effectiveServiceTime = routeStop.serviceTimeOverride ?? location.serviceTimeMinutes}</pre>
 *
 * <p>Both {@link Route#replaceStops} and {@link RouteStop#effectiveServiceTimeMinutes} are plain
 * in-memory operations, so this holds without a database - the same reasoning
 * {@code FrequencyCalendarTest} and {@code LocationModelTest} document for their own invariants.
 */
class RouteStopServiceTimeTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID STORE_A = UUID.randomUUID();
    private static final UUID STORE_B = UUID.randomUUID();

    /** A store that normally takes 15 minutes to serve. */
    private static Location storeTakingMinutes(int serviceTimeMinutes) {
        return new Location(COMPANY, "STO01", "Store", LocationType.STORE, "Av. Argentina 1234", null,
                "Callao", "Callao", "Callao", "PE", "America/Lima",
                new BigDecimal("-12.045600"), new BigDecimal("-77.031700"), null, serviceTimeMinutes, null, null,
                ACTOR);
    }

    private static Route route() {
        return new Route(COMPANY, "NIGHT", "Night corridor", UUID.randomUUID(), null, null, null, null, ACTOR);
    }

    private static RouteStop stopFor(Route route, UUID destinationId) {
        return route.stops().stream()
                .filter(stop -> stop.destinationId().equals(destinationId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("a stop with no override serves for as long as its location says")
    void noOverrideInheritsTheLocationsServiceTime() {
        Route route = route();
        route.replaceStops(List.of(new RouteStopInput(STORE_A, null)), ACTOR);

        assertThat(stopFor(route, STORE_A).serviceTimeOverrideMinutes()).isNull();
        assertThat(stopFor(route, STORE_A).effectiveServiceTimeMinutes(storeTakingMinutes(15))).isEqualTo(15);
    }

    @Test
    @DisplayName("an override wins over the location's service time, on this route only")
    void overrideWinsOverTheLocation() {
        Route route = route();
        route.replaceStops(List.of(new RouteStopInput(STORE_A, 40)), ACTOR);

        assertThat(stopFor(route, STORE_A).effectiveServiceTimeMinutes(storeTakingMinutes(15))).isEqualTo(40);
    }

    @Test
    @DisplayName("zero is a real override - a drop-and-go stop - not a synonym for 'inherit'")
    void zeroIsARealOverride() {
        Route route = route();
        route.replaceStops(List.of(new RouteStopInput(STORE_A, 0)), ACTOR);

        assertThat(stopFor(route, STORE_A).effectiveServiceTimeMinutes(storeTakingMinutes(15))).isZero();
    }

    @Test
    @DisplayName("an unresolvable destination and no override leaves the service time unknown, not zero")
    void unresolvedDestinationWithoutOverrideIsUnknown() {
        Route route = route();
        route.replaceStops(List.of(new RouteStopInput(STORE_A, null)), ACTOR);

        assertThat(stopFor(route, STORE_A).effectiveServiceTimeMinutes(null)).isNull();
    }

    @Test
    @DisplayName("replaceStops updates an existing stop's override in place, keeping the stop row")
    void replaceStopsUpdatesTheOverrideInPlace() {
        Route route = route();
        route.replaceStops(List.of(new RouteStopInput(STORE_A, 40)), ACTOR);

        route.replaceStops(List.of(new RouteStopInput(STORE_B, null), new RouteStopInput(STORE_A, 25)), ACTOR);

        assertThat(route.stops()).extracting(RouteStop::destinationId).containsExactly(STORE_B, STORE_A);
        assertThat(stopFor(route, STORE_A).sequence()).isEqualTo(2);
        assertThat(stopFor(route, STORE_A).serviceTimeOverrideMinutes()).isEqualTo(25);
        assertThat(stopFor(route, STORE_B).serviceTimeOverrideMinutes()).isNull();
    }

    @Test
    @DisplayName("a stop re-sent without an override loses the one it had - the request is the wanted state, not a delta")
    void resendingWithoutAnOverrideClearsIt() {
        Route route = route();
        route.replaceStops(List.of(new RouteStopInput(STORE_A, 40)), ACTOR);

        route.replaceStops(List.of(new RouteStopInput(STORE_A, null)), ACTOR);

        assertThat(stopFor(route, STORE_A).serviceTimeOverrideMinutes()).isNull();
        assertThat(stopFor(route, STORE_A).effectiveServiceTimeMinutes(storeTakingMinutes(15))).isEqualTo(15);
    }
}
