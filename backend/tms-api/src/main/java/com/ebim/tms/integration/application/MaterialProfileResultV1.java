package com.ebim.tms.integration.application;

import java.time.OffsetDateTime;

/**
 * The answer to one material profile ({@code WAREHOUSE_EXECUTION_V1.md} 7.1).
 *
 * @param outcome         {@code CREATED}, {@code UPDATED}, {@code UNCHANGED} or {@code STALE}
 * @param version         the stored version after the write; the untouched one when {@code STALE}
 * @param sourceUpdatedAt the stored owner timestamp, so a sender told {@code STALE} sees the newer one
 */
public record MaterialProfileResultV1(String materialCode, String outcome, long version,
        OffsetDateTime sourceUpdatedAt) {
}
