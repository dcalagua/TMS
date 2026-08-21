package com.ebim.tms.masterdata.application.imports;

import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.masterdata.domain.LocationType;
import com.ebim.tms.shared.imports.ImportOutcome;
import java.math.BigDecimal;
import java.util.Set;

/** One location's row in an import report, as the operator reviews it. */
public record LocationImportPreview(
        String code,
        ImportOutcome outcome,
        int rowNumber,
        String name,
        LocationType type,
        Set<LocationRole> roles,
        String zoneCode,
        String country,
        String timeZone,
        BigDecimal latitude,
        BigDecimal longitude,
        int serviceTimeMinutes,
        String externalSystem,
        String externalReference) {

    static LocationImportPreview from(LocationImportCandidate candidate) {
        return new LocationImportPreview(candidate.code(), candidate.outcome(), candidate.rowNumber(),
                candidate.name(), candidate.type(), candidate.roles(), candidate.zoneCode(), candidate.country(),
                candidate.timeZone(), candidate.latitude(), candidate.longitude(), candidate.serviceTimeMinutes(),
                candidate.externalSystem(), candidate.externalReference());
    }
}
