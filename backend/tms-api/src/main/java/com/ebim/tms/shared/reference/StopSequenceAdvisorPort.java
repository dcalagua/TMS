package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Proposes the order in which a vehicle visits a set of stops (ADR-030). The route master's
 * "Optimizar paradas" asks it; {@code ai.infrastructure.AnthropicStopSequenceAdvisor} answers it.
 *
 * <p><b>Advice, never a decision.</b> The answer is shown to a planner next to the order they
 * already have, with both distances, and nothing changes until they apply it and save the route.
 * The caller validates the answer - every stop exactly once - before showing it, because a model
 * can be wrong in ways a compiler cannot catch.
 *
 * <p><b>Only geometry crosses the boundary.</b> The question carries coordinates and distances,
 * never a location's name, code, address, customer or company: an external service learns where
 * some points are and nothing about whose they are (ADR-030 section 3).
 */
public interface StopSequenceAdvisorPort {

    /** Whether this deployment can answer at all - false when no API key is configured. */
    boolean isAvailable();

    /**
     * The proposed visiting order.
     *
     * @throws com.ebim.tms.shared.api.FeatureNotConfiguredException when {@link #isAvailable()} is false
     * @throws com.ebim.tms.shared.api.ProblemTypedException {@code ai-unavailable} when the provider
     *     failed, timed out or gave no usable answer
     */
    StopSequenceAdvice advise(StopSequenceQuestion question);

    /**
     * Where the vehicle starts and the stops it must visit, in the order the planner has now.
     *
     * @param distanceKm a square matrix over the origin and the stops: index 0 is the origin and
     *     index {@code i} is {@code stops.get(i - 1)}; row is "from", column is "to". Road
     *     estimates, so it may be asymmetric. An entry is null where the leg could not be measured.
     */
    record StopSequenceQuestion(GeoPoint origin, List<GeoPoint> stops, List<List<BigDecimal>> distanceKm) {

        public StopSequenceQuestion {
            stops = List.copyOf(stops);
            // Copied by hand: List.copyOf refuses the null of an unmeasured leg.
            List<List<BigDecimal>> rows = new ArrayList<>(distanceKm.size());
            for (List<BigDecimal> row : distanceKm) {
                rows.add(Collections.unmodifiableList(new ArrayList<>(row)));
            }
            distanceKm = Collections.unmodifiableList(rows);
            if (rows.size() != stops.size() + 1 || rows.stream().anyMatch(row -> row.size() != rows.size())) {
                throw new IllegalArgumentException("the distance matrix must be square over the origin and every stop");
            }
        }
    }

    /**
     * @param sequence  the stops in the proposed visiting order, as 1-based positions in the
     *                  question's {@code stops} - unvalidated: the caller checks it is a permutation
     * @param rationale why this order, in Spanish, for the planner to read; may be blank
     * @param model     the model that answered, for the planner and the logs
     */
    record StopSequenceAdvice(List<Integer> sequence, String rationale, String model) {

        public StopSequenceAdvice {
            sequence = sequence == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sequence));
        }
    }
}
