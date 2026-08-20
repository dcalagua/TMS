package com.ebim.tms.shared.io;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * An RFC 4180 CSV reader, plus the two deviations from that standard that real spreadsheet
 * exports contain.
 *
 * <p>Written rather than taken from a library because what is needed here is small and entirely
 * specified - quoting, escaped quotes, embedded newlines - and because the two deviations below
 * are the whole reason a generic parser tends to disappoint on files an operator produced with
 * Excel on a Spanish-locale Windows machine, which is exactly the input this has to survive.
 *
 * <ul>
 *   <li><b>The delimiter is detected, not assumed.</b> Excel writes {@code ;} rather than
 *       {@code ,} whenever the machine's list separator is a semicolon, which it is across most
 *       of Latin America and Europe. Refusing those files - or worse, reading every line as a
 *       single column - would make "export from Excel, upload here" fail for the majority of the
 *       intended users. The delimiter is whichever of {@code ,} {@code ;} {@code \t} occurs more
 *       often outside quotes in the first line.</li>
 *   <li><b>A UTF-8 BOM is stripped.</b> Excel prefixes one when saving as "CSV UTF-8", and left
 *       in place it becomes part of the first header's name, so the first column silently stops
 *       matching.</li>
 * </ul>
 *
 * <p>Everything else follows RFC 4180: a field may be quoted with {@code "}, a quote inside a
 * quoted field is written {@code ""}, and a quoted field may contain the delimiter and line
 * breaks. Both {@code \n} and {@code \r\n} end a record. A trailing newline does not produce a
 * final empty record, but a blank line inside the file <em>does</em> produce a record of one
 * empty field: dropping it would renumber every record below it, and callers report those
 * numbers to an operator who is looking at the same file.
 */
public final class DelimitedTextReader {

    private static final char[] CANDIDATE_DELIMITERS = {',', ';', '\t'};
    private static final char QUOTE = '"';
    /** U+FEFF, written as an escape: the literal character is invisible in a source file. */
    private static final String BOM = "\uFEFF";

    private DelimitedTextReader() {}

    /**
     * Parses UTF-8 bytes into records of raw, untrimmed cell values. Ragged records are returned
     * as they are: padding them to the header width is the caller's decision, because "this row
     * has fewer cells than the header" may be an error worth reporting rather than a blank.
     */
    public static List<List<String>> read(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith(BOM)) {
            text = text.substring(BOM.length());
        }
        return read(text, detectDelimiter(text));
    }

    /** The delimiter {@link #read(byte[])} would use - exposed so a caller can report it. */
    public static char detectDelimiter(String text) {
        String firstLine = firstLineOutsideQuotes(text);
        char best = ',';
        int bestCount = 0;
        for (char candidate : CANDIDATE_DELIMITERS) {
            int count = countOutsideQuotes(firstLine, candidate);
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    private static List<List<String>> read(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        // Tracks whether anything at all has been read since the last line break, so that the
        // newline ending the final line does not produce a phantom extra record after it.
        boolean recordStarted = false;

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (inQuotes) {
                if (character == QUOTE) {
                    if (index + 1 < text.length() && text.charAt(index + 1) == QUOTE) {
                        field.append(QUOTE);
                        index++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(character);
                }
                continue;
            }
            if (character == QUOTE) {
                inQuotes = true;
                recordStarted = true;
            } else if (character == delimiter) {
                record.add(field.toString());
                field.setLength(0);
                recordStarted = true;
            } else if (character == '\n' || character == '\r') {
                if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                // A blank line becomes a record of one empty field rather than being dropped.
                // Dropping it would shift every subsequent record up by one, and the caller
                // reports row numbers to an operator who is looking at the file - "row 14" has
                // to be row 14 in their spreadsheet, blank lines and all.
                record.add(field.toString());
                records.add(List.copyOf(record));
                record.clear();
                field.setLength(0);
                recordStarted = false;
            } else {
                field.append(character);
                recordStarted = true;
            }
        }

        if (recordStarted) {
            record.add(field.toString());
            records.add(List.copyOf(record));
        }
        return List.copyOf(records);
    }

    private static String firstLineOutsideQuotes(String text) {
        boolean inQuotes = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == QUOTE) {
                inQuotes = !inQuotes;
            } else if (!inQuotes && (character == '\n' || character == '\r')) {
                return text.substring(0, index);
            }
        }
        return text;
    }

    private static int countOutsideQuotes(String line, char character) {
        boolean inQuotes = false;
        int count = 0;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == QUOTE) {
                inQuotes = !inQuotes;
            } else if (!inQuotes && current == character) {
                count++;
            }
        }
        return count;
    }
}
