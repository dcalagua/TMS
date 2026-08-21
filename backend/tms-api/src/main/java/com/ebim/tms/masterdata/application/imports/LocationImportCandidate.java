package com.ebim.tms.masterdata.application.imports;

import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.masterdata.domain.LocationType;
import com.ebim.tms.shared.imports.ImportOutcome;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * One location as a validated file describes it, resolved into exactly the arguments {@code
 * LocationService}'s domain object takes. Exists whether or not it will be written -
 * {@link #outcome()} says which - so the dry-run preview and the apply pass are built from the
 * same object and cannot disagree about what a row meant. Mirrors {@code OrderImportCandidate}.
 */
public record LocationImportCandidate(
        String code,
        ImportOutcome outcome,
        int rowNumber,
        String name,
        LocationType type,
        Set<LocationRole> roles,
        String address,
        String addressReference,
        String district,
        String province,
        String department,
        String country,
        String timeZone,
        BigDecimal latitude,
        BigDecimal longitude,
        String zoneCode,
        /** {@code null} when the row named no zone; also {@code null} on a REJECTED candidate if the code did not resolve. */
        UUID zoneId,
        int serviceTimeMinutes,
        String externalSystem,
        String externalReference) {

    public LocationImportCandidate {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean isCreatable() {
        return outcome == ImportOutcome.CREATE;
    }
}
