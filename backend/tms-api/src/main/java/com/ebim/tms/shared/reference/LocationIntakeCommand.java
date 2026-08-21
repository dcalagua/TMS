package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.util.Set;

/**
 * One location as a sending system describes it.
 *
 * <p>Everything is named the way a partner can actually supply it: {@code type} and
 * {@code roles} are the plain codes rather than {@code masterdata}'s enums, and the zone is a
 * {@code zoneCode} rather than a TMS uuid - an external system knows its own vocabulary and a
 * store code, never our primary keys. Carrying no {@code masterdata} type is also what lets this
 * record live in {@code shared}, which the integration module may depend on and
 * {@code com.ebim.tms.masterdata} is not ({@code ModuleBoundaryTest}).
 *
 * <p><b>Identity.</b> {@code externalSystem} + {@code externalReference} is the sending system's
 * own key and is what makes redelivery idempotent; it is matched first. A payload that carries
 * no external reference falls back to {@code code}, which is the identity a human would use. The
 * two never conflict, because {@code uq_location_external} and {@code uq_location_company_code}
 * both hold: a payload whose reference points at one row and whose code points at another is
 * refused rather than resolved by guessing.
 *
 * @param active {@code null} means "do not change it" on an update, and "active" on a create.
 *     A partner that only syncs attributes should not have to restate the activation state, and
 *     one that closes a store should be able to say so without a second API
 */
public record LocationIntakeCommand(
        String code,
        String name,
        String type,
        Set<String> roles,
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
        Integer serviceTimeMinutes,
        String externalSystem,
        String externalReference,
        Boolean active) {

    public LocationIntakeCommand {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
