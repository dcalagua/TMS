package com.ebim.tms.masterdata.application.imports;

import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.masterdata.domain.LocationType;
import com.ebim.tms.shared.imports.ImportIssue;
import com.ebim.tms.shared.imports.ImportOutcome;
import com.ebim.tms.shared.imports.ImportRow;
import com.ebim.tms.shared.imports.ImportValues;
import com.ebim.tms.shared.settings.CompanySettings;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The whole of the Location import's business validation, as a pure function over already-parsed
 * rows and an already-resolved snapshot of the zones/codes they refer to. Pure on purpose, the
 * same reasoning {@code OrderImportValidator} documents: every rule is exercised with no Spring
 * context, no HTTP and no database.
 *
 * <p>Unlike the order import, one row is one location - there is no line-grouping - so validation
 * is a per-row loop rather than a group-then-validate pass.
 */
final class LocationImportValidator {

    /**
     * @param zoneIdsByCode  active zones of the caller's company, keyed by normalised code
     * @param existingCodes  location codes already used in this company - the idempotency check
     */
    record MasterSnapshot(Map<String, UUID> zoneIdsByCode, Set<String> existingCodes) {
    }

    record Result(List<LocationImportCandidate> candidates, List<ImportIssue> issues) {
    }

    private LocationImportValidator() {}

    static Set<String> referencedZoneCodes(List<ImportRow> rows) {
        return rows.stream()
                .map(row -> row.value(LocationImportColumn.ZONE_CODE.header()))
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The product's own default country, for a caller that has no company settings to hand. Kept as
     * an overload rather than a nullable parameter so the tests of the rules themselves - which are
     * about codes, roles and coordinates, not about which market this is - do not each have to
     * declare a country they do not care about.
     */
    static Result validate(List<ImportRow> rows, MasterSnapshot snapshot, String defaultTimeZone) {
        return validate(rows, snapshot, defaultTimeZone, CompanySettings.DEFAULT_COUNTRY);
    }

    /**
     * @param defaultTimeZone the company's zone, applied to a row that left {@code timeZone} blank
     * @param defaultCountry the company's {@code defaultCountry} (migration V34), applied to a row
     *     that left {@code country} blank. Was the literal {@code "PE"} until then, which is right
     *     for the launch market and wrong for the second one
     */
    static Result validate(List<ImportRow> rows, MasterSnapshot snapshot, String defaultTimeZone,
            String defaultCountry) {
        List<ImportIssue> issues = new ArrayList<>();
        List<LocationImportCandidate> candidates = new ArrayList<>();
        Set<String> codesSeenInFile = new HashSet<>();

        for (ImportRow row : rows) {
            candidates.add(validateRow(row, snapshot, defaultTimeZone, defaultCountry, codesSeenInFile, issues));
        }
        return new Result(List.copyOf(candidates), List.copyOf(issues));
    }

    private static LocationImportCandidate validateRow(ImportRow row, MasterSnapshot snapshot,
            String defaultTimeZone, String defaultCountry, Set<String> codesSeenInFile, List<ImportIssue> issues) {
        int issuesBefore = issues.size();

        String rawCode = row.value(LocationImportColumn.CODE.header());
        String code = rawCode == null ? null : rawCode.trim().toUpperCase(Locale.ROOT);
        if (code == null) {
            issues.add(issue(row, LocationImportColumn.CODE, null, "A code is required."));
        } else if (!codesSeenInFile.add(code)) {
            issues.add(issue(row, LocationImportColumn.CODE, code,
                    "This code appears more than once in the file. Each row must describe a different location."));
        }

        String name = ImportValues.clean(row.value(LocationImportColumn.NAME.header()));
        if (name == null) {
            issues.add(issue(row, LocationImportColumn.NAME, code, "A name is required."));
        }

        LocationType type = convert(row, LocationImportColumn.TYPE, code, issues, raw -> ImportValues.enumeration(raw,
                LocationType.class,
                "WAREHOUSE, DISTRIBUTION_CENTER, PLANT, HUB, OTHER, CUSTOMER, STORE, BRANCH, DELIVERY_POINT"));
        if (!row.hasValue(LocationImportColumn.TYPE.header())) {
            issues.add(issue(row, LocationImportColumn.TYPE, code, "A type is required."));
        }

        Set<LocationRole> roles = readRoles(row, code, issues);

        String country = ImportValues.clean(row.value(LocationImportColumn.COUNTRY.header()));
        String timeZone = ImportValues.clean(row.value(LocationImportColumn.TIME_ZONE.header()));

        BigDecimal latitude = convert(row, LocationImportColumn.LATITUDE, code, issues, ImportValues::decimal);
        BigDecimal longitude = convert(row, LocationImportColumn.LONGITUDE, code, issues, ImportValues::decimal);
        if ((latitude == null) != (longitude == null)) {
            issues.add(issue(row, LocationImportColumn.LATITUDE, code,
                    "latitude and longitude must both be given, or both left blank."));
        }
        if (latitude != null && (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0)) {
            issues.add(issue(row, LocationImportColumn.LATITUDE, code, "latitude must be between -90 and 90."));
        }
        if (longitude != null && (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0)) {
            issues.add(issue(row, LocationImportColumn.LONGITUDE, code, "longitude must be between -180 and 180."));
        }

        UUID zoneId = null;
        String zoneCode = ImportValues.clean(row.value(LocationImportColumn.ZONE_CODE.header()));
        if (zoneCode != null) {
            zoneId = snapshot.zoneIdsByCode().get(zoneCode.toUpperCase(Locale.ROOT));
            if (zoneId == null) {
                issues.add(issue(row, LocationImportColumn.ZONE_CODE, code,
                        "'" + zoneCode + "' does not match a zone in this company."));
            }
        }

        Integer serviceTimeMinutes = convert(
                row, LocationImportColumn.SERVICE_TIME_MINUTES, code, issues, ImportValues::nonNegativeInteger);

        String externalSystem = ImportValues.clean(row.value(LocationImportColumn.EXTERNAL_SYSTEM.header()));
        String externalReference = ImportValues.clean(row.value(LocationImportColumn.EXTERNAL_REFERENCE.header()));
        if ((externalSystem == null) != (externalReference == null)) {
            issues.add(issue(row, LocationImportColumn.EXTERNAL_SYSTEM, code,
                    "externalSystem and externalReference must both be given, or both left blank."));
        }

        boolean rejected = issues.size() > issuesBefore;
        ImportOutcome outcome;
        if (rejected) {
            outcome = ImportOutcome.REJECTED;
        } else if (snapshot.existingCodes().contains(code)) {
            outcome = ImportOutcome.SKIPPED_DUPLICATE;
        } else {
            outcome = ImportOutcome.CREATE;
        }

        return new LocationImportCandidate(code, outcome, row.rowNumber(), name, type, roles,
                ImportValues.clean(row.value(LocationImportColumn.ADDRESS.header())),
                ImportValues.clean(row.value(LocationImportColumn.ADDRESS_REFERENCE.header())),
                ImportValues.clean(row.value(LocationImportColumn.DISTRICT.header())),
                ImportValues.clean(row.value(LocationImportColumn.PROVINCE.header())),
                ImportValues.clean(row.value(LocationImportColumn.DEPARTMENT.header())),
                country == null ? defaultCountry : country,
                timeZone == null ? defaultTimeZone : timeZone, latitude, longitude,
                zoneCode, zoneId, serviceTimeMinutes == null ? 0 : serviceTimeMinutes, externalSystem,
                externalReference);
    }

    private static Set<LocationRole> readRoles(ImportRow row, String code, List<ImportIssue> issues) {
        String raw = row.value(LocationImportColumn.ROLES.header());
        if (raw == null) {
            issues.add(issue(row, LocationImportColumn.ROLES, code, "At least one role is required."));
            return Set.of();
        }
        Set<LocationRole> roles = new LinkedHashSet<>();
        for (String token : raw.split("[,;]")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                roles.add(LocationRole.valueOf(trimmed.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                issues.add(issue(row, LocationImportColumn.ROLES, code, "'" + trimmed + "' is not recognised. Use "
                        + "ORIGIN, DESTINATION, or both separated by a comma."));
            }
        }
        if (roles.isEmpty() && raw.isBlank()) {
            issues.add(issue(row, LocationImportColumn.ROLES, code, "At least one role is required."));
        }
        return roles;
    }

    private static <T> T convert(ImportRow row, LocationImportColumn column, String code, List<ImportIssue> issues,
            Function<String, T> converter) {
        try {
            return converter.apply(row.value(column.header()));
        } catch (ImportValues.Rejected rejected) {
            issues.add(issue(row, column, code, rejected.getMessage()));
            return null;
        }
    }

    private static ImportIssue issue(ImportRow row, LocationImportColumn column, String code, String message) {
        return new ImportIssue(row.rowNumber(), column == null ? null : column.header(), code, message);
    }
}
