package com.ebim.tms.fleet.domain;

import com.ebim.tms.shared.reference.ComplianceEnforcement;
import com.ebim.tms.shared.reference.ComplianceFinding;
import com.ebim.tms.shared.reference.FleetDocumentStatus;
import com.ebim.tms.shared.reference.FleetDocumentSubject;
import com.ebim.tms.shared.reference.FleetDocumentType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whether one vehicle's or one driver's papers allow them to work on a day (migration V72).
 *
 * <p><b>A pure function</b>, like {@link WorkSequenceValidator}: no repository, no clock. The day,
 * the documents on file and the company's policy are all parameters, so every case is a line in a
 * table test.
 *
 * <h2>The rules</h2>
 *
 * <p>Per document type of the subject, the documents of that type on file are read as one piece of
 * evidence - the <em>best</em> one wins, because a renewal is a second paper and the expired first
 * one must not keep flagging a truck that has a valid one:
 *
 * <ol>
 *   <li>any document valid on the day: no finding (about to expire is still valid);</li>
 *   <li>otherwise any document without an expiry date: unrecorded - a warning when the type is
 *       mandatory, nothing when it is not;</li>
 *   <li>otherwise the most recent expiry has passed: expired - blocking when the type's
 *       enforcement is {@link ComplianceEnforcement#BLOCK}, a warning otherwise, mandatory or not;</li>
 *   <li>no document at all: a warning when the type is mandatory, nothing when it is not.</li>
 * </ol>
 *
 * <p>Only an expiry that has passed can block. Not knowing is not expired (V26's licence rule).
 */
public final class FleetComplianceEvaluator {

    private FleetComplianceEvaluator() {
    }

    /** A company's decision about one type, already resolved against the type's defaults. */
    public record Rule(boolean mandatory, ComplianceEnforcement enforcement) {

        public static Rule defaultOf(FleetDocumentType type) {
            return new Rule(type.defaultMandatory(), type.defaultEnforcement());
        }
    }

    /**
     * @param documents the subject's ACTIVE documents
     * @param rules     the company's rules by type; a missing type follows its default
     * @return the findings in the order of {@link FleetDocumentType}, so a message is stable
     */
    public static List<ComplianceFinding> evaluate(FleetDocumentSubject subject, UUID subjectId, String subjectCode,
            List<FleetDocument> documents, Map<FleetDocumentType, Rule> rules, LocalDate on, int alertDays) {
        List<ComplianceFinding> findings = new ArrayList<>();
        for (FleetDocumentType type : FleetDocumentType.values()) {
            if (type.subject() != subject) {
                continue;
            }
            Rule rule = rules.getOrDefault(type, Rule.defaultOf(type));
            List<FleetDocument> ofType = documents.stream()
                    .filter(FleetDocument::active)
                    .filter(document -> document.documentType() == type)
                    .toList();
            if (ofType.isEmpty()) {
                if (rule.mandatory()) {
                    findings.add(new ComplianceFinding(subject, subjectId, subjectCode, type,
                            FleetDocumentStatus.UNRECORDED, null, null, null, false, false));
                }
                continue;
            }
            boolean anyValid = ofType.stream()
                    .anyMatch(document -> document.expiresOn() != null && !document.expiresOn().isBefore(on));
            if (anyValid) {
                continue;
            }
            FleetDocument undated = ofType.stream().filter(document -> document.expiresOn() == null)
                    .findFirst().orElse(null);
            if (undated != null) {
                if (rule.mandatory()) {
                    findings.add(new ComplianceFinding(subject, subjectId, subjectCode, type,
                            FleetDocumentStatus.UNRECORDED, null, undated.id(), undated.documentNumber(), true, false));
                }
                continue;
            }
            FleetDocument latest = ofType.stream()
                    .filter(document -> document.expiresOn() != null)
                    .max(Comparator.comparing(FleetDocument::expiresOn))
                    .orElseThrow();
            findings.add(new ComplianceFinding(subject, subjectId, subjectCode, type, FleetDocumentStatus.EXPIRED,
                    latest.expiresOn(), latest.id(), latest.documentNumber(), true,
                    rule.enforcement() == ComplianceEnforcement.BLOCK));
        }
        return List.copyOf(findings);
    }
}
