package com.ebim.tms.shared.reference;

import java.util.UUID;

/**
 * What {@code iam} created.
 *
 * @param adminAppUserId null when the command carried no administrator
 * @param adminLinkedToExistingUser true when a TMS user with that email already existed and was
 *     given the membership, instead of a new row being created. It is the difference between
 *     {@code LINKED} and {@code PREPROVISIONED} in the response, and an operator reading the
 *     provisioning record needs it: one of them means somebody can already sign in
 */
public record TenantOnboarding(
        UUID organizationId,
        UUID companyId,
        UUID adminAppUserId,
        boolean adminLinkedToExistingUser) {
}
