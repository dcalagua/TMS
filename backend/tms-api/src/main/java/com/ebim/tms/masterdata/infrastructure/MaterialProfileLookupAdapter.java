package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.MaterialProfile;
import com.ebim.tms.shared.geometry.PhysicalBlock;
import com.ebim.tms.shared.reference.MaterialProfileLookupPort;
import com.ebim.tms.shared.reference.MaterialProfileSnapshot;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@code masterdata}'s side of {@link MaterialProfileLookupPort} (ADR-022 section 1). */
@Component
public class MaterialProfileLookupAdapter implements MaterialProfileLookupPort {

    private final MaterialProfileRepository repository;

    public MaterialProfileLookupAdapter(MaterialProfileRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, MaterialProfileSnapshot> byCodes(UUID companyId, Set<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        return repository.findByCompanyIdAndMaterialCodeIn(companyId, codes).stream()
                .map(MaterialProfileLookupAdapter::snapshot)
                .collect(Collectors.toUnmodifiableMap(MaterialProfileSnapshot::materialCode, Function.identity()));
    }

    private static MaterialProfileSnapshot snapshot(MaterialProfile profile) {
        PhysicalBlock block = profile.block();
        return new MaterialProfileSnapshot(profile.materialCode(), profile.version(), profile.unitWeightKg(),
                profile.unitVolumeM3(), block.isEmpty() ? null : block);
    }
}
