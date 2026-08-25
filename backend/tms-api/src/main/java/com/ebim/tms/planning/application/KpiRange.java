package com.ebim.tms.planning.application;

import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

/**
 * A resolved span of operating days: two real dates, both inclusive, within the cap.
 *
 * <p>The only way to get one is {@link #of}, so every caller - the report and the CSV export -
 * reads the same days from the same input, and a report whose export covered a different month
 * than the screen is not expressible.
 *
 * @param from the first operating day, inclusive
 * @param to   the last operating day, inclusive; never before {@code from}
 */
public record KpiRange(LocalDate from, LocalDate to) {

    /**
     * How many days a range may span. A quarter, because that is the longest span an operations
     * review actually asks for and because the whole report is aggregates over it: 92 days of one
     * company at the stated scale is tens of thousands of trips behind two indexes, which is a
     * query, and a year would be four times that for a chart with 365 columns nobody can read.
     *
     * <p>It is also the only bound on the report's cost. Every statement behind it is
     * range-predicated, so this number is what stops one request asking the database for a
     * company's entire history.
     */
    public static final int MAX_DAYS = 92;

    /**
     * The span a caller gets when they name neither end - the last thirty days including today,
     * which is what "how are we doing" means to somebody who opened the screen without touching
     * the filter bar.
     */
    public static final int DEFAULT_DAYS = 30;

    public KpiRange {
        if (from == null || to == null) {
            throw new IllegalArgumentException("a KPI range needs both ends");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("a KPI range cannot end before it starts");
        }
    }

    /**
     * Resolves what the caller asked for into a span this report will actually run over.
     *
     * <p>The rules, in order:
     *
     * <ol>
     *   <li>A missing {@code to} is <em>the company's own today</em> ({@code CompanyScope.today()})
     *       and never the server's and never one the browser computed - the same rule
     *       {@link ControlTowerFilter} states, for the same reason: a report opened at 23:30 in
     *       {@code America/Lima} must end on the 23rd and not on the 24th that UTC already thinks
     *       it is.</li>
     *   <li>A missing {@code from} is {@code to} minus {@link #DEFAULT_DAYS} - 1, so the default
     *       span is thirty days <em>including</em> both ends rather than thirty-one.</li>
     *   <li>A range that runs backwards is refused rather than swapped. Swapping would silently
     *       answer a different question than the one a mistyped filter asked, and the operator
     *       would have no way to tell.</li>
     *   <li>A range longer than {@link #MAX_DAYS} is refused, naming the cap, rather than truncated
     *       - a report that quietly returned a quarter of the year somebody asked for would be
     *       read as the year.</li>
     * </ol>
     *
     * @throws InvalidRequestException if the range runs backwards or exceeds {@link #MAX_DAYS}
     */
    public static KpiRange of(CompanyScope scope, KpiFilter filter) {
        LocalDate to = filter.to() == null ? scope.today() : filter.to();
        LocalDate from = filter.from() == null ? to.minusDays(DEFAULT_DAYS - 1L) : filter.from();

        if (to.isBefore(from)) {
            throw new InvalidRequestException(
                    "The end of the range (" + to + ") is before its start (" + from + ").");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw new InvalidRequestException("A report may cover at most " + MAX_DAYS + " days; "
                    + from + " to " + to + " is " + days + ".");
        }
        return new KpiRange(from, to);
    }

    /** How many operating days this range covers, both ends included. Never zero. */
    public int days() {
        return (int) (ChronoUnit.DAYS.between(from, to) + 1);
    }

    /**
     * Every day in the range, in order, whether or not anything happened on it.
     *
     * <p>What makes the daily series a series: a chart drawn from only the days that produced rows
     * would put a quiet Sunday next to a busy Monday at the same spacing and hide the gap, which is
     * the one thing an operations chart must not do. Bounded by {@link #MAX_DAYS}, so this is at
     * most ninety-two elements.
     */
    public List<LocalDate> dates() {
        return Stream.iterate(from, date -> !date.isAfter(to), date -> date.plusDays(1)).toList();
    }
}
