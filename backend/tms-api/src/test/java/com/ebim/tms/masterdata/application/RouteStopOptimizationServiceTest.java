package com.ebim.tms.masterdata.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.api.ProblemTypedException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The two pure rules behind "Optimizar paradas" (ADR-030): what a usable answer is, and how a path is measured. */
class RouteStopOptimizationServiceTest {

    @Test
    @DisplayName("a usable answer names every stop exactly once")
    void permutationIsAccepted() {
        assertThat(RouteStopOptimizationService.validated(List.of(3, 1, 2), 3)).containsExactly(3, 1, 2);
    }

    @Test
    @DisplayName("a repeated, missing, extra, out-of-range or null stop makes the answer unusable")
    void anythingElseIsAiUnavailable() {
        for (List<Integer> answer : List.of(List.of(1, 1, 2), List.of(1, 2), List.of(1, 2, 3, 4), List.of(0, 1, 2),
                List.of(1, 2, 4), Arrays.asList(1, null, 2))) {
            assertThatThrownBy(() -> RouteStopOptimizationService.validated(answer, 3))
                    .isInstanceOfSatisfying(ProblemTypedException.class,
                            failure -> assertThat(failure.type()).isEqualTo(ProblemType.AI_UNAVAILABLE));
        }
    }

    @Test
    @DisplayName("a path starts at the origin, follows the order given, and does not drive back")
    void pathIsMeasuredFromTheOrigin() {
        var matrix = new RouteStopOptimizationService.Matrix(List.of(
                List.of(km("0"), km("10"), km("4")),
                List.of(km("10"), km("0"), km("6")),
                List.of(km("4"), km("6.5"), km("0"))), true);

        assertThat(matrix.pathKm(List.of(1, 2))).isEqualByComparingTo("16.00");
        // Asymmetric on purpose: 2 -> 1 is 6.5, not the 6 of 1 -> 2.
        assertThat(matrix.pathKm(List.of(2, 1))).isEqualByComparingTo("10.50");
    }

    private static BigDecimal km(String value) {
        return new BigDecimal(value);
    }
}
