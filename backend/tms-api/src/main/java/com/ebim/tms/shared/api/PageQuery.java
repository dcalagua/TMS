package com.ebim.tms.shared.api;

import jakarta.validation.constraints.Min;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The paging and sorting half of every list endpoint's request contract.
 *
 * <p>Bound from query parameters ({@code ?page=0&size=50&sort=code,asc}) as a
 * {@code @ModelAttribute}. Two things are deliberate:
 *
 * <ul>
 *   <li><b>The page size is capped by the server.</b> An unbounded {@code size} is a denial
 *       of service against a database designed for 10,000+ orders/day.</li>
 *   <li><b>Sorting is validated against a per-endpoint allow-list.</b> A sort field reaches
 *       an ORDER BY clause, which no ORM can parameterise; an allow-list is the only safe
 *       way to accept one from a client.</li>
 * </ul>
 *
 * <p>There is no tenant parameter here, and there never will be: the company scope comes from
 * the {@code X-Company-Id} header and is validated against the caller's memberships. A filter
 * object that accepted {@code companyId} would put the tenant back in the client's hands.
 */
public record PageQuery(
        @Min(value = 0, message = "must be zero or greater") Integer page,
        @Min(value = 1, message = "must be at least 1") Integer size,
        String sort) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 200;

    /** Unsorted first page with the default size; the starting point for tests and defaults. */
    public static PageQuery unpaged() {
        return new PageQuery(null, null, null);
    }

    public int pageNumber() {
        return page == null || page < 0 ? DEFAULT_PAGE : page;
    }

    /** Never larger than {@link #MAX_SIZE}: an oversized request is clamped, not rejected. */
    public int pageSize() {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    public int offset() {
        return pageNumber() * pageSize();
    }

    /**
     * Parses {@code sort} into ordered, validated sort terms.
     *
     * @param allowedProperties the properties this endpoint permits sorting on
     * @throws InvalidRequestException if a requested property is not allowed - an unknown
     *     sort is refused with 400, never silently ignored
     */
    public List<SortTerm> sortTerms(Set<String> allowedProperties) {
        if (sort == null || sort.isBlank()) {
            return List.of();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<SortTerm> terms = new java.util.ArrayList<>();
        for (String expression : sort.split(";")) {
            if (expression.isBlank()) {
                continue;
            }
            String[] parts = expression.split(",");
            String property = parts[0].trim();
            if (!allowedProperties.contains(property)) {
                throw new InvalidRequestException(
                        "sort property '" + property + "' is not sortable; allowed: " + allowedProperties);
            }
            if (!seen.add(property)) {
                continue;
            }
            boolean descending = parts.length > 1 && "desc".equals(parts[1].trim().toLowerCase(Locale.ROOT));
            terms.add(new SortTerm(property, descending));
        }
        return List.copyOf(terms);
    }

    /** One validated ORDER BY term. */
    public record SortTerm(String property, boolean descending) {
        public String direction() {
            return descending ? "DESC" : "ASC";
        }
    }
}
