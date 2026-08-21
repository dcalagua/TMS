package com.ebim.tms.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the capability names the product speaks in tied to the permissions that are enforced.
 *
 * <p>The step brief named eleven capabilities the model has to express. They are asserted by
 * name here so that renaming or dropping one is a deliberate, visible change rather than a
 * silent hole in the authorization vocabulary.
 */
class CapabilityTest {

    private static final List<String> REQUIRED_CAPABILITIES = List.of(
            "MASTER_DATA_VIEW", "MASTER_DATA_MANAGE",
            "ORDERS_VIEW", "ORDERS_MANAGE",
            "PLANNING_VIEW", "PLANNING_MANAGE",
            "TRIPS_VIEW", "TRIPS_MANAGE",
            "FLEET_VIEW", "FLEET_MANAGE",
            "TRANSPORT_MONITOR_VIEW");

    @Test
    @DisplayName("every capability the product requires exists and maps to real permissions")
    void requiredCapabilitiesExist() {
        List<String> declared = Arrays.stream(Capability.values()).map(Enum::name).toList();

        assertThat(declared).containsAll(REQUIRED_CAPABILITIES);
        assertThat(Capability.values()).allSatisfy(capability ->
                assertThat(capability.permissions()).isNotEmpty());
    }

    @Test
    @DisplayName("no permission is orphaned: each one is reachable through a capability")
    void everyPermissionIsMapped() {
        assertThat(Capability.allMapped())
                .as("a permission no capability covers can never be surfaced in the UI")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(Permission.class));
    }

    @Test
    @DisplayName("planning and trips are separate capabilities backed by separate permissions")
    void planningAndTripsAreDistinct() {
        assertThat(Capability.PLANNING_MANAGE.permissions())
                .isNotEqualTo(Capability.TRIPS_MANAGE.permissions())
                .containsExactly(Permission.PLANNING_PLAN_MANAGE);
        // Three permissions and one capability: building a plan, operating a trip (V25) and
        // tendering it (V31) are separate authorities that share the trip workspace. What this
        // asserts is that none of them leaks into PLANNING_MANAGE, which is the split that matters.
        assertThat(Capability.TRIPS_MANAGE.permissions()).containsExactlyInAnyOrder(
                Permission.PLANNING_TRIP_MANAGE,
                Permission.PLANNING_TRIP_EXECUTE,
                Permission.PLANNING_TENDER_MANAGE);
        assertThat(Capability.TRIPS_MANAGE.permissions())
                .doesNotContain(Permission.PLANNING_PLAN_MANAGE, Permission.PLANNING_PLAN_READ);
    }

    @Test
    @DisplayName("the transport monitor is read-only: no manage capability exists for it")
    void transportMonitorIsReadOnly() {
        assertThat(Arrays.stream(Capability.values()).map(Enum::name))
                .doesNotContain("TRANSPORT_MONITOR_MANAGE");
        assertThat(Arrays.stream(Permission.values()).map(Permission::code))
                .doesNotContain("monitoring.transport:manage");
    }

    @Test
    @DisplayName("a capability is held when any of its permissions is, and not otherwise")
    void capabilityDerivation() {
        Set<Permission> onlyZones = Set.of(Permission.MASTERDATA_ZONE_READ);

        assertThat(Capability.MASTER_DATA_VIEW.isHeldWith(onlyZones)).isTrue();
        assertThat(Capability.MASTER_DATA_MANAGE.isHeldWith(onlyZones)).isFalse();
        assertThat(Capability.from(Set.of())).isEmpty();
        assertThat(Capability.from(onlyZones)).containsExactly(Capability.MASTER_DATA_VIEW);
    }

    @Test
    @DisplayName("permission codes are resource:action and resolve back to their constant")
    void permissionCodesRoundTrip() {
        assertThat(Permission.values()).allSatisfy(permission -> {
            assertThat(permission.code()).matches("[a-z][a-z0-9_.]*:[a-z_]+");
            assertThat(Permission.fromCode(permission.code())).contains(permission);
        });
        assertThat(Permission.fromCode("orders.order:destroy")).isEmpty();
    }
}
