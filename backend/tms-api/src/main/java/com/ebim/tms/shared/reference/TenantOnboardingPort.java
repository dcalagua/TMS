package com.ebim.tms.shared.reference;

/**
 * Creates the identity rows a new tenant is made of: an organization, its first company, and
 * optionally the administrator who will run it.
 *
 * <p>This port exists because {@code com.ebim.tms.platform} owns the control-plane API but must not
 * own the identity tables. {@code iam} does, and it keeps every rule about them - the shape of a
 * code, what makes an email acceptable, which role an administrator is given and why a membership
 * has to be organization-wide for that role to count. The platform module asks; it does not reach
 * in. {@code ModuleBoundaryTest} is what keeps that true.
 *
 * <p>The implementation runs inside the caller's transaction on purpose. Provisioning is
 * all-or-nothing: an organization with no company, or a company with no administrator, is not a
 * partially provisioned tenant but a broken one, and the only way to guarantee that is for these
 * writes to share the transaction that records them.
 */
public interface TenantOnboardingPort {

    /**
     * @throws TenantOnboardingRefusedException when the request cannot be honoured - an identifier
     *     already taken, an administrator email that belongs to somebody else. The reason is typed
     *     so the caller can answer with the right problem code instead of guessing from a message
     */
    TenantOnboarding onboard(TenantOnboardingCommand command);
}
