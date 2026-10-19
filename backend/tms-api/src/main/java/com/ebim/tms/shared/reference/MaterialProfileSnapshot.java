package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.geometry.PhysicalBlock;
import java.math.BigDecimal;

/**
 * What an order line copies from a material's warehouse profile (ADR-022 section 2), read through
 * {@link MaterialProfileLookupPort}.
 *
 * @param version the profile's version at the moment of the copy, stored on the line so a later
 *     reader can tell which copy it is looking at
 * @param block the physical block, or {@code null} when the profile declares none (a profile may
 *     carry only a unit weight)
 */
public record MaterialProfileSnapshot(
        String materialCode,
        long version,
        BigDecimal unitWeightKg,
        BigDecimal unitVolumeM3,
        PhysicalBlock block) {
}
