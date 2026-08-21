package com.ebim.tms.orders.application.imports;

import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.TotalsSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What an import - dry run or applied - did or would do. One shape for both, so the preview an
 * operator approves and the confirmation they receive are literally the same document with
 * {@link #applied} flipped, and a difference between the two cannot hide in a second schema.
 *
 * <h2>Why an invalid file is still a 200</h2>
 *
 * <p>Applying a file with invalid rows writes nothing and comes back as this report with
 * {@code applied = false}, not as an error status. The endpoint did exactly what it was asked -
 * it evaluated the file - and the answer is the report. Modelling it as an error would force the
 * detail an operator needs (which row, which column, why) into an RFC 9457 {@code detail} string
 * that no interface can render as a table.
 *
 * <p>What is <em>not</em> negotiable is that there is no partial success: {@code OrderImportService}
 * applies in a single transaction and refuses the whole file if any row is invalid. A caller
 * therefore only ever has to read {@link #applied}, never reconcile counts.
 *
 * @param dryRun         true when nothing was written because nothing was meant to be
 * @param applied        true only when every order in the file was committed
 * @param batchId        the {@code tms.order_import_batch} row, present only when applied
 * @param rowCount       data rows read, blank rows excluded
 * @param createdCount   orders created, or - on a dry run - orders that would be
 * @param skippedCount   orders already present under this external source, which are a no-op
 * @param rejectedCount  orders that could not be accepted because a row of theirs has an issue
 * @param issueCount     total issues found, which may exceed {@code issues.size()}
 * @param issuesTruncated true when {@link OrderImportLimits#MAX_REPORTED_ISSUES} cut the list
 */
public record OrderImportReport(
        boolean dryRun,
        boolean applied,
        UUID batchId,
        String fileName,
        OrderImportFormat format,
        String externalSource,
        int rowCount,
        int orderCount,
        int createdCount,
        int skippedCount,
        int rejectedCount,
        int issueCount,
        boolean issuesTruncated,
        List<OrderPreview> orders,
        List<Issue> issues) {

    /** What the import decided about one order - one group of rows sharing an external reference. */
    public enum Outcome {

        /** Will be created (dry run) or was created (apply). */
        CREATE,

        /**
         * An order with this {@code (externalSource, externalReference)} already exists in the
         * company, so the import leaves it untouched. This is what makes re-uploading the same
         * file harmless, and it is deliberately not an error: idempotency means a repeat is a
         * no-op, not a failure.
         */
        SKIPPED_DUPLICATE,

        /** At least one row of this order has an issue, so nothing in the file is applied. */
        REJECTED
    }

    /**
     * One order as the file describes it, with the totals already resolved through
     * {@code OrderTotals} - so the preview shows the figures that will actually be persisted,
     * including which of the two sources produced them, rather than a re-derivation the frontend
     * would have to keep in step.
     */
    public record OrderPreview(
            String externalReference,
            Outcome outcome,
            int firstRowNumber,
            String originCode,
            String originName,
            String destinationCode,
            String destinationName,
            String customerName,
            LocalDate serviceDate,
            OrderPriority priority,
            int lineCount,
            BigDecimal totalWeightKg,
            BigDecimal totalVolumeM3,
            BigDecimal totalPallets,
            TotalsSource totalsSource,
            /** Set only once the order exists, i.e. on an applied import. */
            String orderNumber) {
    }

    /**
     * One reason a row cannot be accepted.
     *
     * @param rowNumber the row as the operator sees it in their spreadsheet, header included
     * @param column    the offending column's header, or {@code null} for a whole-row problem
     */
    public record Issue(int rowNumber, String column, String externalReference, String message) {
    }
}
