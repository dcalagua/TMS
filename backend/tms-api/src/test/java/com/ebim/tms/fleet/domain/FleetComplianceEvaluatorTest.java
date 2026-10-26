package com.ebim.tms.fleet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.fleet.domain.FleetComplianceEvaluator.Rule;
import com.ebim.tms.shared.reference.ComplianceEnforcement;
import com.ebim.tms.shared.reference.ComplianceFinding;
import com.ebim.tms.shared.reference.FleetDocumentStatus;
import com.ebim.tms.shared.reference.FleetDocumentSubject;
import com.ebim.tms.shared.reference.FleetDocumentType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FleetComplianceEvaluatorTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRUCK = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 10, 26);

    private static FleetDocument paper(FleetDocumentType type, LocalDate expiresOn) {
        return new FleetDocument(COMPANY, type.subject(), TRUCK, type, "N-1", "ANT", null, expiresOn, null, null);
    }

    private static List<ComplianceFinding> evaluate(List<FleetDocument> documents, Map<FleetDocumentType, Rule> rules) {
        return FleetComplianceEvaluator.evaluate(FleetDocumentSubject.VEHICLE, TRUCK, "GBA-1234", documents, rules,
                DAY, 30);
    }

    @Test
    @DisplayName("no papers and no policy: nothing to say - the behaviour before V72")
    void nothingConfigured() {
        assertThat(evaluate(List.of(), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("an expired technical inspection blocks by default and is named with its expiry and plate")
    void expiredBlocksByDefault() {
        List<ComplianceFinding> findings =
                evaluate(List.of(paper(FleetDocumentType.TECHNICAL_INSPECTION, DAY.minusDays(1))), Map.of());
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.status()).isEqualTo(FleetDocumentStatus.EXPIRED);
            assertThat(finding.blocking()).isTrue();
            assertThat(finding.describe()).contains("GBA-1234", "technical inspection", "2026-10-25");
        });
    }

    @Test
    @DisplayName("an expired paper whose policy says WARN is a warning")
    void expiredWarns() {
        List<ComplianceFinding> findings = evaluate(
                List.of(paper(FleetDocumentType.TECHNICAL_INSPECTION, DAY.minusDays(1))),
                Map.of(FleetDocumentType.TECHNICAL_INSPECTION, new Rule(true, ComplianceEnforcement.WARN)));
        assertThat(findings).singleElement().satisfies(finding -> assertThat(finding.blocking()).isFalse());
    }

    @Test
    @DisplayName("a paper that expires on the day is still valid on the day")
    void expiryDayIsValid() {
        assertThat(evaluate(List.of(paper(FleetDocumentType.MANDATORY_INSURANCE, DAY)), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("a renewal wins: one valid paper of a type clears the expired one beside it")
    void renewalWins() {
        assertThat(evaluate(List.of(paper(FleetDocumentType.REGISTRATION, DAY.minusYears(1)),
                paper(FleetDocumentType.REGISTRATION, DAY.plusYears(1))), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("a mandatory paper missing, or on file without a date, warns and never blocks")
    void unknownNeverBlocks() {
        Map<FleetDocumentType, Rule> rules = Map.of(
                FleetDocumentType.REGISTRATION, new Rule(true, ComplianceEnforcement.BLOCK),
                FleetDocumentType.OPERATING_PERMIT, new Rule(true, ComplianceEnforcement.BLOCK));
        List<ComplianceFinding> findings = evaluate(List.of(paper(FleetDocumentType.OPERATING_PERMIT, null)), rules);
        assertThat(findings).extracting(ComplianceFinding::documentType)
                .containsExactly(FleetDocumentType.REGISTRATION, FleetDocumentType.OPERATING_PERMIT);
        assertThat(findings).allSatisfy(finding -> {
            assertThat(finding.status()).isEqualTo(FleetDocumentStatus.UNRECORDED);
            assertThat(finding.blocking()).isFalse();
        });
        assertThat(findings.get(0).describe()).contains("is not on file");
        assertThat(findings.get(1).describe()).contains("no expiry date");
    }

    @Test
    @DisplayName("an undated paper of an optional type says nothing")
    void optionalUndatedIsSilent() {
        assertThat(evaluate(List.of(paper(FleetDocumentType.CARGO_INSURANCE, null)), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("a deactivated paper does not count, neither for nor against")
    void deactivatedIgnored() {
        FleetDocument expired = paper(FleetDocumentType.TECHNICAL_INSPECTION, DAY.minusDays(1));
        expired.deactivate(null);
        assertThat(evaluate(List.of(expired), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("a driver's paper cannot be built for a vehicle")
    void subjectMismatch() {
        assertThatThrownBy(() -> new FleetDocument(COMPANY, FleetDocumentSubject.VEHICLE, TRUCK,
                FleetDocumentType.MEDICAL_CERTIFICATE, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
