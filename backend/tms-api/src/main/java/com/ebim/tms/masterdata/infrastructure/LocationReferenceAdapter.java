package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.shared.reference.MasterReference;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resolves canonical {@link Location}s into the module-agnostic {@link MasterReference} shape,
 * parameterised by the {@link LocationRole} the caller needs the place to hold.
 *
 * <p>This is the whole body of what used to be two classes. Before V23,
 * {@code OriginLookupAdapter} read {@code tms.origin} and {@code DestinationLookupAdapter} read
 * {@code tms.destination}; now there is one table and the two differ by a single enum value, so
 * the logic lives here once and {@link OriginLookupAdapter} / {@link DestinationLookupAdapter}
 * are the two thin ports over it. They stay separate types because their interfaces declare the
 * same three method names - and because merging the ports would mean putting {@link LocationRole},
 * a {@code masterdata.domain} type, into {@code shared}, which is exactly the dependency the
 * module boundary exists to prevent.
 *
 * <p>The asymmetry between the two kinds of lookup is deliberate and is this class's tenancy and
 * eligibility contract:
 *
 * <ul>
 *   <li><b>Assignment</b> ({@link #usableAs}, {@link #usableAsByCodes}) filters by company, by
 *       {@code active} and by role. A location that may not ship must not be assignable as an
 *       origin, and one belonging to another company must not resolve at all.</li>
 *   <li><b>Display</b> ({@link #referencedBy}) filters by company only. An order already points
 *       where it points; if that location has since been deactivated or has had a role removed,
 *       the order still has to render the place it was actually sent to.</li>
 * </ul>
 */
@Component
class LocationReferenceAdapter {

    private final LocationRepository locationRepository;

    LocationReferenceAdapter(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    /** One active location of this company that holds {@code role}, for a new assignment. */
    Optional<MasterReference> usableAs(UUID id, UUID companyId, LocationRole role) {
        return locationRepository.findUsableAs(id, companyId, role).map(LocationReferenceAdapter::toReference);
    }

    /** Already-persisted references, resolved for display: company-scoped, role-blind, state-blind. */
    Map<UUID, MasterReference> referencedBy(Set<UUID> ids, UUID companyId) {
        Map<UUID, MasterReference> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        for (Location location : locationRepository.findByIdInAndCompanyId(ids, companyId)) {
            byId.put(location.id(), toReference(location));
        }
        return byId;
    }

    /** {@link #usableAs} for a caller that has codes rather than ids - the bulk order import. */
    Map<String, MasterReference> usableAsByCodes(Collection<String> codes, UUID companyId, LocationRole role) {
        Map<String, MasterReference> byCode = new HashMap<>();
        // Stored codes are upper-cased and trimmed (ck_location_code_normalized), so normalising
        // the caller's input the same way turns "case-insensitive lookup" into an exact IN match
        // the (company, code) index can serve, instead of a lower() scan over the whole company.
        Set<String> normalized = codes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (normalized.isEmpty()) {
            return byCode;
        }
        for (Location location : locationRepository.findUsableAsByCodes(normalized, companyId, role)) {
            byCode.put(location.code(), toReference(location));
        }
        return byCode;
    }

    private static MasterReference toReference(Location location) {
        return new MasterReference(location.id(), location.code(), location.name(),
                location.latitude(), location.longitude(), location.address());
    }
}
