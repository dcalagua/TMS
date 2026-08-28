package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.shared.reference.StopServicePort;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * How long a place takes to serve (migration V43), answered by the module that owns
 * {@code tms.location}.
 *
 * <p>The company predicate is in the query and not applied to the result: a location of another
 * tenant must never be loaded at all, which is the rule every finder in this module follows.
 */
@Component
class StopServiceAdapter implements StopServicePort {

    private final LocationRepository locationRepository;

    StopServiceAdapter(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> findServiceMinutes(Collection<UUID> locationIds, UUID companyId) {
        if (locationIds.isEmpty()) {
            return Map.of();
        }
        return locationRepository.findByIdInAndCompanyId(locationIds, companyId).stream()
                .collect(Collectors.toMap(Location::id, Location::serviceTimeMinutes));
    }
}
