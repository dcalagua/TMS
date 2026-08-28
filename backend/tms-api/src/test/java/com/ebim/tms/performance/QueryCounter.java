package com.ebim.tms.performance;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/**
 * Counts the SQL statements one call actually issues (JOB 25).
 *
 * <p><b>Why query count and not wall-clock time.</b> A duration measured on a laptop, in Docker,
 * against a container that started ninety seconds ago is noise: it varies by a factor of three
 * between runs on the same machine and says nothing about a server. Asserting on it produces a test
 * that fails on a busy afternoon and passes on a quiet one, which teaches everybody to ignore it.
 *
 * <p>A <b>query count is deterministic</b>, and it is the thing that actually breaks at volume. An
 * N+1 is invisible at ten rows and fatal at ten thousand - it does not get slower gradually, it gets
 * slower in proportion to the data, which is precisely the failure a 10,000-orders/day target
 * implies. Counting statements catches it on a fixture of forty.
 *
 * <p>Wall-clock is still <em>recorded</em>, in {@code docs/operations/PERFORMANCE_BASELINE.md}, as
 * information for a human. Nothing asserts on it.
 */
public final class QueryCounter {

    private final Statistics statistics;

    public QueryCounter(EntityManagerFactory entityManagerFactory) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        this.statistics.setStatisticsEnabled(true);
    }

    /** Counts the statements {@code work} issues, from zero. */
    public long count(Runnable work) {
        statistics.clear();
        long before = statistics.getPrepareStatementCount();
        work.run();
        return statistics.getPrepareStatementCount() - before;
    }
}
