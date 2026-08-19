package com.ebim.tms.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The list-endpoint contract every module from Step 05 onwards is expected to follow.
 *
 * <p>The two rules worth testing are the two that are security relevant: an unbounded page size
 * is a denial of service, and a client-chosen sort column reaches an ORDER BY clause that no
 * ORM can parameterise.
 */
class PagingConventionsTest {

    private static final Set<String> SORTABLE = Set.of("code", "name", "createdAt");

    @Test
    @DisplayName("defaults are applied when the client sends nothing")
    void defaultsAreApplied() {
        PageQuery query = PageQuery.unpaged();

        assertThat(query.pageNumber()).isZero();
        assertThat(query.pageSize()).isEqualTo(PageQuery.DEFAULT_SIZE);
        assertThat(query.offset()).isZero();
        assertThat(query.sortTerms(SORTABLE)).isEmpty();
    }

    @Test
    @DisplayName("an oversized page is clamped by the server, never honoured")
    void pageSizeIsClamped() {
        assertThat(new PageQuery(0, 100_000, null).pageSize()).isEqualTo(PageQuery.MAX_SIZE);
        assertThat(new PageQuery(0, 50, null).pageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("nonsense paging values fall back to the defaults instead of producing a bad query")
    void invalidValuesFallBack() {
        PageQuery query = new PageQuery(-3, 0, null);

        assertThat(query.pageNumber()).isZero();
        assertThat(query.pageSize()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("the offset follows from the clamped size, so it cannot overshoot either")
    void offsetUsesTheClampedSize() {
        assertThat(new PageQuery(3, 100_000, null).offset()).isEqualTo(3 * PageQuery.MAX_SIZE);
    }

    @Test
    @DisplayName("only allow-listed properties may be sorted on")
    void sortIsAllowListed() {
        assertThat(new PageQuery(0, 10, "code,desc").sortTerms(SORTABLE))
                .containsExactly(new PageQuery.SortTerm("code", true));

        assertThatThrownBy(() -> new PageQuery(0, 10, "password").sortTerms(SORTABLE))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not sortable");
    }

    @Test
    @DisplayName("a sort injection attempt is refused, not sanitised into something surprising")
    void sortInjectionIsRefused() {
        assertThatThrownBy(() -> new PageQuery(0, 10, "code; DROP TABLE tms.company").sortTerms(SORTABLE))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("several sort terms are kept in order and de-duplicated")
    void multipleSortTerms() {
        List<PageQuery.SortTerm> terms = new PageQuery(0, 10, "name,asc;code,desc;name,desc").sortTerms(SORTABLE);

        assertThat(terms).containsExactly(
                new PageQuery.SortTerm("name", false),
                new PageQuery.SortTerm("code", true));
        assertThat(terms.getFirst().direction()).isEqualTo("ASC");
    }

    @Test
    @DisplayName("the response reports the size actually applied, not the one requested")
    void responseReportsTheAppliedSize() {
        PageQuery query = new PageQuery(1, 100_000, null);
        PageResponse<String> response = PageResponse.of(List.of("a", "b"), query, 500);

        assertThat(response.size()).isEqualTo(PageQuery.MAX_SIZE);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.totalElements()).isEqualTo(500);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.hasPrevious()).isTrue();
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    @DisplayName("an empty result is a valid page, not a special case")
    void emptyPage() {
        PageResponse<String> response = PageResponse.of(List.of(), PageQuery.unpaged(), 0);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalPages()).isZero();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.hasPrevious()).isFalse();
    }

    @Test
    @DisplayName("no paging or filter parameter accepts a tenant: the company comes from the header")
    void pagingCarriesNoTenant() {
        assertThat(PageQuery.class.getRecordComponents())
                .as("a company id in a query parameter would put the tenant back in the client's hands")
                .noneMatch(component -> component.getName().toLowerCase().contains("company"))
                .noneMatch(component -> component.getName().toLowerCase().contains("organization"));
    }
}
