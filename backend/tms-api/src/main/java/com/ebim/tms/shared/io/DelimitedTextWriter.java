package com.ebim.tms.shared.io;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * An RFC 4180 CSV writer - the counterpart of {@link DelimitedTextReader}, and deliberately much
 * smaller than it.
 *
 * <p>Reading has to survive whatever an operator's spreadsheet produced, which is why that class
 * detects its delimiter and strips a BOM. Writing has no such problem: this decides the file, so it
 * decides the rules, and it picks the one shape that opens correctly everywhere.
 *
 * <ul>
 *   <li><b>A comma, always.</b> The reader accepts three delimiters because it must; there is no
 *       reason to emit more than one. A file this writes and that reader reads round-trips by
 *       construction.</li>
 *   <li><b>A UTF-8 BOM, always.</b> Without it Excel on Windows opens the file in the machine's
 *       ANSI code page and every accented character in a carrier's name is mangled. It costs three
 *       bytes and {@link DelimitedTextReader} strips it on the way back in - the same trade
 *       {@code ImportTemplateWriter} already makes for the import templates.</li>
 *   <li><b>CRLF line endings</b>, as RFC 4180 specifies and as Excel expects.</li>
 * </ul>
 *
 * <p><b>Values are quoted only when they have to be</b> - when they contain a comma, a quote, or a
 * newline - which keeps a numeric column readable in a text editor and diffable in a repository.
 * A quote inside a quoted field is written {@code ""}.
 *
 * <p><b>What it does not do, on purpose.</b> No formatting, no locale, no number rounding, no date
 * pattern: every cell arrives as a string the caller has already decided on. An export whose
 * decimal separator followed the reader's locale would produce a file that is a different document
 * in Lima and in London, and a CSV is supposed to be the machine-readable copy - the screen is
 * where regional formatting belongs ({@code frontend/tms-web/src/shared/i18n/format.ts}).
 */
public final class DelimitedTextWriter {

    private static final char DELIMITER = ',';
    private static final char QUOTE = '"';
    private static final String RECORD_SEPARATOR = "\r\n";

    /**
     * The UTF-8 byte-order mark, written as a code point rather than as the character itself: the
     * literal is invisible in a source file, which makes it the kind of thing an editor silently
     * eats. Same form {@code ImportTemplateWriter} uses.
     */
    private static final char BOM = (char) 0xFEFF;

    private DelimitedTextWriter() {}

    /**
     * Writes a header row followed by the data rows, as UTF-8 bytes with a BOM.
     *
     * <p>Rows shorter or longer than the header are written as they are rather than padded or
     * truncated: a caller that produced a ragged row has a defect, and silently squaring it off
     * would hide the column that went missing. Every caller here builds its rows from the same
     * column list it built the header from, so the case does not arise.
     *
     * @param header one label per column, in write order
     * @param rows   the data, one list of already-formatted cell values per row
     */
    public static byte[] write(List<String> header, List<List<String>> rows) {
        StringBuilder csv = new StringBuilder();
        csv.append(BOM);
        appendRow(csv, header);
        rows.forEach(row -> appendRow(csv, row));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendRow(StringBuilder csv, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                csv.append(DELIMITER);
            }
            appendValue(csv, values.get(index));
        }
        csv.append(RECORD_SEPARATOR);
    }

    /**
     * A null cell is written as an empty field and never as the four letters {@code null}.
     *
     * <p>Accepted rather than refused because "there is no value here" is a real cell in every
     * report this writes - a percentage over nothing, a figure not yet recorded - and a caller that
     * had to remember to pass {@code ""} would eventually forget once. A column reading
     * {@code null} would be imported into a spreadsheet as text and break the whole column's type.
     */
    private static void appendValue(StringBuilder csv, String value) {
        if (value == null) {
            return;
        }
        if (value.indexOf(DELIMITER) < 0 && value.indexOf(QUOTE) < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            csv.append(value);
            return;
        }
        csv.append(QUOTE).append(value.replace("\"", "\"\"")).append(QUOTE);
    }
}
