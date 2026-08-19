package com.ebim.tms.shared.api;

import java.util.List;

/**
 * The response half of the list contract.
 *
 * <p>Defined by TMS rather than returning a persistence framework's page type: the API
 * contract must not change shape because the backend swapped an ORM, and Spring Data's
 * {@code Page} serialises an unstable structure.
 *
 * @param content       the rows of this page, already scoped to the caller's company
 * @param page          zero-based page number
 * @param size          page size actually applied, after server-side clamping
 * @param totalElements total number of rows matching the filter <em>inside the company scope</em>
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {

    public PageResponse {
        content = List.copyOf(content);
    }

    public static <T> PageResponse<T> of(List<T> content, PageQuery query, long totalElements) {
        return new PageResponse<>(content, query.pageNumber(), query.pageSize(), totalElements);
    }

    public int totalPages() {
        return size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    }

    public boolean hasNext() {
        return (long) (page + 1) * size < totalElements;
    }

    public boolean hasPrevious() {
        return page > 0;
    }
}
