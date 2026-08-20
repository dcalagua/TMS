package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Destination;
import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.masterdata.domain.LocationType;
import com.ebim.tms.masterdata.domain.Origin;
import com.ebim.tms.masterdata.infrastructure.DestinationRepository;
import com.ebim.tms.masterdata.infrastructure.LocationRepository;
import com.ebim.tms.masterdata.infrastructure.OriginRepository;
import com.ebim.tms.shared.api.ConflictException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The single place that keeps {@code tms.location} and its {@code tms.origin} /
 * {@code tms.destination} compatibility projections consistent, in both directions, inside the
 * caller's transaction. See {@code docs/architecture/ADR_LOCATION_MODEL.md} section 5.
 *
 * <p>Two write paths exist while unification is pending, so exactly one component may know about
 * both. Everything else - {@code LocationService}, {@code OriginService},
 * {@code DestinationService} - calls into this class and knows nothing about the other model.
 *
 * <h2>Downward: canonical to legacy</h2>
 *
 * <p>{@link #synchronize} makes the projections match the location's roles and current field
 * values. Losing a projecting role <em>deactivates</em> the legacy row rather than deleting it:
 * {@code route}, {@code transport_order} and {@code planning_run} may reference it and their
 * {@code ON DELETE RESTRICT} would refuse anyway, so a delete would be a failure disguised as a
 * feature.
 *
 * <h2>Upward: legacy to canonical</h2>
 *
 * <p>{@link #syncFromOrigin} and {@link #syncFromDestination} run after an Origins/Destinations
 * API write. An unlinked legacy row adopts a new canonical location; a linked one updates the
 * one it has. Three rules are deliberate and are the ones worth reviewing:
 *
 * <ul>
 *   <li><b>{@code code} propagates and a collision is a 409.</b> After V14 the two legacy code
 *       namespaces are one canonical namespace, and that tightening is the point. It cannot brick
 *       an existing row: the backfill left {@code location.code} equal to the legacy code, so an
 *       unchanged update re-proposes the code the location already holds.</li>
 *   <li><b>{@code external_reference} propagates only when it is free and the location has no
 *       integration-owned identity.</b> V6/V7 never constrained it, so pre-V14 duplicates exist
 *       and one of them must be allowed to keep being edited; and an identity a real integration
 *       claimed through the Locations API must not be overwritten by a legacy edit.</li>
 *   <li><b>{@code type} propagates only when it is a real change.</b> The legacy type is a
 *       narrowing of the canonical one, so re-widening it unconditionally would turn a
 *       {@code STORE} into a {@code DELIVERY_POINT} on the first unrelated edit.</li>
 * </ul>
 *
 * <p>Fields the legacy shape does not carry - a zone or a service time on an origin, a time zone
 * or the locality fields on a destination - are never touched by an upward sync. They simply are
 * not part of that payload.
 */
@Service
public class LocationCompatibilityProjector {

    /**
     * The external system recorded for an identity that came from a legacy row rather than from a
     * named integration. {@code ck_location_external_pair_complete} requires a system whenever a
     * reference is present, and the honest value for pre-V14 data is where it came from, not an
     * integration name nobody configured.
     */
    static final String LEGACY_EXTERNAL_SYSTEM = "LEGACY";

    /** The country an origin-derived location starts with; {@code tms.origin} has no such column. */
    private static final String DEFAULT_COUNTRY = "PE";

    /**
     * The time zone a destination-derived location starts with. {@code tms.destination} has no
     * such column, and the V14 backfill could reach for the company's zone because it was a
     * one-off transformation of rows that predate the column entirely - doing the same on every
     * ordinary edit would keep producing a guess that reads like data. The operator sets the real
     * value in the Locations screen.
     */
    private static final String DEFAULT_TIME_ZONE = "UTC";

    private final LocationRepository locationRepository;
    private final OriginRepository originRepository;
    private final DestinationRepository destinationRepository;

    public LocationCompatibilityProjector(LocationRepository locationRepository,
            OriginRepository originRepository, DestinationRepository destinationRepository) {
        this.locationRepository = locationRepository;
        this.originRepository = originRepository;
        this.destinationRepository = destinationRepository;
    }

    /** The ids of a location's legacy projections; {@code null} where the role is not held. */
    public record Projections(UUID originId, UUID destinationId) {}

    // -----------------------------------------------------------------------
    // Downward: canonical -> legacy
    // -----------------------------------------------------------------------

    /**
     * Brings the legacy projections in line with {@code location}, which must already be
     * persisted (its id is what the projections link to).
     *
     * @return the ids of the projections that exist after this call
     */
    public Projections synchronize(Location location, UUID actorId) {
        return new Projections(
                synchronizeOrigin(location, actorId),
                synchronizeDestination(location, actorId));
    }

    /** Reads the current projection ids without changing anything, for list and detail views. */
    public Projections projectionsOf(Location location) {
        return new Projections(
                originRepository.findByLocationIdAndCompanyId(location.id(), location.companyId())
                        .map(Origin::id).orElse(null),
                destinationRepository.findByLocationIdAndCompanyId(location.id(), location.companyId())
                        .map(Destination::id).orElse(null));
    }

    /** One batched lookup per page instead of two queries per row. */
    public Map<UUID, Projections> projectionsOf(UUID companyId, Collection<Location> locations) {
        Map<UUID, Projections> byLocationId = new HashMap<>();
        List<UUID> ids = locations.stream().map(Location::id).toList();
        if (ids.isEmpty()) {
            return byLocationId;
        }
        Map<UUID, UUID> origins = new HashMap<>();
        for (Origin origin : originRepository.findByLocationIdInAndCompanyId(ids, companyId)) {
            origins.put(origin.locationId(), origin.id());
        }
        Map<UUID, UUID> destinations = new HashMap<>();
        for (Destination destination : destinationRepository.findByLocationIdInAndCompanyId(ids, companyId)) {
            destinations.put(destination.locationId(), destination.id());
        }
        for (UUID id : ids) {
            byLocationId.put(id, new Projections(origins.get(id), destinations.get(id)));
        }
        return byLocationId;
    }

    private UUID synchronizeOrigin(Location location, UUID actorId) {
        Optional<Origin> existing =
                originRepository.findByLocationIdAndCompanyId(location.id(), location.companyId());

        if (!location.hasRole(LocationRole.ORIGIN)) {
            existing.ifPresent(origin -> {
                if (origin.active()) {
                    origin.deactivate(actorId);
                    originRepository.save(origin);
                }
            });
            return existing.map(Origin::id).orElse(null);
        }

        // A single assignment, because the active-state helper below takes method references
        // and a reassigned local is not effectively final.
        final Origin origin = existing.orElseGet(() -> {
            Origin created = new Origin(location.companyId(), location.code(), location.name(),
                    location.type().toOriginType(), location.address(), location.latitude(),
                    location.longitude(), location.timeZone(), location.externalReference(), actorId);
            created.linkToLocation(location.id());
            return created;
        });
        if (existing.isPresent()) {
            origin.applyChanges(location.code(), location.name(), location.type().toOriginType(),
                    location.address(), location.latitude(), location.longitude(), location.timeZone(),
                    location.externalReference(), actorId);
        }
        applyActive(location.active(), origin.active(),
                () -> origin.activate(actorId), () -> origin.deactivate(actorId));
        return saveOriginOrConflict(origin, location.code()).id();
    }

    private UUID synchronizeDestination(Location location, UUID actorId) {
        Optional<Destination> existing =
                destinationRepository.findByLocationIdAndCompanyId(location.id(), location.companyId());

        if (!location.hasRole(LocationRole.SHIP_TO)) {
            existing.ifPresent(destination -> {
                if (destination.active()) {
                    destination.deactivate(actorId);
                    destinationRepository.save(destination);
                }
            });
            return existing.map(Destination::id).orElse(null);
        }

        final Destination destination = existing.orElseGet(() -> {
            Destination created = new Destination(location.companyId(), location.code(), location.name(),
                    location.type().toDestinationType(), location.address(), location.addressReference(),
                    location.district(), location.province(), location.department(), location.country(),
                    location.latitude(), location.longitude(), location.zoneId(),
                    location.serviceTimeMinutes(), location.externalReference(), actorId);
            created.linkToLocation(location.id());
            return created;
        });
        if (existing.isPresent()) {
            destination.applyChanges(location.code(), location.name(), location.type().toDestinationType(),
                    location.address(), location.addressReference(), location.district(), location.province(),
                    location.department(), location.country(), location.latitude(), location.longitude(),
                    location.zoneId(), location.serviceTimeMinutes(), location.externalReference(), actorId);
        }
        applyActive(location.active(), destination.active(),
                () -> destination.activate(actorId), () -> destination.deactivate(actorId));
        return saveDestinationOrConflict(destination, location.code()).id();
    }

    // -----------------------------------------------------------------------
    // Upward: legacy -> canonical
    // -----------------------------------------------------------------------

    /** Called by {@code OriginService} after every write, inside the same transaction. */
    public void syncFromOrigin(Origin origin, UUID actorId) {
        Location location = linkedLocation(origin.locationId(), origin.companyId());
        if (location == null) {
            Location adopted = new Location(origin.companyId(), origin.code(), origin.name(),
                    LocationType.from(origin.type()), origin.address(), null, null, null, null,
                    DEFAULT_COUNTRY, origin.timeZone(), origin.latitude(), origin.longitude(), null, 0,
                    origin.externalReference() == null ? null : LEGACY_EXTERNAL_SYSTEM,
                    claimableExternalReference(origin.companyId(), null, origin.externalReference()),
                    actorId);
            adopted.replaceRoles(Set.of(LocationRole.ORIGIN));
            origin.linkToLocation(saveLocationOrConflict(adopted, origin.code()).id());
            originRepository.save(origin);
            return;
        }

        requireCodeIsFree(origin.companyId(), origin.code(), location.id(), "origin");
        LocationType type = location.type().toOriginType() == origin.type()
                ? location.type()
                : LocationType.from(origin.type());
        ExternalIdentity identity = propagatedIdentity(location, origin.externalReference());

        location.applyChanges(origin.code(), origin.name(), type, origin.address(),
                location.addressReference(), location.district(), location.province(), location.department(),
                location.country(), origin.timeZone(), origin.latitude(), origin.longitude(),
                location.zoneId(), location.serviceTimeMinutes(), identity.system(), identity.reference(), actorId);
        ensureRole(location, LocationRole.ORIGIN);
        applyActive(origin.active(), location.active(),
                () -> location.activate(actorId), () -> location.deactivate(actorId));
        saveLocationOrConflict(location, origin.code());
    }

    /** Called by {@code DestinationService} after every write, inside the same transaction. */
    public void syncFromDestination(Destination destination, UUID actorId) {
        Location location = linkedLocation(destination.locationId(), destination.companyId());
        if (location == null) {
            Location adopted = new Location(destination.companyId(), destination.code(), destination.name(),
                    LocationType.from(destination.type()), destination.address(), destination.addressReference(),
                    destination.district(), destination.province(), destination.department(),
                    destination.country(), DEFAULT_TIME_ZONE, destination.latitude(),
                    destination.longitude(), destination.zoneId(), destination.serviceTimeMinutes(),
                    destination.externalReference() == null ? null : LEGACY_EXTERNAL_SYSTEM,
                    claimableExternalReference(destination.companyId(), null, destination.externalReference()),
                    actorId);
            adopted.replaceRoles(Set.of(LocationRole.SHIP_TO));
            destination.linkToLocation(saveLocationOrConflict(adopted, destination.code()).id());
            destinationRepository.save(destination);
            return;
        }

        requireCodeIsFree(destination.companyId(), destination.code(), location.id(), "destination");
        LocationType type = location.type().toDestinationType() == destination.type()
                ? location.type()
                : LocationType.from(destination.type());
        ExternalIdentity identity = propagatedIdentity(location, destination.externalReference());

        location.applyChanges(destination.code(), destination.name(), type, destination.address(),
                destination.addressReference(), destination.district(), destination.province(),
                destination.department(), destination.country(), location.timeZone(), destination.latitude(),
                destination.longitude(), destination.zoneId(), destination.serviceTimeMinutes(),
                identity.system(), identity.reference(), actorId);
        ensureRole(location, LocationRole.SHIP_TO);
        applyActive(destination.active(), location.active(),
                () -> location.activate(actorId), () -> location.deactivate(actorId));
        saveLocationOrConflict(location, destination.code());
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /** The external identity pair, kept together so the pair-complete check cannot be half-applied. */
    private record ExternalIdentity(String system, String reference) {}

    private Location linkedLocation(UUID locationId, UUID companyId) {
        return locationId == null
                ? null
                : locationRepository.findByIdAndCompanyId(locationId, companyId).orElse(null);
    }

    /**
     * What the canonical row's external identity becomes after an upward sync.
     *
     * <p>An identity claimed through the Locations API by a named system is left alone: a legacy
     * edit must not be able to overwrite what an integration owns. Otherwise the legacy reference
     * is propagated when it is free, and the canonical value is kept when it is not - see the
     * class comment for why a 409 would be the wrong answer here.
     */
    private ExternalIdentity propagatedIdentity(Location location, String legacyReference) {
        boolean integrationOwned = location.externalSystem() != null
                && !LEGACY_EXTERNAL_SYSTEM.equals(location.externalSystem());
        if (integrationOwned) {
            return new ExternalIdentity(location.externalSystem(), location.externalReference());
        }
        if (legacyReference == null) {
            return new ExternalIdentity(null, null);
        }
        String claimable = claimableExternalReference(location.companyId(), location.id(), legacyReference);
        return claimable == null
                ? new ExternalIdentity(location.externalSystem(), location.externalReference())
                : new ExternalIdentity(LEGACY_EXTERNAL_SYSTEM, claimable);
    }

    /** The reference itself when this company has it free, {@code null} when another location holds it. */
    private String claimableExternalReference(UUID companyId, UUID locationId, String reference) {
        if (reference == null) {
            return null;
        }
        boolean taken = locationId == null
                ? locationRepository.existsByCompanyIdAndExternalSystemAndExternalReference(
                        companyId, LEGACY_EXTERNAL_SYSTEM, reference)
                : locationRepository.existsByCompanyIdAndExternalSystemAndExternalReferenceAndIdNot(
                        companyId, LEGACY_EXTERNAL_SYSTEM, reference, locationId);
        return taken ? null : reference;
    }

    private void requireCodeIsFree(UUID companyId, String code, UUID locationId, String legacyName) {
        if (locationRepository.existsByCompanyIdAndCodeAndIdNot(companyId, code, locationId)) {
            throw new ConflictException("Code '" + code + "' already identifies another location in this company, "
                    + "so this " + legacyName + " cannot take it. Locations and " + legacyName
                    + "s share one code namespace since the canonical Location master was introduced.");
        }
    }

    private static void ensureRole(Location location, LocationRole role) {
        if (!location.hasRole(role)) {
            Set<LocationRole> roles = EnumSet.copyOf(location.roles());
            roles.add(role);
            location.replaceRoles(roles);
        }
    }

    /** Applies a desired active state only when it differs, so an unrelated edit does not restamp the actor. */
    private static void applyActive(boolean desired, boolean current, Runnable activate, Runnable deactivate) {
        if (desired == current) {
            return;
        }
        if (desired) {
            activate.run();
        } else {
            deactivate.run();
        }
    }

    private Location saveLocationOrConflict(Location location, String code) {
        try {
            return locationRepository.saveAndFlush(location);
        } catch (DataIntegrityViolationException raced) {
            throw new ConflictException(
                    "A location with code '" + code + "' already exists in this company.");
        }
    }

    private Origin saveOriginOrConflict(Origin origin, String code) {
        try {
            return originRepository.saveAndFlush(origin);
        } catch (DataIntegrityViolationException raced) {
            throw new ConflictException("An origin with code '" + code + "' already exists in this company, "
                    + "so this location cannot take the ORIGIN role.");
        }
    }

    private Destination saveDestinationOrConflict(Destination destination, String code) {
        try {
            return destinationRepository.saveAndFlush(destination);
        } catch (DataIntegrityViolationException raced) {
            throw new ConflictException("A destination with code '" + code + "' already exists in this company, "
                    + "so this location cannot take the SHIP_TO role.");
        }
    }
}
