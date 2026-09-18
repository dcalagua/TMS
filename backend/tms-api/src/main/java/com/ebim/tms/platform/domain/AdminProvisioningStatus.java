package com.ebim.tms.platform.domain;

/**
 * What happened to the administrator the control plane asked for.
 *
 * <p>TMS never invents a password and never creates a Supabase identity on somebody's behalf.
 * {@code tms.app_user.auth_user_id} is nullable exactly so a user row can exist before the person
 * has signed in, which is what makes {@link #PREPROVISIONED} possible: the administrator, the
 * organization-wide membership and the {@code ORGANIZATION_ADMIN} role are all in place, and the
 * Supabase identity is linked on first sign-in.
 *
 * <p>Mirrored by {@code ck_platform_provisioning_admin_status} (V51).
 */
public enum AdminProvisioningStatus {

    /** A TMS user row was created with no {@code auth_user_id}; the person signs in to claim it. */
    PREPROVISIONED,

    /**
     * A TMS user with that email already existed and already carried a Supabase identity, and it
     * was granted the administrator membership instead of creating a second row. Reached only when
     * the existing user belongs to no other organization - otherwise the request is refused with
     * {@code ADMIN_EMAIL_CONFLICT}, because moving somebody between tenants is not provisioning.
     */
    LINKED,

    /** The request carried no administrator. The tenant exists; nobody can sign in to it yet. */
    NOT_REQUESTED
}
