package com.ebim.tms.iam.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corrects the display name of somebody who works in this company.
 *
 * <p>The name only. The email is the account's identity - it is what the Supabase sign-in resolves
 * to a profile - so changing it from here would be an account takeover with a typo's blast radius;
 * a person whose email changes gets a new invitation and their old membership revoked.
 *
 * <p>The consequence of editing a global row from a company screen is stated rather than hidden:
 * {@code tms.app_user} is installation-wide, so a person who also works for another organization
 * is renamed there too. That is a property of one person having one profile, and the alternative -
 * a per-company display name - would mean the same human appearing under two names on two
 * manifests.
 */
public record UserProfileRequest(
        @NotBlank(message = "is required")
        @Size(max = 200, message = "must be at most 200 characters")
        String fullName) {
}
