package com.ebim.tms.shared.security;

/**
 * A caller that is a system rather than a person: a partner integration credential, and in the
 * future a scheduled job.
 *
 * <p>Two things separate it from {@link TmsAuthenticationToken} and both matter to code in
 * {@code shared}:
 *
 * <ol>
 *   <li><b>There is no {@code app_user} behind it.</b> {@code created_by} / {@code updated_by}
 *       reference {@code tms.app_user}, and inventing a row for a machine would put a fake
 *       person in the audit trail. The columns stay null and the machine's identity is recorded
 *       where it is actually true: {@link #machineActorLabel()} in the log line, and the
 *       integration inbox row that names the credential and the correlation id.</li>
 *   <li><b>The company is not selectable.</b> It is a property of the credential, so there is no
 *       header to validate and no way for the caller to address another tenant.</li>
 * </ol>
 */
public interface MachineAuthentication extends CompanyScopedAuthentication {

    /**
     * A stable, human-readable, non-secret identifier of the machine caller - for example
     * {@code integration-client:tmsc_...}. Safe to log: it never contains secret material.
     */
    String machineActorLabel();
}
