package com.ebim.tms.integration.application;

import java.util.List;

/**
 * The answer to a batch of material profiles ({@code WAREHOUSE_EXECUTION_V1.md} 7.1). Not atomic:
 * each item stands alone, and one {@code INVALID} item makes the response 207.
 *
 * @param received every item sent
 * @param applied  the items that changed the stored copy ({@code CREATED} or {@code UPDATED})
 * @param refused  the {@code INVALID} items. {@code UNCHANGED} and {@code STALE} are neither applied
 *     nor refused: they are answered and change nothing
 */
public record MaterialProfileBatchResultV1(int received, int applied, int refused, List<Item> results) {

    public MaterialProfileBatchResultV1 {
        results = List.copyOf(results);
    }

    /** @param message why an {@code INVALID} item was refused; {@code null} otherwise */
    public record Item(int index, String materialCode, String outcome, String message) {
    }
}
