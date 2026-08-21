package com.ebim.tms.planning.application;

/**
 * A generated report file and the name the browser should save it under.
 *
 * <p>The name is composed on the server, beside the query that produced the bytes, so a file
 * sitting on somebody's desktop in March still says which company-days it covers. A client that
 * invented its own name would be guessing at a range the server may have defaulted or capped.
 *
 * @param fileName the suggested name, already carrying its extension
 * @param content  the bytes, complete - these reports are bounded by {@code KpiRange.MAX_DAYS} and
 *                 are kilobytes, so there is nothing here to stream
 */
public record KpiExportFile(String fileName, byte[] content) {
}
