package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Origin;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OriginLookupPort;
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
 * The only implementation of {@link OriginLookupPort}: translates between {@code masterdata}'s
 * own {@link Origin}/{@link OriginRepository} and the module-agnostic {@link MasterReference}
 * shape other business modules (starting with {@code orders}) are allowed to depend on. See
 * {@link OriginLookupPort}'s class comment for why this indirection exists.
 */
@Component
class OriginLookupAdapter implements OriginLookupPort {

    private final OriginRepository originRepository;

    OriginLookupAdapter(OriginRepository originRepository) {
        this.originRepository = originRepository;
    }

    @Override
    public Optional<MasterReference> findActiveInCompany(UUID id, UUID companyId) {
        return originRepository.findByIdAndCompanyId(id, companyId).filter(Origin::active).map(OriginLookupAdapter::toReference);
    }

    @Override
    public Map<UUID, MasterReference> findAllInCompany(Set<UUID> ids, UUID companyId) {
        Map<UUID, MasterReference> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        for (Origin origin : originRepository.findByIdInAndCompanyId(ids, companyId)) {
            byId.put(origin.id(), toReference(origin));
        }
        return byId;
    }

    @Override
    public Map<String, MasterReference> findActiveByCodesInCompany(Collection<String> codes, UUID companyId) {
        Map<String, MasterReference> byCode = new HashMap<>();
        // Stored codes are upper-cased and trimmed (ck_origin_code_normalized), so normalising the
        // caller's input the same way turns "case-insensitive lookup" into an exact IN match the
        // company/code index can serve, instead of a lower() scan over the whole company.
        Set<String> normalized = codes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (normalized.isEmpty()) {
            return byCode;
        }
        for (Origin row : originRepository.findByCompanyIdAndCodeIn(companyId, normalized)) {
            if (row.active()) {
                byCode.put(row.code(), toReference(row));
            }
        }
        return byCode;
    }

    private static MasterReference toReference(Origin origin) {
        return new MasterReference(origin.id(), origin.code(), origin.name(),
                origin.latitude(), origin.longitude());
    }
}
