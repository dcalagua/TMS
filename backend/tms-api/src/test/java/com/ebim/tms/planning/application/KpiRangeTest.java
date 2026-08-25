package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules that turn two optional query parameters into a span this report will run over.
 *
 * <p>All four matter operationally rather than technically: the default is what somebody sees when
 * they open the screen and never touch the filter bar, the zone is why a browser in Madrid and one
 * in Lima see the same days, and the two refusals are the difference between a wrong answer and no
 * answer.
 */
class KpiRangeTest {

    /** Lima, because it is the product's market and because it is far enough west that "today" is a real question. */
    private static final CompanyScope SCOPE = new CompanyScope(UUID.randomUUID(), "C1", "Company One",
            "America/Lima", UUID.randomUUID(), "ORG", "Organization", Set.of(Permission.MONITORING_TRANSPORT_READ));

    @Nested
    @DisplayName("when the caller names both ends")
    class BothEndsNamed {

        @Test
        @DisplayName("takes them as they are, both inclusive")
        void takesWhatWasAsked() {
            KpiRange range = KpiRange.of(SCOPE, new KpiFilter(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)));

            assertThat(range.from()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(range.to()).isEqualTo(LocalDate.of(2026, 3, 31));
            assertThat(range.days()).isEqualTo(31);
        }

        @Test
        @DisplayName("accepts a single day as a range of one")
        void oneDayIsOneDay() {
            LocalDate day = LocalDate.of(2026, 3, 1);

            assertThat(KpiRange.of(SCOPE, new KpiFilter(day, day)).days()).isEqualTo(1);
        }

        @Test
        @DisplayName("refuses a range that runs backwards rather than swapping it")
        void refusesABackwardsRange() {
            assertThatThrownBy(() -> KpiRange.of(SCOPE,
                    new KpiFilter(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 1))))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("2026-03-31")
                    .hasMessageContaining("2026-03-01");
        }

        @Test
        @DisplayName("refuses a range longer than the cap, naming it, rather than truncating it")
        void refusesTooLongARange() {
            LocalDate from = LocalDate.of(2026, 1, 1);

            assertThatThrownBy(() -> KpiRange.of(SCOPE, new KpiFilter(from, from.plusDays(KpiRange.MAX_DAYS))))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining(String.valueOf(KpiRange.MAX_DAYS));
        }

        @Test
        @DisplayName("accepts a range exactly at the cap")
        void acceptsExactlyTheCap() {
            LocalDate from = LocalDate.of(2026, 1, 1);

            assertThat(KpiRange.of(SCOPE, new KpiFilter(from, from.plusDays(KpiRange.MAX_DAYS - 1L)))
                    .days()).isEqualTo(KpiRange.MAX_DAYS);
        }
    }

    @Nested
    @DisplayName("when the caller names neither end")
    class Defaulted {

        @Test
        @DisplayName("ends on the company's own today, not the server's and not the browser's")
        void endsOnTheCompanysToday() {
            KpiRange range = KpiRange.of(SCOPE, new KpiFilter(null, null));

            assertThat(range.to()).isEqualTo(SCOPE.today());
        }

        @Test
        @DisplayName("covers thirty days including both ends, not thirty-one")
        void spansThirtyDaysInclusive() {
            KpiRange range = KpiRange.of(SCOPE, new KpiFilter(null, null));

            assertThat(range.days()).isEqualTo(KpiRange.DEFAULT_DAYS);
            assertThat(range.from()).isEqualTo(range.to().minusDays(KpiRange.DEFAULT_DAYS - 1L));
        }
    }

    @Nested
    @DisplayName("when the caller names one end")
    class HalfNamed {

        @Test
        @DisplayName("counts the default span back from a named end")
        void fromDefaultsBackwardsFromTo() {
            KpiRange range = KpiRange.of(SCOPE, new KpiFilter(null, LocalDate.of(2026, 3, 31)));

            assertThat(range.from()).isEqualTo(LocalDate.of(2026, 3, 2));
            assertThat(range.days()).isEqualTo(KpiRange.DEFAULT_DAYS);
        }

        @Test
        @DisplayName("runs a named start up to the company's today")
        void toDefaultsToToday() {
            KpiRange range = KpiRange.of(SCOPE, new KpiFilter(SCOPE.today().minusDays(3), null));

            assertThat(range.to()).isEqualTo(SCOPE.today());
            assertThat(range.days()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("the days it enumerates")
    class Dates {

        @Test
        @DisplayName("are every day in the range, in order, including the ones nothing happened on")
        void areContiguous() {
            KpiRange range = KpiRange.of(SCOPE, new KpiFilter(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5)));

            assertThat(range.dates()).containsExactly(
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 3),
                    LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5));
        }

        @Test
        @DisplayName("are one for a single-day range")
        void areOneForOneDay() {
            LocalDate day = LocalDate.of(2026, 3, 1);

            assertThat(KpiRange.of(SCOPE, new KpiFilter(day, day)).dates()).containsExactly(day);
        }
    }
}
