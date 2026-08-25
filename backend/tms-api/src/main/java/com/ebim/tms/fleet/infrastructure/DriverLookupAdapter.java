package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.Driver;
import com.ebim.tms.shared.reference.DriverLookupPort;
import com.ebim.tms.shared.reference.DriverReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only implementation of {@link DriverLookupPort}: a repository translation, so it lives here
 * next to {@code CarrierLookupAdapter} rather than in {@code application}.
 *
 * <p>{@link #findAssignable} filters {@code active} in Java and not in the query on purpose. The
 * lookup is by primary key inside one company, so the row is fetched either way; deciding on it
 * afterwards keeps the "which conditions make a driver assignable" list in one readable place
 * instead of encoding half of it in a derived query name.
 */
@Component
class DriverLookupAdapter implements DriverLookupPort {

    private final DriverRepository driverRepository;

    DriverLookupAdapter(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    @Override
    public Optional<DriverReference> findAssignable(UUID driverId, UUID companyId) {
        return driverRepository.findByIdAndCompanyId(driverId, companyId)
                .filter(Driver::active)
                .map(DriverLookupAdapter::toReference);
    }

    @Override
    public Map<UUID, DriverReference> findAllInCompany(Set<UUID> ids, UUID companyId) {
        Map<UUID, DriverReference> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        for (Driver driver : driverRepository.findByIdInAndCompanyId(ids, companyId)) {
            byId.put(driver.id(), toReference(driver));
        }
        return byId;
    }

    private static DriverReference toReference(Driver driver) {
        return new DriverReference(driver.id(), driver.code(), driver.fullName(), driver.documentType(),
                driver.documentNumber(), driver.phone(), driver.licenseNumber(), driver.licenseCategory(),
                driver.licenseExpiresOn(), driver.carrierId(), driver.active());
    }
}
