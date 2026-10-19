package com.ebim.tms.shared.reference;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads back the codes and time zone of a provisioned tenant.
 *
 * <p>Separate from {@link TenantOnboardingPort} because it is a different job: one creates identity
 * rows, the other answers a question about them. Keeping the read here is what stops the platform
 * module from querying {@code tms.organization} and {@code tms.company} itself - those tables belong
 * to {@code iam}, and a module that reads another module's tables directly is a cycle waiting to be
 * written, whether or not the compiler can see it.
 */
public interface TenantIdentityLookupPort {

    /** Empty when either row is gone, which for a provisioned tenant means something is wrong. */
    Optional<TenantIdentity> find(UUID organizationId, UUID companyId);
}
