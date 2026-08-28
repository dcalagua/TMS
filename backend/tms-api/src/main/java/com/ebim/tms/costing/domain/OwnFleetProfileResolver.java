package com.ebim.tms.costing.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Which profile governs a given truck on a given day (V48, JOB 22).
 *
 * <p>A pure function over the candidates, so precedence and effective dating are provable without a
 * database - and so the rule lives in one place rather than being spelled out in an {@code ORDER BY}
 * that a future query could quietly reorder.
 *
 * <h2>Precedence</h2>
 *
 * <pre>
 *   this specific vehicle  &gt;  this vehicle's type  &gt;  no cost available
 * </pre>
 *
 * <p><b>There is deliberately no company-wide fallback.</b> A rate that applied to every truck a
 * company owns regardless of type would be a number that means nothing - a van and an articulated
 * truck do not share a fuel rate, and averaging them would produce an estimate that is wrong for
 * both while looking authoritative. Two levels, and then honestly no cost, which the caller reports
 * as such rather than as zero.
 *
 * <h2>Why ties cannot happen here</h2>
 *
 * Two active profiles covering one target on one day are refused by the database
 * ({@code ex_own_fleet_profile_vehicle_no_overlap}), so this resolver never has to break a tie with
 * a rule nobody chose. It still asserts rather than picking one, because a tie reaching this point
 * would mean the constraint had been dropped and silently choosing would hide that.
 */
public final class OwnFleetProfileResolver {

    private OwnFleetProfileResolver() {
    }

    public static Optional<OwnFleetCostProfile> resolve(
            List<OwnFleetCostProfile> candidates, UUID vehicleId, UUID vehicleTypeId, LocalDate on) {

        if (candidates == null || candidates.isEmpty() || on == null) {
            return Optional.empty();
        }
        List<OwnFleetCostProfile> live = candidates.stream()
                .filter(OwnFleetCostProfile::isUsable)
                .filter(profile -> profile.coversDate(on))
                .toList();

        Optional<OwnFleetCostProfile> specific = single(live.stream()
                .filter(profile -> vehicleId != null && vehicleId.equals(profile.vehicleId()))
                .toList(), "vehicle");
        if (specific.isPresent()) {
            return specific;
        }
        return single(live.stream()
                .filter(profile -> vehicleTypeId != null && vehicleTypeId.equals(profile.vehicleTypeId()))
                .toList(), "vehicle type");
    }

    private static Optional<OwnFleetCostProfile> single(List<OwnFleetCostProfile> matches, String level) {
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("two active cost profiles overlap for one " + level
                    + " on one day, which the database is supposed to make impossible");
        }
        return Optional.of(matches.getFirst());
    }
}
