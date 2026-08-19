package com.ebim.tms.iam.infrastructure;

import com.ebim.tms.iam.domain.AppUserProfile;
import com.ebim.tms.iam.domain.CompanyPermissionRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the identity and tenancy facts behind an authenticated request.
 *
 * <p>Both methods are scoped by the caller's own {@code app_user} id and return nothing for
 * anyone else's data. There is deliberately no "find all memberships" or "find company by id"
 * method here: a repository that can answer an unscoped question is a repository a later
 * change can call by accident.
 */
public interface IdentityRepository {

    /** The active profile mapped to a Supabase auth user, or empty if none is (ADR-003). */
    Optional<AppUserProfile> findActiveProfileByAuthUserId(UUID authUserId);

    /**
     * Every (company, permission) pair the user holds through an <em>active</em> membership of
     * an <em>active</em> organization and company. Empty for a provisioned user with no
     * membership yet.
     */
    List<CompanyPermissionRow> findCompanyPermissions(UUID appUserId);
}
