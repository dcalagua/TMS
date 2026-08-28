package com.ebim.tms.shared.reference;

import java.util.List;

/**
 * Comparing a carrier's price with what our own truck would cost us (V48, JOB 22).
 *
 * <p>A pure function, and mostly a list of the times it refuses to answer. Every refusal below is a
 * case where a naive {@code min()} would have returned something, and what it returned would have
 * been wrong.
 */
public final class TransportCostComparison {

    private TransportCostComparison() {
    }

    /** Why no option could be named cheapest. */
    public enum Outcome {
        /** One option is cheapest, and {@code cheapest} names it. */
        COMPARED,
        /** Fewer than two options carried an amount. Nothing to compare. */
        NOT_ENOUGH_COSTED_OPTIONS,
        /**
         * The options are in different currencies.
         *
         * <p>TMS holds no exchange rate and will not invent one. Converting at a rate nobody
         * approved would produce a decision that changes when the rate does, without anybody
         * choosing that. The options are reported side by side in their own currencies instead.
         */
        INCOMPARABLE_CURRENCY,
        /** Two options tie exactly. Naming either would be arbitrary. */
        TIED
    }

    public record Result(Outcome outcome, TransportCostQuote cheapest, List<TransportCostQuote> options) {

        public Result {
            options = List.copyOf(options);
        }

        /**
         * Whether the winner is an internal cost being held up against a commercial price.
         *
         * <p>True does not make the comparison invalid - it is the comparison a planner is there to
         * make - but the screen must say so. A carrier's price contains their margin and our
         * estimate contains none, so own fleet coming out lower is the expected shape of the two
         * numbers and not, on its own, evidence that running it ourselves is cheaper.
         */
        public boolean comparesCostAgainstPrice() {
            if (outcome != Outcome.COMPARED) {
                return false;
            }
            return options.stream().filter(TransportCostQuote::isCosted)
                    .map(TransportCostQuote::nature).distinct().count() > 1;
        }
    }

    public static Result compare(List<TransportCostQuote> options) {
        List<TransportCostQuote> all = options == null ? List.of() : List.copyOf(options);
        List<TransportCostQuote> costed = all.stream().filter(TransportCostQuote::isCosted).toList();

        if (costed.size() < 2) {
            return new Result(Outcome.NOT_ENOUGH_COSTED_OPTIONS, null, all);
        }
        long currencies = costed.stream().map(TransportCostQuote::currency).distinct().count();
        if (currencies > 1) {
            return new Result(Outcome.INCOMPARABLE_CURRENCY, null, all);
        }

        TransportCostQuote best = costed.stream()
                .min((a, b) -> a.amount().compareTo(b.amount()))
                .orElseThrow();
        long atBest = costed.stream().filter(q -> q.amount().compareTo(best.amount()) == 0).count();
        if (atBest > 1) {
            return new Result(Outcome.TIED, null, all);
        }
        return new Result(Outcome.COMPARED, best, all);
    }
}
