package com.ebim.tms.planning.application;

import com.ebim.tms.shared.api.InvalidRequestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The engines a planner may choose between (JOB 05).
 *
 * <p>{@link PlanningEngine} was written with the sentence "the interface exists before there is a
 * second implementation, and that is the point". This is that second implementation arriving, and
 * this class is the whole of what had to change to accommodate it: a lookup and a default.
 *
 * <h2>Why the default stays {@code HEURISTIC_V1}</h2>
 *
 * <p>Every company using automatic planning today gets V1's proposals, and silently swapping the
 * algorithm underneath them would change what a familiar button does with no way to tell that it
 * had. V2 is opt-in per run, the two are comparable on the same board, and promoting V2 to the
 * default is a decision to take with evidence from real datasets rather than one to smuggle in
 * with the code that introduced it.
 */
@Component
public class PlanningEngines {

    /** What a run gets when nobody names an engine. */
    public static final String DEFAULT_ENGINE = HeuristicPlanningEngine.NAME;

    private final Map<String, PlanningEngine> byName;

    /**
     * The names in registration order.
     *
     * <p>Held separately because {@code Map.copyOf} makes no ordering promise - the first version
     * of this class kept a {@link LinkedHashMap}, copied it, and then advertised an order it no
     * longer had. A UI listing the choices needs a stable one, so it is stored rather than derived.
     */
    private final List<String> names;

    public PlanningEngines(List<PlanningEngine> engines) {
        Map<String, PlanningEngine> registry = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (PlanningEngine engine : engines) {
            PlanningEngine clash = registry.putIfAbsent(engine.name(), engine);
            if (clash != null) {
                // Two engines answering to one name would make a proposal's `engine` field a lie,
                // and the field exists precisely so a plan can be traced back to the rules that
                // made it. Failing at startup is the only honest response.
                throw new IllegalStateException("two planning engines are both called " + engine.name());
            }
            order.add(engine.name());
        }
        this.byName = Map.copyOf(registry);
        this.names = List.copyOf(order);
    }

    /**
     * The engine by name, or the default when none is asked for.
     *
     * @throws InvalidRequestException for a name no engine answers to - a 400, because the caller
     *     asked for something that does not exist rather than something that went wrong
     */
    public PlanningEngine select(String name) {
        String wanted = name == null || name.isBlank() ? DEFAULT_ENGINE : name.trim();
        PlanningEngine engine = byName.get(wanted);
        if (engine == null) {
            throw new InvalidRequestException("Unknown planning engine '" + wanted + "'. Available: "
                    + String.join(", ", byName.keySet()) + ".");
        }
        return engine;
    }

    /** Every engine's name, for a UI that offers the choice. In registration order. */
    public List<String> availableNames() {
        return names;
    }
}
