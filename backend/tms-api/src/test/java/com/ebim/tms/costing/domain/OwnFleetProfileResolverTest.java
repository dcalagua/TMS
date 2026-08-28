package com.ebim.tms.costing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Effective dating and precedence, provable without a database (V48, JOB 22). */
class OwnFleetProfileResolverTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();
    private static final UUID TYPE = UUID.randomUUID();
    private static final LocalDate TRIP_DAY = LocalDate.of(2026, 6, 15);

    private static OwnFleetCostProfile profile(UUID vehicleId, UUID typeId, String from, String to, String fixed) {
        OwnFleetCostProfile profile = new OwnFleetCostProfile(COMPANY, vehicleId, typeId, "PEN",
                LocalDate.parse(from));
        profile.setWindow(LocalDate.parse(from), to == null ? null : LocalDate.parse(to));
        profile.setRates(new BigDecimal(fixed), null, null, null, null, null, null);
        return profile;
    }

    @Test
    @DisplayName("a profile valid on the trip date is used")
    void validOnTheDay() {
        var card = profile(VEHICLE, null, "2026-01-01", "2027-01-01", "100.00");

        assertThat(OwnFleetProfileResolver.resolve(List.of(card), VEHICLE, TYPE, TRIP_DAY)).contains(card);
    }

    @Test
    @DisplayName("an expired profile is not used, and nothing takes its place")
    void expired() {
        var card = profile(VEHICLE, null, "2025-01-01", "2026-01-01", "100.00");

        // No cost at all is the honest answer. Falling back to the most recent expired rates would
        // cost a June trip at last year's fuel price and say nothing about having done so.
        assertThat(OwnFleetProfileResolver.resolve(List.of(card), VEHICLE, TYPE, TRIP_DAY)).isEmpty();
    }

    @Test
    @DisplayName("a future profile is not used yet")
    void future() {
        var card = profile(VEHICLE, null, "2026-09-01", null, "100.00");

        assertThat(OwnFleetProfileResolver.resolve(List.of(card), VEHICLE, TYPE, TRIP_DAY)).isEmpty();
    }

    @Test
    @DisplayName("effective_to is exclusive, so a rate change on one day is not a gap and not an overlap")
    void handover() {
        var ending = profile(VEHICLE, null, "2026-01-01", "2026-06-15", "100.00");
        var starting = profile(VEHICLE, null, "2026-06-15", null, "120.00");

        assertThat(ending.coversDate(TRIP_DAY)).isFalse();
        assertThat(starting.coversDate(TRIP_DAY)).isTrue();
        assertThat(OwnFleetProfileResolver.resolve(List.of(ending, starting), VEHICLE, TYPE, TRIP_DAY))
                .contains(starting);
    }

    @Test
    @DisplayName("a vehicle-specific profile outranks the one for its type")
    void vehicleBeatsType() {
        var forType = profile(null, TYPE, "2026-01-01", null, "100.00");
        var forVehicle = profile(VEHICLE, null, "2026-01-01", null, "140.00");

        assertThat(OwnFleetProfileResolver.resolve(List.of(forType, forVehicle), VEHICLE, TYPE, TRIP_DAY))
                .contains(forVehicle);
    }

    @Test
    @DisplayName("the type profile is used when the vehicle has none of its own")
    void fallsBackToType() {
        var forType = profile(null, TYPE, "2026-01-01", null, "100.00");

        assertThat(OwnFleetProfileResolver.resolve(List.of(forType), VEHICLE, TYPE, TRIP_DAY)).contains(forType);
    }

    @Test
    @DisplayName("an expired vehicle profile does not fall through to the type profile")
    void expiredSpecificDoesNotFallThrough() {
        var forType = profile(null, TYPE, "2026-01-01", null, "100.00");
        var expiredForVehicle = profile(VEHICLE, null, "2025-01-01", "2026-01-01", "140.00");

        // The type profile applies because the vehicle has NO profile in force - not because the
        // vehicle's own one expired. Same answer, and worth pinning: a reader could reasonably
        // expect the specific one to keep winning and produce nothing.
        assertThat(OwnFleetProfileResolver.resolve(List.of(forType, expiredForVehicle), VEHICLE, TYPE, TRIP_DAY))
                .contains(forType);
    }

    @Test
    @DisplayName("a deactivated profile is ignored")
    void deactivated() {
        var card = profile(VEHICLE, null, "2026-01-01", null, "100.00");
        card.setActive(false);

        assertThat(OwnFleetProfileResolver.resolve(List.of(card), VEHICLE, TYPE, TRIP_DAY)).isEmpty();
    }

    @Test
    @DisplayName("no profile at all resolves to nothing, which is not a zero cost")
    void nothing() {
        assertThat(OwnFleetProfileResolver.resolve(List.of(), VEHICLE, TYPE, TRIP_DAY)).isEmpty();
    }

    @Test
    @DisplayName("two overlapping profiles are refused rather than silently tie-broken")
    void overlapIsNotTieBroken() {
        var one = profile(VEHICLE, null, "2026-01-01", null, "100.00");
        var two = profile(VEHICLE, null, "2026-06-01", null, "120.00");

        // The database prevents this. If one ever reaches here the constraint has been lost, and
        // picking the cheaper or the newer would hide that behind a plausible number.
        assertThatThrownBy(() -> OwnFleetProfileResolver.resolve(List.of(one, two), VEHICLE, TYPE, TRIP_DAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    @DisplayName("a profile is about a vehicle or a type, never both and never neither")
    void oneTarget() {
        assertThatThrownBy(() -> new OwnFleetCostProfile(COMPANY, VEHICLE, TYPE, "PEN", TRIP_DAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OwnFleetCostProfile(COMPANY, null, null, "PEN", TRIP_DAY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
