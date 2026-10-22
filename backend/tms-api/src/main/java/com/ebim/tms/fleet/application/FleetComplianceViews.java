package com.ebim.tms.fleet.application;

import com.ebim.tms.shared.reference.ComplianceEnforcement;
import com.ebim.tms.shared.reference.ComplianceFinding;
import com.ebim.tms.shared.reference.FleetDocumentSubject;
import com.ebim.tms.shared.reference.FleetDocumentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The read and write shapes of the compliance screen beyond a single document (migration V72). */
public final class FleetComplianceViews {

    private FleetComplianceViews() {
    }

    /**
     * The whole fleet's papers at a glance, on the company's today.
     *
     * @param missingMandatory active vehicles and drivers without a document of a mandatory type
     * @param ageing           how the active documents spread over time, in a fixed bucket order
     */
    public record Summary(
            LocalDate today,
            int alertDays,
            long total,
            long valid,
            long expiringSoon,
            long expired,
            long unrecorded,
            long missingMandatory,
            List<AgeingBucket> ageing) {
    }

    /**
     * @param bucket one of EXPIRED_OVER_90, EXPIRED_31_90, EXPIRED_1_30, DUE_0_7, DUE_8_30, DUE_31_90,
     *               DUE_OVER_90, NO_DATE - stable codes a screen translates
     */
    public record AgeingBucket(String bucket, long count) {
    }

    /** An active vehicle or driver without any document of a mandatory type. */
    public record MissingDocument(
            FleetDocumentSubject subjectType,
            UUID subjectId,
            String subjectCode,
            String subjectName,
            FleetDocumentType documentType,
            ComplianceEnforcement enforcement) {
    }

    /** One vehicle's or one driver's file: every document, active or not, and what is wrong today. */
    public record SubjectCompliance(
            FleetDocumentSubject subjectType,
            UUID subjectId,
            LocalDate today,
            int alertDays,
            List<FleetDocumentView> documents,
            List<ComplianceFinding> findings) {
    }

    /** The company's policy, with the default beside each type so a screen can say what it changed. */
    public record Policy(int alertDays, List<TypePolicy> types) {
    }

    public record TypePolicy(
            FleetDocumentType documentType,
            FleetDocumentSubject subjectType,
            boolean mandatory,
            ComplianceEnforcement enforcement,
            boolean defaultMandatory,
            ComplianceEnforcement defaultEnforcement,
            boolean configured) {
    }

    /** Saved whole: the alert window and every type the screen sends; a type left out keeps its row. */
    public record PolicyRequest(
            @NotNull @Min(1) @Max(365) Integer alertDays,
            @NotNull List<@Valid TypePolicyRequest> types) {
    }

    public record TypePolicyRequest(
            @NotNull FleetDocumentType documentType,
            boolean mandatory,
            @NotNull ComplianceEnforcement enforcement) {
    }
}
