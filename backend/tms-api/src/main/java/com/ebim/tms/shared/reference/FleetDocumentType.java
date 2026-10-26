package com.ebim.tms.shared.reference;

/**
 * The compliance papers TMS knows about (migration V72), each tied to one subject.
 *
 * <p>International names, because the paper is the same and only its local name changes: the
 * {@link #TECHNICAL_INSPECTION} is the RTV of the ANT in Ecuador and the CITV of the MTC in Peru;
 * {@link #MANDATORY_INSURANCE} is the SPPAT in Ecuador and the SOAT in Peru; the
 * {@link #OPERATING_PERMIT} is the ANT/MTOP permiso de operación or the MTC habilitación. What is
 * local is in the screen's labels and in the document's issuer, never in a code branch.
 *
 * <p>The defaults are what a company gets before it opens the policy screen: <b>nothing is
 * mandatory</b>, so a company that never records a document sees no new warning on any trip; and
 * the four legal papers of a vehicle <b>block</b> when they are on file and expired, because a truck
 * that may not legally drive is the one case a planner must not be able to talk past.
 */
public enum FleetDocumentType {

    REGISTRATION(FleetDocumentSubject.VEHICLE, ComplianceEnforcement.BLOCK, "registration"),
    TECHNICAL_INSPECTION(FleetDocumentSubject.VEHICLE, ComplianceEnforcement.BLOCK, "technical inspection"),
    MANDATORY_INSURANCE(FleetDocumentSubject.VEHICLE, ComplianceEnforcement.BLOCK, "mandatory insurance"),
    OPERATING_PERMIT(FleetDocumentSubject.VEHICLE, ComplianceEnforcement.BLOCK, "operating permit"),
    CARGO_INSURANCE(FleetDocumentSubject.VEHICLE, ComplianceEnforcement.WARN, "cargo insurance policy"),
    VEHICLE_OTHER(FleetDocumentSubject.VEHICLE, ComplianceEnforcement.WARN, "vehicle document"),
    MEDICAL_CERTIFICATE(FleetDocumentSubject.DRIVER, ComplianceEnforcement.WARN, "medical certificate"),
    DEFENSIVE_DRIVING_COURSE(FleetDocumentSubject.DRIVER, ComplianceEnforcement.WARN, "defensive driving course"),
    DRIVER_OTHER(FleetDocumentSubject.DRIVER, ComplianceEnforcement.WARN, "driver document");

    private final FleetDocumentSubject subject;
    private final ComplianceEnforcement defaultEnforcement;
    private final String label;

    FleetDocumentType(FleetDocumentSubject subject, ComplianceEnforcement defaultEnforcement, String label) {
        this.subject = subject;
        this.defaultEnforcement = defaultEnforcement;
        this.label = label;
    }

    public FleetDocumentSubject subject() {
        return subject;
    }

    /** Never mandatory by default - see the class comment. */
    public boolean defaultMandatory() {
        return false;
    }

    public ComplianceEnforcement defaultEnforcement() {
        return defaultEnforcement;
    }

    /** The English name an API message uses; the screen has its own, per language. */
    public String label() {
        return label;
    }
}
