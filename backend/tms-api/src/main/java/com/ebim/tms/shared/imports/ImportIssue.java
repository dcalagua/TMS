package com.ebim.tms.shared.imports;

/**
 * One reason a row cannot be accepted.
 *
 * @param rowNumber  the row as the operator sees it in their spreadsheet, header included
 * @param column     the offending column's header, or {@code null} for a whole-row problem
 * @param identifier the row's business key (e.g. its {@code code}) if one could be read, else {@code null}
 */
public record ImportIssue(int rowNumber, String column, String identifier, String message) {
}
