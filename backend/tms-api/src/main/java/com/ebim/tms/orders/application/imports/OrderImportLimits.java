package com.ebim.tms.orders.application.imports;

/**
 * The bounds a single import may not exceed. Every one of them is a denial-of-service control
 * first and a usability guardrail second: an import runs inside one request and one transaction,
 * against a database sized for 10,000+ orders a day, and an unbounded upload would let one
 * operator hold a connection and a transaction open for as long as the file is large.
 *
 * <p>The numbers are chosen so that the largest legitimate file an operator produces - a day's
 * worth of store replenishment - passes comfortably, and anything an order of magnitude beyond
 * it is refused with a message rather than absorbed. A genuinely larger load belongs to the
 * integration API, not to a browser upload.
 *
 * <p>{@link #MAX_FILE_BYTES} is enforced twice: by Spring's multipart resolver (configured in
 * {@code application.yml}, which rejects the upload before it is fully buffered) and again here
 * against the received bytes, so the limit does not depend on one configuration file being right.
 */
public final class OrderImportLimits {

    /** 2 MiB. An XLSX of 20,000 rows is well under this; a photo pasted into a sheet is not. */
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    /** Data rows, excluding the header. */
    public static final int MAX_ROWS = 5_000;

    /** Distinct external references, i.e. orders, in one file. */
    public static final int MAX_ORDERS = 1_000;

    /** Lines in one order. Beyond this the row grouping is far likelier to be wrong than real. */
    public static final int MAX_LINES_PER_ORDER = 200;

    /**
     * Issues carried back in one report. A file whose every row is wrong would otherwise produce
     * a response as large as the upload, which no operator can read anyway - the report says how
     * many were truncated.
     */
    public static final int MAX_REPORTED_ISSUES = 200;

    private OrderImportLimits() {}
}
