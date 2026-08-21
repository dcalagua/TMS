package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.shared.imports.ImportFormat;
import com.ebim.tms.shared.imports.ImportGrid;
import com.ebim.tms.shared.imports.ImportGridMapper;
import com.ebim.tms.shared.imports.ImportLimits;
import com.ebim.tms.shared.imports.ImportRow;
import java.util.List;
import org.springframework.stereotype.Component;

/** Turns an uploaded XLSX or CSV into {@link ImportRow}s, through the shared grid reader/mapper. */
@Component
public class CarrierImportParser {

    public List<ImportRow> parse(byte[] content, ImportFormat format, ImportLimits limits) {
        List<List<String>> grid = ImportGrid.read(content, format);
        return ImportGridMapper.map(grid, CarrierImportColumn.SPECS, limits.maxRows(), "carriers").rows();
    }
}
