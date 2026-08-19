package com.ebim.tms.planning.application;

import com.ebim.tms.shared.api.ConflictException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The one place TMS decides whether a load fits a vehicle and what percentage of it is used.
 *
 * <p>Three rules the rest of the module depends on:
 *
 * <ol>
 *   <li><b>The frontend is never trusted.</b> Nothing here takes a client-supplied used-quantity;
 *       every number arrives from {@code TripOrderAssignmentRepository}'s grouped sum over active
 *       assignment rows, and every limit from {@code fleet} through
 *       {@code VehicleLookupPort}. A browser can render a bar; it cannot decide a truck is full.</li>
 *   <li><b>Rejection is transactional.</b> {@link #requireWithinCapacity} throws before the
 *       assignment is written, inside the same transaction and while the caller holds the trip's
 *       row lock, so a refusal leaves nothing behind.</li>
 *   <li><b>Null and zero limits are different.</b> Null is unlimited, zero is a real zero - see
 *       {@link CapacityLimits} and {@code docs/domain/CAPACITY_MODEL.md}. No path here divides by
 *       a limit without checking it first.</li>
 * </ol>
 *
 * <p>Stateless and repository-free by design, exactly like {@code fleet}'s
 * {@code EffectiveCapacityResolver}: callers already hold the load and the limits, so this stays
 * a pure function that a unit test can exercise without a database.
 */
@Service
public class PlanningCapacityService {

    /** One decimal place: enough for a progress bar, not enough to imply false precision. */
    private static final int PERCENT_SCALE = 1;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public TripCapacityView summarize(UUID tripId, CapacitySource source, CapacityLimits limits, CapacityLoad load) {
        CapacityDimension weight = dimension(load.weightKg(), limits.maxWeightKg());
        CapacityDimension volume = dimension(load.volumeM3(), limits.maxVolumeM3());
        CapacityDimension pallets = dimension(load.pallets(), limits.maxPallets());
        boolean within = !weight.exceeded() && !volume.exceeded() && !pallets.exceeded();
        return new TripCapacityView(tripId, source, load.orderCount(), weight, volume, pallets, within);
    }

    /**
     * Refuses a load that does not fit, naming every dimension that failed rather than only the
     * first - a planner who is over on both weight and pallets needs to know both before they
     * choose a different vehicle.
     *
     * @param what a caller-facing description of what is being attempted, e.g.
     *             {@code "Order TO-00000007 does not fit trip 2"}
     */
    public void requireWithinCapacity(String what, CapacityLimits limits, CapacityLoad load) {
        List<String> failures = new ArrayList<>();
        addFailure(failures, "weight", load.weightKg(), limits.maxWeightKg(), "kg");
        addFailure(failures, "volume", load.volumeM3(), limits.maxVolumeM3(), "m3");
        addFailure(failures, "pallets", load.pallets(), limits.maxPallets(), "pallets");
        if (!failures.isEmpty()) {
            throw new ConflictException(what + ": " + String.join("; ", failures) + ".");
        }
    }

    private static void addFailure(List<String> failures, String dimension, BigDecimal used, BigDecimal limit,
            String unit) {
        if (limit != null && used.compareTo(limit) > 0) {
            failures.add(dimension + " " + used.stripTrailingZeros().toPlainString() + " " + unit
                    + " exceeds the capacity of " + limit.stripTrailingZeros().toPlainString() + " " + unit);
        }
    }

    private static CapacityDimension dimension(BigDecimal used, BigDecimal limit) {
        if (limit == null) {
            return new CapacityDimension(used, null, null, null, false, true);
        }
        boolean exceeded = used.compareTo(limit) > 0;
        // A zero limit is a real limit with no meaningful percentage: reported as null rather
        // than as 0% or as a division by zero.
        BigDecimal percent = limit.signum() == 0
                ? null
                : used.multiply(HUNDRED).divide(limit, PERCENT_SCALE, RoundingMode.HALF_UP);
        return new CapacityDimension(used, limit, limit.subtract(used), percent, exceeded, false);
    }
}
