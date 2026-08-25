package com.ebim.tms.masterdata.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parts of the Location model that hold without a database, so they are proved on a machine
 * where Docker is unavailable too - which, on this host, is every machine (BASELINE E-1).
 *
 * <p>What these pin down is the separation the whole domain rests on: a location's
 * <em>type</em> says what the place is and admits exactly one value; its <em>roles</em> say how
 * it may be used in a movement and admit a set. Before V23 the role vocabulary carried five
 * values that were really types, and the screens showed "Type: Store / Roles: Store". The first
 * test here is what stops that coming back.
 */
class LocationModelTest {

    private static Location location() {
        return new Location(UUID.randomUUID(), "LIM01", "Lima DC", LocationType.DISTRIBUTION_CENTER,
                "Av. Argentina 1234", "Puerta azul", "Callao", "Callao", "Callao", "PE", "America/Lima",
                new BigDecimal("-12.045600"), new BigDecimal("-77.031700"), null, 30, null, null,
                UUID.randomUUID());
    }

    @Test
    @DisplayName("a role is an operational use and nothing else: exactly ORIGIN and DESTINATION")
    void roleVocabularyCarriesNoClassification() {
        assertThat(EnumSet.allOf(LocationRole.class))
                .as("a value that names a kind of place belongs in LocationType, which already "
                        + "has one - a role that classifies is how the Type/Roles duplication "
                        + "V23 removed gets reintroduced")
                .containsExactly(LocationRole.ORIGIN, LocationRole.DESTINATION);
    }

    @Test
    @DisplayName("the retired role vocabulary no longer parses, so stale payloads fail loudly")
    void retiredRolesAreRejected() {
        for (String retired : List.of("SHIP_TO", "STORE", "DC", "PLANT", "HUB", "OTHER")) {
            assertThatThrownBy(() -> LocationRole.valueOf(retired))
                    .as("%s was a V14 role; a client still sending it must get an error, not a "
                            + "silently dropped capability", retired)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the type vocabulary still covers every kind of place the two legacy masters knew")
    void typeVocabularyIsComplete() {
        assertThat(EnumSet.allOf(LocationType.class).stream().map(Enum::name).toList())
                .as("V14 built this as the union of the origin and destination type enums so its "
                        + "backfill was lossless; narrowing it now would strand rows that carry "
                        + "the dropped value")
                .containsExactlyInAnyOrder("WAREHOUSE", "DISTRIBUTION_CENTER", "PLANT", "HUB", "OTHER",
                        "CUSTOMER", "STORE", "BRANCH", "DELIVERY_POINT");
    }

    @Test
    @DisplayName("one location may ship and receive, which is the entire point of the model")
    void aLocationMayHoldBothRoles() {
        Location store = location();

        store.replaceRoles(Set.of(LocationRole.ORIGIN, LocationRole.DESTINATION));

        assertThat(store.hasRole(LocationRole.ORIGIN)).isTrue();
        assertThat(store.hasRole(LocationRole.DESTINATION)).isTrue();
        assertThat(store.roles()).containsExactly(LocationRole.ORIGIN, LocationRole.DESTINATION);
    }

    @Test
    @DisplayName("a location may hold only ORIGIN, or only DESTINATION")
    void aLocationMayHoldOneRole() {
        Location plant = location();
        plant.replaceRoles(Set.of(LocationRole.ORIGIN));
        assertThat(plant.roles()).containsExactly(LocationRole.ORIGIN);
        assertThat(plant.hasRole(LocationRole.DESTINATION)).isFalse();

        Location deliveryPoint = location();
        deliveryPoint.replaceRoles(Set.of(LocationRole.DESTINATION));
        assertThat(deliveryPoint.roles()).containsExactly(LocationRole.DESTINATION);
        assertThat(deliveryPoint.hasRole(LocationRole.ORIGIN)).isFalse();
    }

    @Test
    @DisplayName("replacing roles adds what is new, removes what is gone and keeps what is unchanged")
    void replaceRolesDiffsRatherThanRebuilding() {
        Location location = location();
        location.replaceRoles(Set.of(LocationRole.ORIGIN, LocationRole.DESTINATION));
        LocationRoleAssignment originAssignment = location.roleAssignments().stream()
                .filter(assignment -> assignment.role() == LocationRole.ORIGIN)
                .findFirst()
                .orElseThrow();

        location.replaceRoles(Set.of(LocationRole.ORIGIN));

        assertThat(location.roles()).containsExactly(LocationRole.ORIGIN);
        assertThat(location.roleAssignments())
                .as("a role the location already held must keep its own assignment row, so its "
                        + "created_at keeps saying when the location first took that role")
                .contains(originAssignment);
    }

    @Test
    @DisplayName("roles come back in the enum's declaration order, so the API response is stable")
    void rolesAreOrdered() {
        Location location = location();

        location.replaceRoles(List.of(LocationRole.DESTINATION, LocationRole.ORIGIN));

        assertThat(location.roles()).containsExactly(LocationRole.ORIGIN, LocationRole.DESTINATION);
    }

    @Test
    @DisplayName("replacing with an empty set removes every role instead of throwing")
    void replaceRolesAcceptsAnEmptySet() {
        Location location = location();
        location.replaceRoles(Set.of(LocationRole.ORIGIN));

        location.replaceRoles(Set.of());

        // The database permits this; the API does not (LocationRequest.roles is @NotEmpty). The
        // entity stays permissive so the V23 migration's "a location left with no operational
        // use" state is representable rather than un-loadable.
        assertThat(location.roles()).isEmpty();
        assertThat(location.hasRole(LocationRole.ORIGIN)).isFalse();
    }

    @Test
    @DisplayName("deactivating is one flag on one row, so it applies to both ends of a movement")
    void deactivationIsSingleSourced() {
        Location location = location();
        location.replaceRoles(Set.of(LocationRole.ORIGIN, LocationRole.DESTINATION));
        UUID actor = UUID.randomUUID();

        location.deactivate(actor);

        assertThat(location.active()).isFalse();
        assertThat(location.updatedBy()).isEqualTo(actor);
        assertThat(location.roles())
                .as("roles say what the place may be used for; active says whether it is in "
                        + "service. Taking it out of service must not silently rewrite its uses")
                .containsExactly(LocationRole.ORIGIN, LocationRole.DESTINATION);
    }
}
