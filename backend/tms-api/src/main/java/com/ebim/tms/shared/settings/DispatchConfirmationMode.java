package com.ebim.tms.shared.settings;

/**
 * Who may dispatch a trip in a company (ADR-013 section 1, migration V52).
 *
 * <ul>
 *   <li>{@link #MANUAL} - a person, as always. A warehouse system's dispatch is recorded and
 *       reconciled and never moves the trip. The default, so every existing company is unchanged.</li>
 *   <li>{@link #EXTERNAL_REQUIRED} - the warehouse system dispatches. A person needs
 *       {@code planning.trip:dispatch-override} and a reason.</li>
 *   <li>{@link #HYBRID} - whichever arrives first dispatches; the second only reconciles.</li>
 * </ul>
 */
public enum DispatchConfirmationMode {
    MANUAL,
    EXTERNAL_REQUIRED,
    HYBRID;

    /** Whether a dispatch confirmed by an external system may move the trip. */
    public boolean externalMayDispatch() {
        return this != MANUAL;
    }

    /** Whether a person may dispatch without an override. */
    public boolean manualMayDispatch() {
        return this != EXTERNAL_REQUIRED;
    }
}
