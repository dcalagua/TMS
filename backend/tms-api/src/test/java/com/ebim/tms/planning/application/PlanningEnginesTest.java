package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.api.InvalidRequestException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which engine a run gets, and what happens when somebody asks for one that does not exist. */
class PlanningEnginesTest {

    private final PlanningEngines engines =
            new PlanningEngines(List.of(new HeuristicPlanningEngine(), new PlanningEngineV2()));

    @Test
    @DisplayName("no engine named means HEURISTIC_V1, so an existing caller's proposals do not change")
    void defaultsToV1() {
        assertThat(engines.select(null).name()).isEqualTo("HEURISTIC_V1");
        assertThat(engines.select("  ").name()).isEqualTo("HEURISTIC_V1");
        assertThat(PlanningEngines.DEFAULT_ENGINE).isEqualTo("HEURISTIC_V1");
    }

    @Test
    @DisplayName("an engine is selectable by name")
    void selectsByName() {
        assertThat(engines.select("PLANNING_V2").name()).isEqualTo("PLANNING_V2");
        assertThat(engines.select(" PLANNING_V2 ").name()).isEqualTo("PLANNING_V2");
    }

    @Test
    @DisplayName("an unknown engine is a bad request that names the ones that exist")
    void unknownEngine() {
        assertThatThrownBy(() -> engines.select("SOLVER_V9"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("SOLVER_V9")
                .hasMessageContaining("HEURISTIC_V1")
                .hasMessageContaining("PLANNING_V2");
    }

    @Test
    @DisplayName("every engine is offered, in registration order")
    void listsWhatExists() {
        assertThat(engines.availableNames()).containsExactly("HEURISTIC_V1", "PLANNING_V2");
    }

    /**
     * Two engines answering to one name would make a proposal's {@code engine} field a lie, and
     * that field exists precisely so a plan can be traced back to the rules that produced it.
     */
    @Test
    @DisplayName("two engines with the same name fail at startup rather than shadowing each other")
    void duplicateNamesAreRefused() {
        assertThatThrownBy(() ->
                new PlanningEngines(List.of(new HeuristicPlanningEngine(), new HeuristicPlanningEngine())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HEURISTIC_V1");
    }
}
