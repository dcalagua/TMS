package com.ebim.tms.shared.reference;

/**
 * {@code iam} refused to create the tenant, for a reason the caller can act on.
 *
 * <p>Typed rather than a message, because the caller has to turn it into a problem code an
 * integrator reads from a machine. A refusal is never a failure of TMS: it means the request
 * described something that already belongs to somebody else, and the right answer is 409 with the
 * reason named, not 500 with a stack trace.
 */
public class TenantOnboardingRefusedException extends RuntimeException {

    /** Why the tenant could not be created. */
    public enum Reason {

        /** The organization id, or its code, is already in use. */
        ORGANIZATION_TAKEN,

        /** The company id, or its code inside that organization, is already in use. */
        COMPANY_TAKEN,

        /**
         * A TMS user already holds that email and already belongs somewhere else. Provisioning does
         * not move people between tenants: that is an administrative decision with consequences for
         * the data they can already see, and it needs a person.
         */
        ADMIN_EMAIL_TAKEN,

        /** The administrator role this product provisions is missing from the role catalogue. */
        ADMIN_ROLE_MISSING
    }

    private final transient Reason reason;

    public TenantOnboardingRefusedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
