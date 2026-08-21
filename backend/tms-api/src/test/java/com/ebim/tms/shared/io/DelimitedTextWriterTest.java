package com.ebim.tms.shared.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The CSV writer, tested against the two audiences it actually has: Excel on a Windows machine and
 * {@link DelimitedTextReader}.
 *
 * <p>The round-trip test is the one that matters. Everything else here is a claim about bytes; that
 * one is a claim that a file this product wrote is a file this product can read, which is the only
 * property an export and an import have to share.
 */
class DelimitedTextWriterTest {

    private static String text(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("writes a header and its rows, comma-separated and CRLF-terminated")
    void writesAPlainGrid() {
        byte[] content = DelimitedTextWriter.write(
                List.of("date", "trips"),
                List.of(List.of("2026-03-01", "12"), List.of("2026-03-02", "9")));

        assertThat(text(content)).endsWith("date,trips\r\n2026-03-01,12\r\n2026-03-02,9\r\n");
    }

    @Test
    @DisplayName("starts with a UTF-8 BOM, so Excel on Windows does not mangle an accented name")
    void startsWithABom() {
        byte[] content = DelimitedTextWriter.write(List.of("carrier"), List.of(List.of("Transportes Ñandú")));

        assertThat(Arrays.copyOf(content, 3)).containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(text(content)).contains("Transportes Ñandú");
    }

    @Test
    @DisplayName("quotes only the values that need it, and doubles an embedded quote")
    void quotesOnlyWhereNeeded() {
        byte[] content = DelimitedTextWriter.write(
                List.of("a", "b", "c", "d"),
                List.of(List.of("plain", "has,comma", "has\"quote", "has\nnewline")));

        assertThat(text(content)).contains("plain,\"has,comma\",\"has\"\"quote\",\"has\nnewline\"\r\n");
    }

    @Test
    @DisplayName("writes a null as an empty cell and never as the four letters null")
    void nullBecomesAnEmptyCell() {
        byte[] content = DelimitedTextWriter.write(
                List.of("date", "onTimeDeparturePercent"),
                List.of(Arrays.asList("2026-03-01", null)));

        assertThat(text(content)).endsWith("2026-03-01,\r\n");
        assertThat(text(content)).doesNotContain("null");
    }

    @Test
    @DisplayName("writes a file its own reader reads back unchanged")
    void roundTripsThroughTheReader() {
        List<String> header = List.of("code", "name", "note");
        List<List<String>> rows = List.of(
                List.of("C-1", "Transportes Ñandú", "sin novedad"),
                List.of("C-2", "Logística, S.A.", "dijo \"no\""),
                List.of("C-3", "Two\nlines", ""));

        List<List<String>> read = DelimitedTextReader.read(DelimitedTextWriter.write(header, rows));

        assertThat(read).hasSize(4);
        assertThat(read.get(0)).isEqualTo(header);
        assertThat(read.subList(1, 4)).isEqualTo(rows);
    }
}
