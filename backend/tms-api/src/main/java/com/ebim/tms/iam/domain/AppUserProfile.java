package com.ebim.tms.iam.domain;

import java.util.UUID;

/**
 * The business profile behind a Supabase identity: {@code tms.app_user}, active rows only.
 *
 * @param authUserId the {@code auth.users.id} the profile is mapped to
 */
public record AppUserProfile(UUID id, UUID authUserId, String email, String fullName) {}
