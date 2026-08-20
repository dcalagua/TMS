package com.ebim.tms.shared.imports;

/**
 * The bounds a single import may not exceed. Every one of them is a denial-of-service control
 * first and a usability guardrail second, following {@code OrderImportLimits}'s reasoning: an
 * import runs inside one request and one transaction, and an unbounded upload would let one
 * operator hold both open for as long as the file is large.
 *
 * @param maxFileBytes      enforced twice: by Spring's multipart resolver (application.yml) and
 *                          again against the received bytes, so the limit does not depend on one
 *                          configuration file being right
 * @param maxRows           data rows, excluding the header
 * @param maxReportedIssues issues carried back in one report; the report says how many more exist
 */
public record ImportLimits(int maxFileBytes, int maxRows, int maxReportedIssues) {

    /**
     * 2 MiB, 5,000 rows, 200 reported issues - the same numbers {@code OrderImportLimits} uses.
     * A master-data row is far smaller than an order-with-lines row, so this ceiling is, if
     * anything, more generous per byte than the order import's; a genuinely larger load belongs
     * to the integration API, not to a browser upload.
     */
    public static ImportLimits standard() {
        return new ImportLimits(2 * 1024 * 1024, 5_000, 200);
    }
}
