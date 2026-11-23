package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.masterdata.infrastructure.LocationRepository;
import com.ebim.tms.shared.api.FeatureNotConfiguredException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.api.ProblemTypedException;
import com.ebim.tms.shared.reference.GeoPoint;
import com.ebim.tms.shared.reference.RoutingPort;
import com.ebim.tms.shared.reference.RoutingSource;
import com.ebim.tms.shared.reference.StopSequenceAdvisorPort;
import com.ebim.tms.shared.reference.StopSequenceAdvisorPort.StopSequenceAdvice;
import com.ebim.tms.shared.reference.StopSequenceAdvisorPort.StopSequenceQuestion;
import com.ebim.tms.shared.reference.TravelEstimate;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Optimizar paradas" on the route master (ADR-030): asks the AI assistant for a visiting order and
 * measures it against the planner's own before showing it.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> The model may think for tens of seconds, and a
 * database connection held open for that long - by every planner who presses the button - is a
 * pool exhausted for a feature that writes nothing. The locations are read in one short read-only
 * transaction and the distances in {@code RoutingPort}'s own; the tenant is bound per connection
 * ({@code TenantScopedDataSource}), so RLS applies to both.
 *
 * <p><b>The model's answer is checked, not trusted.</b> It must name every stop exactly once, or it
 * is reported as {@code ai-unavailable}: a sequence that drops or repeats a stop would, applied,
 * silently change which places the route serves.
 */
@Service
public class RouteStopOptimizationService {

    /** A route master with more stops than this is not a corridor a model should be sequencing in one go. */
    public static final int MAX_STOPS = 40;

    private final LocationRepository locations;
    private final RoutingPort routingPort;
    private final StopSequenceAdvisorPort advisor;
    private final TransactionTemplate readOnly;

    public RouteStopOptimizationService(LocationRepository locations, RoutingPort routingPort,
            StopSequenceAdvisorPort advisor, PlatformTransactionManager transactionManager) {
        this.locations = locations;
        this.routingPort = routingPort;
        this.advisor = advisor;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    public RouteStopOptimizationView optimize(CompanyScope scope, RouteStopOptimizationRequest request) {
        // First, so an unconfigured deployment answers 503 without computing a matrix for nothing.
        if (!advisor.isAvailable()) {
            throw new FeatureNotConfiguredException(
                    "The AI assistant is not configured: set tms.ai.anthropic.api-key (ANTHROPIC_API_KEY).");
        }
        List<UUID> stopIds = request.destinationIds();
        if (new HashSet<>(stopIds).size() != stopIds.size()) {
            throw new InvalidRequestException("A destination appears more than once in the stops.");
        }

        List<GeoPoint> points = readOnly.execute(status -> places(scope, request));
        int stopCount = stopIds.size();

        Matrix matrix = matrix(scope.companyId(), points);
        StopSequenceAdvice advice = advisor.advise(
                new StopSequenceQuestion(points.get(0), points.subList(1, points.size()), matrix.distanceKm()));
        List<Integer> sequence = validated(advice.sequence(), stopCount);

        List<Integer> current = new ArrayList<>(stopCount);
        for (int number = 1; number <= stopCount; number++) {
            current.add(number);
        }
        BigDecimal currentKm = matrix.pathKm(current);
        BigDecimal proposedKm = matrix.pathKm(sequence);
        return new RouteStopOptimizationView(
                sequence.stream().map(number -> stopIds.get(number - 1)).toList(),
                currentKm, proposedKm, proposedKm.compareTo(currentKm) < 0, matrix.estimated(),
                advice.rationale() == null ? "" : advice.rationale().trim(), advice.model());
    }

    /**
     * The origin and the stops as points, origin first - in one short read-only transaction, because
     * a location's roles load lazily. Everything after it, the AI call above all, runs without one.
     */
    private List<GeoPoint> places(CompanyScope scope, RouteStopOptimizationRequest request) {
        List<UUID> stopIds = request.destinationIds();
        Set<UUID> wanted = new LinkedHashSet<>(stopIds);
        wanted.add(request.originId());
        Map<UUID, Location> byId = locations.findByIdInAndCompanyId(wanted, scope.companyId()).stream()
                .collect(Collectors.toMap(Location::id, Function.identity()));

        Location origin = byId.get(request.originId());
        if (origin == null || !origin.hasRole(LocationRole.ORIGIN)) {
            throw new InvalidRequestException("The origin is not an origin of this company.");
        }
        List<Location> stops = new ArrayList<>(stopIds.size());
        for (UUID stopId : stopIds) {
            Location stop = byId.get(stopId);
            if (stop == null || !stop.hasRole(LocationRole.DESTINATION)) {
                throw new InvalidRequestException("A stop is not a destination of this company.");
            }
            stops.add(stop);
        }

        List<Location> all = new ArrayList<>(stops.size() + 1);
        all.add(origin);
        all.addAll(stops);
        List<String> withoutCoordinates = all.stream()
                .filter(location -> GeoPoint.of(location.latitude(), location.longitude()) == null)
                .map(Location::code)
                .toList();
        if (!withoutCoordinates.isEmpty()) {
            throw new ProblemTypedException(ProblemType.MISSING_COORDINATES,
                    "Locations without coordinates: " + String.join(", ", withoutCoordinates) + ".");
        }
        return all.stream().map(location -> GeoPoint.of(location.latitude(), location.longitude())).toList();
    }

    /** Every stop number 1..n exactly once, or the answer is unusable. */
    static List<Integer> validated(List<Integer> sequence, int stops) {
        boolean permutation = sequence.size() == stops
                && sequence.stream().allMatch(number -> number != null && number >= 1 && number <= stops)
                && new HashSet<>(sequence).size() == stops;
        if (!permutation) {
            throw new ProblemTypedException(ProblemType.AI_UNAVAILABLE,
                    "The assistant's sequence does not name every stop exactly once.");
        }
        return sequence;
    }

    /**
     * The N x N over the origin and the stops, from {@code RoutingPort} - the distances the rest of
     * TMS charges a trip with. A leg it could not answer falls back to the great-circle distance, so
     * the two totals are always comparable; that leg makes the result an estimate.
     */
    private Matrix matrix(UUID companyId, List<GeoPoint> points) {
        Map<RoutingPort.Leg, TravelEstimate> legs = routingPort.matrix(companyId, points, points);
        boolean estimated = false;
        List<List<BigDecimal>> rows = new ArrayList<>(points.size());
        for (GeoPoint from : points) {
            List<BigDecimal> row = new ArrayList<>(points.size());
            for (GeoPoint to : points) {
                if (from.equals(to)) {
                    row.add(BigDecimal.ZERO);
                    continue;
                }
                TravelEstimate estimate = legs.get(new RoutingPort.Leg(from, to));
                if (estimate == null) {
                    estimated = true;
                    row.add(from.greatCircleKmTo(to));
                } else {
                    estimated |= estimate.source() == RoutingSource.FALLBACK;
                    row.add(estimate.distanceKm());
                }
            }
            rows.add(row);
        }
        return new Matrix(rows, estimated);
    }

    /** Index 0 is the origin and index {@code n} is stop {@code n}, as in {@link StopSequenceQuestion}. */
    record Matrix(List<List<BigDecimal>> distanceKm, boolean estimated) {

        /** Origin, then the stops in this order; the drive back is not counted. */
        BigDecimal pathKm(List<Integer> sequence) {
            BigDecimal total = BigDecimal.ZERO;
            int from = 0;
            for (int to : sequence) {
                total = total.add(distanceKm.get(from).get(to));
                from = to;
            }
            return total.setScale(2, RoundingMode.HALF_UP);
        }
    }
}
