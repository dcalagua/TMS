package com.ebim.tms.masterdata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parts of the canonical Location model that hold without a database, so they are proved on
 * a machine where Docker is unavailable too.
 *
 * <p>Three of these are load-bearing for {@code LocationCompatibilityProjector} and for the V14
 * backfill, and each would fail silently rather than loudly if it broke:
 *
 * <ul>
 *   <li>{@link LocationType} must be a superset of both legacy vocabularies, or the backfill
 *       loses a row's type;</li>
 *   <li>widening a legacy type and narrowing it back must be the identity for the values the
 *       legacy side owns, or an unrelated edit through the Origins API silently reclassifies a
 *       location;</li>
 *   <li>{@link Location#replaceRoles} must be a diff, not a replace, or every edit churns the
 *       {@code created_at} of roles the location already held.</li>
 * </ul>
 */
class LocationModelTest {

    private static Location location() {
        return new Location(UUID.randomUUID(), "LIM01", "Lima DC", LocationType.DISTRIBUTION_CENTER,
                "Av. Argentina 1234", "Puerta azul", "Callao", "Callao", "Callao", "PE", "America/Lima",
                new BigDecimal("-12.045600"), new BigDecimal("-77.031700"), null, 30, null, null,
                UUID.randomUUID());
    }

    @Test
    @DisplayName("the canonical type vocabulary covers every legacy origin and destination type")
    void canonicalTypeIsASupersetOfBothLegacyVocabularies() {
        List<String> canonical = EnumSet.allOf(LocationType.class).stream().map(Enum::name).toList();

        assertThat(canonical)
                .as("a legacy origin type with no canonical counterpart cannot be backfilled")
                .containsAll(EnumSet.allOf(OriginType.class).stream().map(Enum::name).toList());
        assertThat(canonical)
                .as("a legacy destination type with no canonical counterpart cannot be backfilled")
                .containsAll(EnumSet.allOf(DestinationType.class).stream().map(Enum::name).toList());
    }

    @Test
    @DisplayName("widening a legacy origin type and narrowing it back is the identity")
    void originTypeRoundTrips() {
        assertThat(EnumSet.allOf(OriginType.class)).allSatisfy(type ->
                assertThat(LocationType.from(type).toOriginType()).isEqualTo(type));
    }

    @Test
    @DisplayName("widening a legacy destination type and narrowing it back is the identity")
    void destinationTypeRoundTrips() {
        assertThat(EnumSet.allOf(DestinationType.class)).allSatisfy(type ->
                assertThat(LocationType.from(type).toDestinationType()).isEqualTo(type));
    }

    @Test
    @DisplayName("narrowing a canonical type the legacy side cannot express falls back to its catch-all")
    void narrowingUsesEachLegacyCatchAll() {
        assertThat(LocationType.STORE.toOriginType()).isEqualTo(OriginType.OTHER);
        assertThat(LocationType.CUSTOMER.toOriginType()).isEqualTo(OriginType.OTHER);
        assertThat(LocationType.WAREHOUSE.toDestinationType()).isEqualTo(DestinationType.DELIVERY_POINT);
        assertThat(LocationType.PLANT.toDestinationType()).isEqualTo(DestinationType.DELIVERY_POINT);
    }

    @Test
    @DisplayName("only ORIGIN and SHIP_TO project; the other roles classify and nothing else")
    void onlyTwoRolesProject() {
        assertThat(EnumSet.allOf(LocationRole.class).stream().filter(LocationRole::isProjecting))
                .containsExactly(LocationRole.ORIGIN, LocationRole.SHIP_TO);
    }

    @Test
    @DisplayName("replacing roles adds what is new, removes what is gone and keeps what is unchanged")
    void replaceRolesDiffsRatherThanRebuilding() {
        Location location = location();
        location.replaceRoles(Set.of(LocationRole.ORIGIN, LocationRole.DC));
        LocationRoleAssignment originAssignment = location.roleAssignments().stream()
                .filter(assignment -> assignment.role() == LocationRole.ORIGIN)
                .findFirst()
                .orElseThrow();

        location.replaceRoles(Set.of(LocationRole.ORIGIN, LocationRole.SHIP_TO));

        assertThat(location.roles()).containsExactly(LocationRole.ORIGIN, LocationRole.SHIP_TO);
        assertThat(location.roleAssignments())
                .as("a role the location already held must keep its own assignment row, so its "
                        + "created_at keeps saying when the location first took that role")
                .contains(originAssignment);
    }

    @Test
    @DisplayName("roles come back in the enum's declaration order, so the API response is stable")
    void rolesAreOrdered() {
        Location location = location();

        location.replaceRoles(List.of(LocationRole.OTHER, LocationRole.SHIP_TO, LocationRole.ORIGIN));

        assertThat(location.roles())
                .containsExactly(LocationRole.ORIGIN, LocationRole.SHIP_TO, LocationRole.OTHER);
    }

    @Test
    @DisplayName("replacing with an empty set removes every role instead of throwing")
    void replaceRolesAcceptsAnEmptySet() {
        Location location = location();
        location.replaceRoles(Set.of(LocationRole.ORIGIN));

        location.replaceRoles(Set.of());

        assertThat(location.roles()).isEmpty();
        assertThat(location.hasRole(LocationRole.ORIGIN)).isFalse();
    }
}
