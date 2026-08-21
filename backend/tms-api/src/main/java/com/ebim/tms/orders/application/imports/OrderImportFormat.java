package com.ebim.tms.orders.application.imports;

import java.util.Locale;

/**
 * The two file formats the order import reads and writes. Mirrors
 * {@code ck_order_import_batch_format} (migration V17).
 */
public enum OrderImportFormat {

    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("csv", "text/csv; charset=UTF-8");

    private final String extension;
    private final String contentType;

    OrderImportFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /**
     * Decides the format of an upload from its <em>bytes</em>, falling back to the file name.
     *
     * <p>Content sniffing comes first on purpose: the browser's {@code Content-Type} for a
     * spreadsheet is whatever the operating system's file-type registry says, which on a machine
     * with an unusual association is regularly wrong, and an operator who renamed
     * {@code orders.xlsx} to {@code orders.csv} should get a clear "this is a workbook, not a
     * CSV" rather than a wall of parse errors. An XLSX file is a ZIP archive, so its first two
     * bytes are {@code PK}; nothing that is really CSV starts that way.
     *
     * @return the detected format, or {@code null} when the bytes are neither a ZIP archive nor
     *         a file named {@code .csv} or {@code .xlsx}
     */
    public static OrderImportFormat detect(byte[] content, String fileName) {
        if (content != null && content.length >= 2 && content[0] == 'P' && content[1] == 'K') {
            return XLSX;
        }
        String name = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv") || name.endsWith(".txt")) {
            return CSV;
        }
        if (name.endsWith(".xlsx")) {
            // Named .xlsx but not a ZIP archive: most often a .xls or a CSV that was renamed.
            // Returning XLSX here would surface as a corrupt-workbook stack trace, so it is a
            // rejection instead - the caller turns null into an explicit message.
            return null;
        }
        return null;
    }
}
