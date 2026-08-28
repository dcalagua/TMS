package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.shared.reference.LocationTimeZonePort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The zone a place keeps (migration V41), answered by the module that owns {@code tms.location}.
 *
 * <p>The company predicate is in the query and not applied to the result: a location of another
 * tenant must never be loaded at all, which is the rule every finder in this module follows.
 */
@Component
class LocationTimeZoneAdapter implements LocationTimeZonePort {

    private final LocationRepository locationRepository;

    LocationTimeZoneAdapter(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findTimeZone(UUID locationId, UUID companyId) {
        return locationRepository.findByIdAndCompanyId(locationId, companyId)
                .map(Location::timeZone)
                .filter(zone -> zone != null && !zone.isBlank());
    }
}
