package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.fleet.domain.VehicleBodyType;
import com.ebim.tms.shared.imports.ImportIssue;
import com.ebim.tms.shared.imports.ImportOutcome;
import com.ebim.tms.shared.imports.ImportRow;
import com.ebim.tms.shared.imports.ImportValues;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * The whole of the Vehicle Type import's business validation, as a pure function over
 * already-parsed rows. See {@code LocationImportValidator} for the pattern this follows.
 */
final class VehicleTypeImportValidator {

    record MasterSnapshot(Set<String> existingCodes) {
    }

    record Result(List<VehicleTypeImportCandidate> candidates, List<ImportIssue> issues) {
    }

    private VehicleTypeImportValidator() {}

    static Result validate(List<ImportRow> rows, MasterSnapshot snapshot) {
        List<ImportIssue> issues = new ArrayList<>();
        List<VehicleTypeImportCandidate> candidates = new ArrayList<>();
        Set<String> codesSeenInFile = new HashSet<>();

        for (ImportRow row : rows) {
            candidates.add(validateRow(row, snapshot, codesSeenInFile, issues));
        }
        return new Result(List.copyOf(candidates), List.copyOf(issues));
    }

    private static VehicleTypeImportCandidate validateRow(
            ImportRow row, MasterSnapshot snapshot, Set<String> codesSeenInFile, List<ImportIssue> issues) {
        int issuesBefore = issues.size();

        String rawCode = row.value(VehicleTypeImportColumn.CODE.header());
        String code = rawCode == null ? null : rawCode.trim().toUpperCase(Locale.ROOT);
        if (code == null) {
            issues.add(issue(row, VehicleTypeImportColumn.CODE, null, "A code is required."));
        } else if (!codesSeenInFile.add(code)) {
            issues.add(issue(row, VehicleTypeImportColumn.CODE, code,
                    "This code appears more than once in the file. Each row must describe a different vehicle type."));
        }

        String name = ImportValues.clean(row.value(VehicleTypeImportColumn.NAME.header()));
        if (name == null) {
            issues.add(issue(row, VehicleTypeImportColumn.NAME, code, "A name is required."));
        }

        BigDecimal maxWeightKg = convert(row, VehicleTypeImportColumn.MAX_WEIGHT_KG, code, issues, ImportValues::positiveDecimal);
        if (!row.hasValue(VehicleTypeImportColumn.MAX_WEIGHT_KG.header())) {
            issues.add(issue(row, VehicleTypeImportColumn.MAX_WEIGHT_KG, code, "A max weight is required."));
        }
        BigDecimal maxVolumeM3 = convert(row, VehicleTypeImportColumn.MAX_VOLUME_M3, code, issues, ImportValues::positiveDecimal);
        if (!row.hasValue(VehicleTypeImportColumn.MAX_VOLUME_M3.header())) {
            issues.add(issue(row, VehicleTypeImportColumn.MAX_VOLUME_M3, code, "A max volume is required."));
        }
        Integer maxPallets = convert(row, VehicleTypeImportColumn.MAX_PALLETS, code, issues, ImportValues::nonNegativeInteger);
        if (!row.hasValue(VehicleTypeImportColumn.MAX_PALLETS.header())) {
            issues.add(issue(row, VehicleTypeImportColumn.MAX_PALLETS, code, "A max pallets count is required."));
        }

        BigDecimal lengthM = convert(row, VehicleTypeImportColumn.LENGTH_M, code, issues, ImportValues::positiveDecimal);
        BigDecimal widthM = convert(row, VehicleTypeImportColumn.WIDTH_M, code, issues, ImportValues::positiveDecimal);
        BigDecimal heightM = convert(row, VehicleTypeImportColumn.HEIGHT_M, code, issues, ImportValues::positiveDecimal);

        VehicleBodyType bodyType = convert(row, VehicleTypeImportColumn.BODY_TYPE, code, issues, raw ->
                ImportValues.enumeration(raw, VehicleBodyType.class,
                        "DRY_VAN, REFRIGERATED, FLATBED, TANKER, CONTAINER, CURTAIN_SIDER, OTHER"));

        Boolean temperatureControlled = convert(row, VehicleTypeImportColumn.TEMPERATURE_CONTROLLED, code, issues, ImportValues::bool);
        boolean isTemperatureControlled = Boolean.TRUE.equals(temperatureControlled);

        BigDecimal minTemperature = convert(row, VehicleTypeImportColumn.MIN_TEMPERATURE_CELSIUS, code, issues, ImportValues::decimal);
        BigDecimal maxTemperature = convert(row, VehicleTypeImportColumn.MAX_TEMPERATURE_CELSIUS, code, issues, ImportValues::decimal);
        if (!isTemperatureControlled && (minTemperature != null || maxTemperature != null)) {
            issues.add(issue(row, VehicleTypeImportColumn.TEMPERATURE_CONTROLLED, code,
                    "minTemperatureCelsius/maxTemperatureCelsius require temperatureControlled to be TRUE."));
        }
        if (minTemperature != null && maxTemperature != null && minTemperature.compareTo(maxTemperature) > 0) {
            issues.add(issue(row, VehicleTypeImportColumn.MAX_TEMPERATURE_CELSIUS, code,
                    "maxTemperatureCelsius must be greater than or equal to minTemperatureCelsius."));
        }

        Integer axles = convert(row, VehicleTypeImportColumn.AXLES, code, issues, raw -> {
            Integer value = ImportValues.nonNegativeInteger(raw);
            if (value != null && value < 1) {
                throw new ImportValues.Rejected("'" + raw + "' must be 1 or more.");
            }
            return value;
        });

        boolean rejected = issues.size() > issuesBefore;
        ImportOutcome outcome;
        if (rejected) {
            outcome = ImportOutcome.REJECTED;
        } else if (snapshot.existingCodes().contains(code)) {
            outcome = ImportOutcome.SKIPPED_DUPLICATE;
        } else {
            outcome = ImportOutcome.CREATE;
        }

        return new VehicleTypeImportCandidate(code, outcome, row.rowNumber(), name, maxWeightKg, maxVolumeM3,
                maxPallets == null ? 0 : maxPallets, lengthM, widthM, heightM, bodyType, isTemperatureControlled,
                minTemperature, maxTemperature, axles);
    }

    private static <T> T convert(ImportRow row, VehicleTypeImportColumn column, String code,
            List<ImportIssue> issues, Function<String, T> converter) {
        try {
            return converter.apply(row.value(column.header()));
        } catch (ImportValues.Rejected rejected) {
            issues.add(issue(row, column, code, rejected.getMessage()));
            return null;
        }
    }

    private static ImportIssue issue(ImportRow row, VehicleTypeImportColumn column, String code, String message) {
        return new ImportIssue(row.rowNumber(), column == null ? null : column.header(), code, message);
    }
}
