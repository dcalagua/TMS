package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.MaterialProfile;
import com.ebim.tms.shared.geometry.PhysicalBlock;
import com.ebim.tms.shared.geometry.StackMode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A material profile as the Masters screen shows it: what the warehouse stated, who stated it
 * ({@code sourceSystem}), when the warehouse changed it ({@code sourceUpdatedAt}) and when TMS last
 * received it ({@code updatedAt}).
 */
public record MaterialProfileView(
        UUID id,
        String materialCode,
        String description,
        String sourceSystem,
        OffsetDateTime sourceUpdatedAt,
        BigDecimal lengthM,
        BigDecimal widthM,
        BigDecimal heightM,
        BigDecimal unitWeightKg,
        BigDecimal unitVolumeM3,
        StackMode stackMode,
        Integer stackMaxQuantity,
        BigDecimal nestIncrementM,
        BigDecimal maxTopLoadKg,
        boolean orientationLocked,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static MaterialProfileView from(MaterialProfile profile) {
        PhysicalBlock block = profile.block();
        return new MaterialProfileView(profile.id(), profile.materialCode(), profile.description(),
                profile.sourceSystem(), profile.sourceUpdatedAt(), block.lengthM(), block.widthM(), block.heightM(),
                profile.unitWeightKg(), profile.unitVolumeM3(), block.stackMode(), block.stackMaxQuantity(),
                block.nestIncrementM(), block.maxTopLoadKg(), block.orientationLocked(), profile.version(),
                profile.createdAt(), profile.updatedAt());
    }
}
