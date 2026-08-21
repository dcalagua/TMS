package com.ebim.tms.masterdata.application.imports;

import com.ebim.tms.shared.imports.ImportFormat;
import com.ebim.tms.shared.imports.ImportGrid;
import com.ebim.tms.shared.imports.ImportGridMapper;
import com.ebim.tms.shared.imports.ImportLimits;
import com.ebim.tms.shared.imports.ImportRow;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns an uploaded XLSX or CSV into {@link ImportRow}s, entirely through the shared
 * {@code com.ebim.tms.shared.imports} reader - this class touches no file-format library itself,
 * unlike {@code OrderImportParser}, which predates that shared package.
 */
@Component
public class LocationImportParser {

    public List<ImportRow> parse(byte[] content, ImportFormat format, ImportLimits limits) {
        List<List<String>> grid = ImportGrid.read(content, format);
        return ImportGridMapper.map(grid, LocationImportColumn.SPECS, limits.maxRows(), "locations").rows();
    }
}
