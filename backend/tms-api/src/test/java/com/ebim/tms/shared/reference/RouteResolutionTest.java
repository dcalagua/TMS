package com.ebim.tms.shared.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-014 section 6: the four answers of route resolution, as a pure function. */
class RouteResolutionTest {

    private static final UUID ORIGIN = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID OTHER_STORE = UUID.randomUUID();
    private static final UUID FREQUENCY = UUID.randomUUID();

    @Test
    @DisplayName("NOT_CONFIGURED when the origin has no active route at all")
    void notConfiguredWithoutRoutes() {
        RouteResolution resolution = RouteResolution.resolve(List.of(), Map.of(), STORE);

        assertThat(resolution.status()).isEqualTo(RouteResolution.Status.NOT_CONFIGURED);
        assertThat(resolution.routeCode()).isNull();
        assertThat(resolution.candidates()).isEmpty();
    }

    @Test
    @DisplayName("inactive and empty routes do not count as configured - the Corridors rule")
    void inactiveAndEmptyRoutesAreNotCorridors() {
        RouteTemplate inactive = route("R-OFF", false, STORE);
        RouteTemplate empty = route("R-EMPTY", true);

        assertThat(RouteResolution.resolve(List.of(inactive, empty), Map.of(), STORE).status())
                .isEqualTo(RouteResolution.Status.NOT_CONFIGURED);
    }

    @Test
    @DisplayName("NOT_FOUND when the origin has routes and none stops at the destination")
    void notFound() {
        RouteResolution resolution = RouteResolution.resolve(List.of(route("R-1", true, OTHER_STORE)), Map.of(), STORE);

        assertThat(resolution.status()).isEqualTo(RouteResolution.Status.NOT_FOUND);
        assertThat(resolution.route()).isEmpty();
    }

    @Test
    @DisplayName("RESOLVED with exactly one compatible route, carrying its frequency and the stop position")
    void resolved() {
        RouteTemplate route = route("R-1", true, OTHER_STORE, STORE);

        RouteResolution resolution = RouteResolution.resolve(
                List.of(route, route("R-2", true, OTHER_STORE)), Map.of(route.id(), FREQUENCY), STORE);

        assertThat(resolution.status()).isEqualTo(RouteResolution.Status.RESOLVED);
        assertThat(resolution.routeCode()).isEqualTo("R-1");
        assertThat(resolution.route()).hasValueSatisfying(resolved -> {
            assertThat(resolved.frequencyId()).isEqualTo(FREQUENCY);
            assertThat(resolved.position()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("AMBIGUOUS with several compatible routes, and none of them is picked")
    void ambiguousNeverPicksOne() {
        RouteResolution resolution = RouteResolution.resolve(
                List.of(route("R-A", true, STORE), route("R-B", true, STORE)), Map.of(), STORE);

        assertThat(resolution.status()).isEqualTo(RouteResolution.Status.AMBIGUOUS);
        assertThat(resolution.route()).isEmpty();
        assertThat(resolution.routeCode()).isNull();
        assertThat(resolution.candidates()).extracting(RouteResolution.ResolvedRoute::code)
                .containsExactly("R-A", "R-B");
    }

    private static RouteTemplate route(String code, boolean active, UUID... stops) {
        return new RouteTemplate(UUID.randomUUID(), code, code, ORIGIN, List.of(stops), active);
    }
}
