package com.ebim.tms.shared.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.api.InvalidRequestException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ImportGridMapper} against a small made-up column set - every entity import's parser
 * delegates its header-finding and row-mapping to this class, so its rules only need proving once.
 */
class ImportGridMapperTest {

    private static final List<ImportColumnSpec> COLUMNS = List.of(
            new ImportColumnSpec("code", true), new ImportColumnSpec("name", true),
            new ImportColumnSpec("note", false));

    @Test
    @DisplayName("header matching ignores case, spaces, underscores and hyphens")
    void headerMatchingIsForgiving() {
        List<List<String>> grid = List.of(List.of("Code", "N-A_M E"), List.of("A1", "Alpha"));
        var parsed = ImportGridMapper.map(grid, COLUMNS, 100, "things");

        assertThat(parsed.columns()).containsExactly("code", "name");
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).value("code")).isEqualTo("A1");
    }

    @Test
    @DisplayName("columns may be reordered and unknown columns are ignored rather than rejected")
    void reorderedAndExtraColumns() {
        List<List<String>> grid = List.of(
                List.of("note", "name", "code"), List.of("ignore me", "Alpha", "A1"));
        var parsed = ImportGridMapper.map(grid, COLUMNS, 100, "things");

        assertThat(parsed.rows().get(0).value("code")).isEqualTo("A1");
        assertThat(parsed.rows().get(0).value("name")).isEqualTo("Alpha");
    }

    @Test
    @DisplayName("a blank row inside the table is skipped, not reported")
    void blankRowsAreSkipped() {
        List<List<String>> grid = List.of(
                List.of("code", "name"), List.of("A1", "Alpha"), List.of("", ""), List.of("A2", "Beta"));
        var parsed = ImportGridMapper.map(grid, COLUMNS, 100, "things");

        assertThat(parsed.rows()).hasSize(2);
        // Row numbers count the blank line, matching what the operator sees in their spreadsheet.
        assertThat(parsed.rows().get(1).rowNumber()).isEqualTo(4);
    }

    @Test
    @DisplayName("a missing required column is rejected before any row is read")
    void missingRequiredColumnIsRejected() {
        List<List<String>> grid = List.of(List.of("name"), List.of("Alpha"));
        assertThatThrownBy(() -> ImportGridMapper.map(grid, COLUMNS, 100, "things"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("code");
    }

    @Test
    @DisplayName("no recognisable header row is rejected with a message naming the subject")
    void noHeaderRowIsRejected() {
        List<List<String>> grid = List.of(List.of("nothing", "recognisable"));
        assertThatThrownBy(() -> ImportGridMapper.map(grid, COLUMNS, 100, "widgets"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("widgets");
    }

    @Test
    @DisplayName("more rows than the limit is rejected")
    void tooManyRowsIsRejected() {
        List<List<String>> grid = new java.util.ArrayList<>();
        grid.add(List.of("code", "name"));
        for (int index = 0; index < 5; index++) {
            grid.add(List.of("A" + index, "Alpha " + index));
        }
        assertThatThrownBy(() -> ImportGridMapper.map(grid, COLUMNS, 3, "things"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("more than 3 rows");
    }

    @Test
    @DisplayName("a header with no data rows is rejected")
    void headerWithNoDataIsRejected() {
        List<List<String>> grid = List.of(List.of("code", "name"));
        assertThatThrownBy(() -> ImportGridMapper.map(grid, COLUMNS, 100, "things"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("no data rows");
    }
}
