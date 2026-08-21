package com.ebim.tms.masterdata.application.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.masterdata.domain.LocationType;
import com.ebim.tms.shared.imports.ImportOutcome;
import com.ebim.tms.shared.imports.ImportRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LocationImportValidator} as a pure function - no Spring context, no database. See
 * {@code OrderImportValidatorTest} for the pattern this follows.
 */
class LocationImportValidatorTest {

    private static final LocationImportValidator.MasterSnapshot EMPTY_COMPANY =
            new LocationImportValidator.MasterSnapshot(Map.of(), Set.of());

    private static ImportRow row(int rowNumber, Map<String, String> values) {
        return new ImportRow(rowNumber, values);
    }

    private static Map<String, String> baseValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(LocationImportColumn.CODE.header(), "DC-01");
        values.put(LocationImportColumn.NAME.header(), "Main DC");
        values.put(LocationImportColumn.TYPE.header(), "DISTRIBUTION_CENTER");
        values.put(LocationImportColumn.ROLES.header(), "ORIGIN");
        return values;
    }

    @Test
    @DisplayName("a minimal valid row becomes a creatable candidate with country/timeZone defaults filled in")
    void minimalValidRow() {
        var result = LocationImportValidator.validate(List.of(row(2, baseValues())), EMPTY_COMPANY, "America/Lima");

        assertThat(result.issues()).isEmpty();
        LocationImportCandidate candidate = result.candidates().get(0);
        assertThat(candidate.outcome()).isEqualTo(ImportOutcome.CREATE);
        assertThat(candidate.country()).isEqualTo("PE");
        assertThat(candidate.timeZone()).isEqualTo("America/Lima");
        assertThat(candidate.serviceTimeMinutes()).isZero();
        assertThat(candidate.type()).isEqualTo(LocationType.DISTRIBUTION_CENTER);
        assertThat(candidate.roles()).containsExactly(LocationRole.ORIGIN);
    }

    @Test
    @DisplayName("roles accepts more than one value separated by commas")
    void multipleRoles() {
        Map<String, String> values = baseValues();
        values.put(LocationImportColumn.ROLES.header(), "ORIGIN, SHIP_TO");
        var result = LocationImportValidator.validate(List.of(row(2, values)), EMPTY_COMPANY, "America/Lima");

        assertThat(result.candidates().get(0).roles()).containsExactlyInAnyOrder(LocationRole.ORIGIN, LocationRole.SHIP_TO);
    }

    @Test
    @DisplayName("a missing code, name, type or roles is rejected with a per-column issue")
    void requiredFieldsAreEnforced() {
        var result = LocationImportValidator.validate(List.of(row(2, Map.of())), EMPTY_COMPANY, "America/Lima");

        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.REJECTED);
        assertThat(result.issues()).extracting("column").containsExactlyInAnyOrder(
                LocationImportColumn.CODE.header(), LocationImportColumn.NAME.header(),
                LocationImportColumn.TYPE.header(), LocationImportColumn.ROLES.header());
    }

    @Test
    @DisplayName("a code already used by the company is skipped, not rejected")
    void existingCodeIsSkipped() {
        var snapshot = new LocationImportValidator.MasterSnapshot(Map.of(), Set.of("DC-01"));
        var result = LocationImportValidator.validate(List.of(row(2, baseValues())), snapshot, "America/Lima");

        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.SKIPPED_DUPLICATE);
    }

    @Test
    @DisplayName("a code repeated within the file is rejected on its second occurrence")
    void duplicateCodeWithinFileIsRejected() {
        var result = LocationImportValidator.validate(
                List.of(row(2, baseValues()), row(3, baseValues())), EMPTY_COMPANY, "America/Lima");

        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.CREATE);
        assertThat(result.candidates().get(1).outcome()).isEqualTo(ImportOutcome.REJECTED);
    }

    @Test
    @DisplayName("a zone code that does not resolve in the company is an issue")
    void unresolvedZoneCodeIsRejected() {
        Map<String, String> values = baseValues();
        values.put(LocationImportColumn.ZONE_CODE.header(), "NO-SUCH-ZONE");
        var result = LocationImportValidator.validate(List.of(row(2, values)), EMPTY_COMPANY, "America/Lima");

        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.REJECTED);
        assertThat(result.issues()).anySatisfy(
                issue -> assertThat(issue.message()).contains("NO-SUCH-ZONE"));
    }

    @Test
    @DisplayName("latitude without longitude, or a latitude out of range, is rejected")
    void coordinatePairAndRangeAreEnforced() {
        Map<String, String> onlyLatitude = baseValues();
        onlyLatitude.put(LocationImportColumn.LATITUDE.header(), "-12.05");
        assertThat(LocationImportValidator.validate(List.of(row(2, onlyLatitude)), EMPTY_COMPANY, "America/Lima")
                .candidates().get(0).outcome()).isEqualTo(ImportOutcome.REJECTED);

        Map<String, String> outOfRange = baseValues();
        outOfRange.put(LocationImportColumn.LATITUDE.header(), "-95");
        outOfRange.put(LocationImportColumn.LONGITUDE.header(), "-77");
        assertThat(LocationImportValidator.validate(List.of(row(2, outOfRange)), EMPTY_COMPANY, "America/Lima")
                .candidates().get(0).outcome()).isEqualTo(ImportOutcome.REJECTED);
    }

    @Test
    @DisplayName("externalSystem without externalReference, or vice versa, is rejected")
    void externalIdentityPairIsEnforced() {
        Map<String, String> values = baseValues();
        values.put(LocationImportColumn.EXTERNAL_SYSTEM.header(), "ERP");
        var result = LocationImportValidator.validate(List.of(row(2, values)), EMPTY_COMPANY, "America/Lima");

        assertThat(result.candidates().get(0).outcome()).isEqualTo(ImportOutcome.REJECTED);
    }
}
