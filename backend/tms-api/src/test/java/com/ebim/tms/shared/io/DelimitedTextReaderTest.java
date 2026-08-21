package com.ebim.tms.shared.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DelimitedTextReader} against the shapes a real Excel export produces, not only the ones
 * RFC 4180 describes - the delimiter and BOM handling exist for those files specifically, so they
 * are what is worth testing.
 */
class DelimitedTextReaderTest {

    private static List<List<String>> read(String text) {
        return DelimitedTextReader.read(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("plain comma-separated records")
    void plainCsv() {
        assertThat(read("a,b,c\n1,2,3\n"))
                .containsExactly(List.of("a", "b", "c"), List.of("1", "2", "3"));
    }

    @Test
    @DisplayName("CRLF line endings end a record without leaving a stray carriage return")
    void windowsLineEndings() {
        assertThat(read("a,b\r\n1,2\r\n")).containsExactly(List.of("a", "b"), List.of("1", "2"));
    }

    @Test
    @DisplayName("a quoted field may contain the delimiter, a newline and an escaped quote")
    void quoting() {
        assertThat(read("a,b\n\"x,y\",\"line1\nline2\"\n"))
                .containsExactly(List.of("a", "b"), List.of("x,y", "line1\nline2"));
        assertThat(read("a\n\"say \"\"hi\"\"\"\n")).containsExactly(List.of("a"), List.of("say \"hi\""));
    }

    @Test
    @DisplayName("a semicolon-separated export - what Excel writes on a Spanish-locale machine - is read")
    void semicolonDelimiter() {
        assertThat(read("externalReference;originCode\nORD-1;LIM-01\n"))
                .containsExactly(List.of("externalReference", "originCode"), List.of("ORD-1", "LIM-01"));
    }

    @Test
    @DisplayName("a tab-separated export is read")
    void tabDelimiter() {
        assertThat(read("a\tb\n1\t2\n")).containsExactly(List.of("a", "b"), List.of("1", "2"));
    }

    @Test
    @DisplayName("a UTF-8 BOM does not become part of the first header")
    void bomIsStripped() {
        // Left in place, the first column stops matching its header and the file looks unusable.
        assertThat(read("\uFEFFexternalReference,originCode\nORD-1,LIM-01\n").get(0))
                .containsExactly("externalReference", "originCode");
    }

    @Test
    @DisplayName("a semicolon file whose fields contain commas keeps the semicolon as the delimiter")
    void delimiterDetectionPrefersTheMoreFrequentCandidate() {
        assertThat(read("name;note\nAcme, Inc;a, b, c\n").get(1)).containsExactly("Acme, Inc", "a, b, c");
    }

    @Test
    @DisplayName("a blank line keeps its place so the records below it are not renumbered")
    void blankLinesArePreserved() {
        // The caller reports row numbers to an operator reading the same file, so record 3 has
        // to stay record 3 even though record 2 is empty.
        assertThat(read("a,b\n\n1,2\n"))
                .containsExactly(List.of("a", "b"), List.of(""), List.of("1", "2"));
    }

    @Test
    @DisplayName("a trailing newline produces no phantom record after the last line")
    void trailingNewline() {
        assertThat(read("a,b\n1,2\n")).containsExactly(List.of("a", "b"), List.of("1", "2"));
    }

    @Test
    @DisplayName("a final record without a trailing newline is still read")
    void lastRecordWithoutNewline() {
        assertThat(read("a,b\n1,2")).containsExactly(List.of("a", "b"), List.of("1", "2"));
    }

    @Test
    @DisplayName("empty fields are preserved, including trailing ones")
    void emptyFields() {
        assertThat(read("a,b,c\n1,,\n").get(1)).containsExactly("1", "", "");
    }

    @Test
    @DisplayName("a ragged record is returned as it is, not padded")
    void raggedRecord() {
        // Padding here would hide "this row is missing its last columns", which the import may
        // legitimately want to report.
        assertThat(read("a,b,c\n1,2\n").get(1)).containsExactly("1", "2");
    }

    @Test
    @DisplayName("accented text survives the round trip")
    void utf8Content() {
        assertThat(read("nombre\nBodega Miraflores S.A.\n").get(1)).containsExactly("Bodega Miraflores S.A.");
    }

    @Test
    @DisplayName("empty input produces no records at all")
    void emptyInput() {
        assertThat(read("")).isEmpty();
    }
}
