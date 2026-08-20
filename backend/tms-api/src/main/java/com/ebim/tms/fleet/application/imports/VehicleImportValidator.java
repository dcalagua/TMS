package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.fleet.domain.VehicleAvailabilityStatus;
import com.ebim.tms.shared.imports.ImportIssue;
import com.ebim.tms.shared.imports.ImportOutcome;
import com.ebim.tms.shared.imports.ImportRow;
import com.ebim.tms.shared.imports.ImportValues;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The whole of the Vehicle import's business validation, as a pure function over already-parsed
 * rows and an already-resolved snapshot of the carriers/vehicle types they refer to. See
 * {@code LocationImportValidator} for the pattern this follows and for why resolution happens
 * once for the whole file rather than once per row.
 */
final class VehicleImportValidator {

    private static final Pattern LICENSE_PLATE_SHAPE = Pattern.compile("^[A-Z0-9-]{4,12}$");

    /**
     * @param carrierIdsByCode     active carriers of the caller's company, keyed by normalised code
     * @param vehicleTypeIdsByCode vehicle types of the caller's company, keyed by normalised code
     * @param existingCodes        vehicle codes already used in this company - the idempotency check
     * @param existingLicensePlates license plates already used in this company
     */
    record MasterSnapshot(Map<String, UUID> carrierIdsByCode, Map<String, UUID> vehicleTypeIdsByCode,
            Set<String> existingCodes, Set<String> existingLicensePlates) {
    }

    record Result(List<VehicleImportCandidate> candidates, List<ImportIssue> issues) {
    }

    private VehicleImportValidator() {}

    static Set<String> referencedCarrierCodes(List<ImportRow> rows) {
        return referencedCodes(rows, VehicleImportColumn.CARRIER_CODE);
    }

    static Set<String> referencedVehicleTypeCodes(List<ImportRow> rows) {
        return referencedCodes(rows, VehicleImportColumn.VEHICLE_TYPE_CODE);
    }

    private static Set<String> referencedCodes(List<ImportRow> rows, VehicleImportColumn column) {
        Set<String> codes = new HashSet<>();
        for (ImportRow row : rows) {
            String value = row.value(column.header());
            if (value != null && !value.isBlank()) {
                codes.add(value.trim().toUpperCase(Locale.ROOT));
            }
        }
        return Set.copyOf(codes);
    }

    static Result validate(List<ImportRow> rows, MasterSnapshot snapshot) {
        List<ImportIssue> issues = new ArrayList<>();
        List<VehicleImportCandidate> candidates = new ArrayList<>();
        Set<String> codesSeenInFile = new HashSet<>();
        Set<String> platesSeenInFile = new HashSet<>();

        for (ImportRow row : rows) {
            candidates.add(validateRow(row, snapshot, codesSeenInFile, platesSeenInFile, issues));
        }
        return new Result(List.copyOf(candidates), List.copyOf(issues));
    }

    private static VehicleImportCandidate validateRow(ImportRow row, MasterSnapshot snapshot,
            Set<String> codesSeenInFile, Set<String> platesSeenInFile, List<ImportIssue> issues) {
        int issuesBefore = issues.size();

        String rawCode = row.value(VehicleImportColumn.CODE.header());
        String code = rawCode == null ? null : rawCode.trim().toUpperCase(Locale.ROOT);
        if (code == null) {
            issues.add(issue(row, VehicleImportColumn.CODE, null, "A code is required."));
        } else if (!codesSeenInFile.add(code)) {
            issues.add(issue(row, VehicleImportColumn.CODE, code,
                    "This code appears more than once in the file. Each row must describe a different vehicle."));
        }

        String rawPlate = row.value(VehicleImportColumn.LICENSE_PLATE.header());
        String licensePlate = rawPlate == null ? null : rawPlate.trim().toUpperCase(Locale.ROOT);
        if (licensePlate == null) {
            issues.add(issue(row, VehicleImportColumn.LICENSE_PLATE, code, "A license plate is required."));
        } else if (!LICENSE_PLATE_SHAPE.matcher(licensePlate).matches()) {
            issues.add(issue(row, VehicleImportColumn.LICENSE_PLATE, code,
                    "'" + licensePlate + "' is not a valid license plate shape."));
        } else if (!platesSeenInFile.add(licensePlate)) {
            issues.add(issue(row, VehicleImportColumn.LICENSE_PLATE, code,
                    "This license plate appears more than once in the file."));
        }

        UUID carrierId = null;
        String carrierCode = ImportValues.clean(row.value(VehicleImportColumn.CARRIER_CODE.header()));
        if (carrierCode != null) {
            carrierId = snapshot.carrierIdsByCode().get(carrierCode.toUpperCase(Locale.ROOT));
            if (carrierId == null) {
                issues.add(issue(row, VehicleImportColumn.CARRIER_CODE, code,
                        "'" + carrierCode + "' does not match a carrier in this company."));
            }
        }

        UUID vehicleTypeId = null;
        String vehicleTypeCode = ImportValues.clean(row.value(VehicleImportColumn.VEHICLE_TYPE_CODE.header()));
        if (vehicleTypeCode == null) {
            issues.add(issue(row, VehicleImportColumn.VEHICLE_TYPE_CODE, code, "A vehicle type code is required."));
        } else {
            vehicleTypeId = snapshot.vehicleTypeIdsByCode().get(vehicleTypeCode.toUpperCase(Locale.ROOT));
            if (vehicleTypeId == null) {
                issues.add(issue(row, VehicleImportColumn.VEHICLE_TYPE_CODE, code,
                        "'" + vehicleTypeCode + "' does not match a vehicle type in this company."));
            }
        }

        BigDecimal maxWeightOverrideKg = convert(
                row, VehicleImportColumn.MAX_WEIGHT_OVERRIDE_KG, code, issues, ImportValues::positiveDecimal);
        BigDecimal maxVolumeOverrideM3 = convert(
                row, VehicleImportColumn.MAX_VOLUME_OVERRIDE_M3, code, issues, ImportValues::positiveDecimal);
        Integer maxPalletsOverride = convert(
                row, VehicleImportColumn.MAX_PALLETS_OVERRIDE, code, issues, ImportValues::nonNegativeInteger);

        VehicleAvailabilityStatus availabilityStatus = convert(
                row, VehicleImportColumn.AVAILABILITY_STATUS, code, issues, raw -> ImportValues.enumeration(raw,
                        VehicleAvailabilityStatus.class, "AVAILABLE, IN_MAINTENANCE, OUT_OF_SERVICE"));

        boolean rejected = issues.size() > issuesBefore;
        ImportOutcome outcome;
        if (rejected) {
            outcome = ImportOutcome.REJECTED;
        } else if (snapshot.existingCodes().contains(code) || snapshot.existingLicensePlates().contains(licensePlate)) {
            outcome = ImportOutcome.SKIPPED_DUPLICATE;
        } else {
            outcome = ImportOutcome.CREATE;
        }

        return new VehicleImportCandidate(code, outcome, row.rowNumber(), licensePlate, carrierCode, carrierId,
                vehicleTypeCode, vehicleTypeId, maxWeightOverrideKg, maxVolumeOverrideM3, maxPalletsOverride,
                availabilityStatus == null ? VehicleAvailabilityStatus.AVAILABLE : availabilityStatus,
                ImportValues.clean(row.value(VehicleImportColumn.EXTERNAL_REFERENCE.header())));
    }

    private static <T> T convert(ImportRow row, VehicleImportColumn column, String code, List<ImportIssue> issues,
            Function<String, T> converter) {
        try {
            return converter.apply(row.value(column.header()));
        } catch (ImportValues.Rejected rejected) {
            issues.add(issue(row, column, code, rejected.getMessage()));
            return null;
        }
    }

    private static ImportIssue issue(ImportRow row, VehicleImportColumn column, String code, String message) {
        return new ImportIssue(row.rowNumber(), column == null ? null : column.header(), code, message);
    }
}
