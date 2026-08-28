package com.ebim.tms.routing.application;

import com.ebim.tms.routing.domain.GeodesicDistance;
import com.ebim.tms.routing.domain.RoutingProviderAdapter;
import com.ebim.tms.shared.reference.GeoPoint;
import com.ebim.tms.shared.reference.RoutingSource;
import com.ebim.tms.shared.reference.TravelEstimate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The routing provider that always answers (migration V38).
 *
 * <p>Estimates a road from the coordinates alone: great-circle distance stretched by
 * {@link GeodesicDistance#ROAD_FACTOR}, divided by a speed chosen from the distance itself. It is
 * the last link in {@code RoutingService}'s chain and the only one that ships, so with no vendor
 * configured every distance in the product comes from here.
 *
 * <p><b>It is an estimate and it says so.</b> Every answer carries
 * {@link RoutingSource#FALLBACK}, which travels through the cache, onto the plan and into the cost
 * breakdown. Nothing downstream can mistake it for a measured road, and "how much of tonight's plan
 * rests on straight lines" is answerable by counting.
 *
 * <p><b>Two speed bands, not one.</b> A 3 km city delivery and a 300 km line-haul do not average
 * the same speed, and assuming they do makes every urban round trip look about an hour shorter than
 * it is - which would then be planned as if it fitted in a shift. Two bands is the least detail
 * that avoids that lie; more bands would imply knowledge of road classes this has no way to obtain.
 *
 * <p>Deterministic apart from {@link #estimate}'s timestamp, which comes from an injected
 * {@link Clock} so a test can pin it.
 */
@Component
public class LocalGeodesicRoutingProvider implements RoutingProviderAdapter {

    /** Recorded on every estimate. Deliberately versioned: changing the constants changes the name. */
    public static final String NAME = "LOCAL_GEODESIC_V1";

    private final RoutingProperties properties;
    private final Clock clock;

    public LocalGeodesicRoutingProvider(RoutingProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Always. It needs nothing but the two points, which is the whole reason it is the fallback. */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Optional<TravelEstimate> estimate(GeoPoint origin, GeoPoint destination) {
        BigDecimal roadKm = GeodesicDistance.estimatedRoadKm(origin, destination);
        return Optional.of(TravelEstimate.computed(roadKm, durationFor(roadKm), NAME, RoutingSource.FALLBACK,
                OffsetDateTime.now(clock)));
    }

    /**
     * Driving time for a distance, at the band's speed, rounded up to the whole minute.
     *
     * <p>Up, not to nearest: a schedule built from times rounded down is a schedule that is
     * systematically optimistic, and the direction of that error is the one that makes a driver
     * late. Zero distance is zero time and skips the arithmetic - a stop is not a minute away from
     * itself.
     */
    Duration durationFor(BigDecimal roadKm) {
        if (roadKm.signum() == 0) {
            return Duration.ZERO;
        }
        int speedKph = roadKm.compareTo(BigDecimal.valueOf(properties.urbanThresholdKm())) <= 0
                ? properties.urbanSpeedKph()
                : properties.averageSpeedKph();
        BigDecimal minutes = roadKm
                .multiply(BigDecimal.valueOf(60))
                .divide(BigDecimal.valueOf(speedKph), 0, RoundingMode.CEILING);
        return Duration.ofMinutes(minutes.longValueExact());
    }
}
