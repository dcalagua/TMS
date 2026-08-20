package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.shared.imports.ImportIssue;
import com.ebim.tms.shared.imports.ImportOutcome;
import com.ebim.tms.shared.imports.ImportRow;
import com.ebim.tms.shared.imports.ImportValues;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The whole of the Carrier import's business validation, as a pure function over already-parsed
 * rows and an already-resolved snapshot of the codes they refer to. See
 * {@code LocationImportValidator} for the pattern this follows.
 *
 * <p>A carrier's {@code (taxIdType, taxIdValue)} pair is also unique per company
 * ({@code uq_carrier_company_tax_id}), but unlike {@code code} this validator checks it only
 * within the file itself, not against the company's existing carriers: a bulk-scoped lookup for
 * every distinct pair would be a second query shape this feature does not otherwise need, and an
 * existing-pair collision is still caught - as a 409, not a per-row issue - by the same unique
 * index {@code CarrierService.create} itself relies on as its race backstop.
 */
final class CarrierImportValidator {

    private static final Pattern EMAIL_SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+[.][^@\\s]+$");

    record MasterSnapshot(Set<String> existingCodes) {
    }

    record Result(List<CarrierImportCandidate> candidates, List<ImportIssue> issues) {
    }

    private CarrierImportValidator() {}

    static Result validate(List<ImportRow> rows, MasterSnapshot snapshot) {
        List<ImportIssue> issues = new ArrayList<>();
        List<CarrierImportCandidate> candidates = new ArrayList<>();
        Set<String> codesSeenInFile = new HashSet<>();
        Set<String> taxIdsSeenInFile = new HashSet<>();

        for (ImportRow row : rows) {
            candidates.add(validateRow(row, snapshot, codesSeenInFile, taxIdsSeenInFile, issues));
        }
        return new Result(List.copyOf(candidates), List.copyOf(issues));
    }

    private static CarrierImportCandidate validateRow(ImportRow row, MasterSnapshot snapshot,
            Set<String> codesSeenInFile, Set<String> taxIdsSeenInFile, List<ImportIssue> issues) {
        int issuesBefore = issues.size();

        String rawCode = row.value(CarrierImportColumn.CODE.header());
        String code = rawCode == null ? null : rawCode.trim().toUpperCase(Locale.ROOT);
        if (code == null) {
            issues.add(issue(row, CarrierImportColumn.CODE, null, "A code is required."));
        } else if (!codesSeenInFile.add(code)) {
            issues.add(issue(row, CarrierImportColumn.CODE, code,
                    "This code appears more than once in the file. Each row must describe a different carrier."));
        }

        String businessName = ImportValues.clean(row.value(CarrierImportColumn.BUSINESS_NAME.header()));
        if (businessName == null) {
            issues.add(issue(row, CarrierImportColumn.BUSINESS_NAME, code, "A business name is required."));
        }

        String taxIdType = ImportValues.clean(row.value(CarrierImportColumn.TAX_ID_TYPE.header()));
        String taxIdValue = ImportValues.clean(row.value(CarrierImportColumn.TAX_ID_VALUE.header()));
        if (taxIdType == null) {
            issues.add(issue(row, CarrierImportColumn.TAX_ID_TYPE, code, "A tax id type is required."));
        }
        if (taxIdValue == null) {
            issues.add(issue(row, CarrierImportColumn.TAX_ID_VALUE, code, "A tax id value is required."));
        }
        String normalizedTaxIdType = taxIdType == null ? null : taxIdType.toUpperCase(Locale.ROOT);
        String normalizedTaxIdValue = taxIdValue == null ? null : taxIdValue.toUpperCase(Locale.ROOT);
        if (normalizedTaxIdType != null && normalizedTaxIdValue != null) {
            String pairKey = normalizedTaxIdType + "|" + normalizedTaxIdValue;
            if (!taxIdsSeenInFile.add(pairKey)) {
                issues.add(issue(row, CarrierImportColumn.TAX_ID_VALUE, code, "This tax id (" + normalizedTaxIdType
                        + " " + normalizedTaxIdValue + ") appears more than once in the file."));
            }
        }

        String email = ImportValues.clean(row.value(CarrierImportColumn.EMAIL.header()));
        String normalizedEmail = email == null ? null : email.toLowerCase(Locale.ROOT);
        if (normalizedEmail != null && !EMAIL_SHAPE.matcher(normalizedEmail).matches()) {
            issues.add(issue(row, CarrierImportColumn.EMAIL, code, "'" + email + "' does not look like an email address."));
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

        return new CarrierImportCandidate(code, outcome, row.rowNumber(), businessName, normalizedTaxIdType,
                normalizedTaxIdValue, ImportValues.clean(row.value(CarrierImportColumn.CONTACT_NAME.header())),
                ImportValues.clean(row.value(CarrierImportColumn.PHONE.header())), normalizedEmail,
                ImportValues.clean(row.value(CarrierImportColumn.EXTERNAL_REFERENCE.header())));
    }

    private static ImportIssue issue(ImportRow row, CarrierImportColumn column, String code, String message) {
        return new ImportIssue(row.rowNumber(), column == null ? null : column.header(), code, message);
    }
}
