package com.ebim.tms.shared.imports;

import java.util.List;
import java.util.UUID;

/**
 * What a bulk import - dry run or applied - did or would do. One shape for both, so the preview
 * an operator approves and the confirmation they receive are literally the same document with
 * {@link #applied} flipped, and a difference between the two cannot hide in a second schema.
 * Generalizes {@code OrderImportReport} so every entity import returns the same envelope, with
 * {@code T} carrying whatever per-item preview fields are specific to that entity.
 *
 * <h2>Why an invalid file is still a 200</h2>
 *
 * <p>Applying a file with invalid rows writes nothing and comes back as this report with
 * {@code applied = false}, not as an error status: the endpoint did exactly what it was asked - it
 * evaluated the file - and the answer is the report. What is not negotiable is that there is no
 * partial success: every import service applies in a single transaction and refuses the whole
 * file if any row is invalid.
 *
 * @param dryRun          true when nothing was written because nothing was meant to be
 * @param applied         true only when every item in the file was committed
 * @param batchId         the audit batch row's id, present only when applied
 * @param rowCount        data rows read, blank rows excluded
 * @param itemCount       distinct items (rows, for a one-row-per-item import) the file describes
 * @param createdCount    items created, or - on a dry run - items that would be
 * @param skippedCount     items already present, which are a no-op
 * @param rejectedCount   items that could not be accepted because they have an issue
 * @param issueCount      total issues found, which may exceed {@code issues.size()}
 * @param issuesTruncated true when the report's issue list was cut short of {@code issueCount}
 */
public record ImportReport<T>(
        boolean dryRun,
        boolean applied,
        UUID batchId,
        String fileName,
        ImportFormat format,
        int rowCount,
        int itemCount,
        int createdCount,
        int skippedCount,
        int rejectedCount,
        int issueCount,
        boolean issuesTruncated,
        List<T> items,
        List<ImportIssue> issues) {
}
