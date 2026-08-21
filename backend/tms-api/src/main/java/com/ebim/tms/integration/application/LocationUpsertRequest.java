package com.ebim.tms.integration.application;

import com.ebim.tms.shared.reference.LocationIntakeCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Set;

/**
 * One location as the inbound API v1 receives it.
 *
 * <p>A separate record from {@code LocationRequest} on purpose. That one is the UI's contract and
 * may change with the UI; this one is a <em>published, versioned</em> contract that a partner's
 * code is compiled against, and the two must be free to move independently. What keeps them from
 * diverging in behaviour is not a shared type but a shared implementation: both end up in
 * {@code LocationService} through {@link LocationIntakeCommand}.
 *
 * <p>Validation here is deliberately thin - the identity fields and the lengths that make a
 * payload obviously malformed. Everything else (the code pattern, the coordinate bounds, the time
 * zone) is checked once, by the same Bean Validation constraints the UI path uses, inside
 * {@code LocationIntakeService}. Restating them here is how two surfaces start disagreeing about
 * what a valid location is.
 *
 * @param zoneCode the zone's user-facing code, not a TMS uuid. Omitted means "leave the zone as it
 *     is" on an update, which is what lets a partner synchronise addresses without undoing an
 *     operator's zoning
 * @param active   omitted means "do not change it"; {@code false} is how a partner closes a store
 */
public record LocationUpsertRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 40) String type,
        @NotEmpty(message = "a location must hold at least one role") Set<@NotBlank @Size(max = 40) String> roles,
        @Size(max = 500) String address,
        @Size(max = 300) String addressReference,
        @Size(max = 120) String district,
        @Size(max = 120) String province,
        @Size(max = 120) String department,
        @NotBlank @Size(max = 60) String country,
        @NotBlank @Size(max = 64) String timeZone,
        BigDecimal latitude,
        BigDecimal longitude,
        @Size(max = 32) String zoneCode,
        Integer serviceTimeMinutes,
        @Size(max = 60) String externalSystem,
        @Size(max = 100) String externalReference,
        Boolean active) {

    public LocationUpsertRequest {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /**
     * {@code serviceTimeMinutes} defaults to zero rather than being rejected: most sending systems
     * do not model dwell time, and demanding it would block an otherwise complete store master
     * over a figure the receiving warehouse can set later.
     */
    public LocationIntakeCommand toCommand() {
        return new LocationIntakeCommand(code, name, type, roles, address, addressReference, district, province,
                department, country, timeZone, latitude, longitude, zoneCode,
                serviceTimeMinutes == null ? 0 : serviceTimeMinutes, externalSystem, externalReference, active);
    }
}
