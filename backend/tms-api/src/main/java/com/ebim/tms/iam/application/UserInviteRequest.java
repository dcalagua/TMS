package com.ebim.tms.iam.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Gives one person access to the company the request is scoped to.
 *
 * <p>Named "invite" rather than "create user" because it is not always a creation.
 * {@code tms.app_user} is installation-wide - one person may work for several organizations (V2
 * says so on the table) - so an email that already exists is attached rather than duplicated, and
 * {@code fullName} is then ignored: the profile belongs to that person, not to whichever
 * administrator typed their name second.
 *
 * <p>There is no password and no {@code authUserId}. TMS never holds a credential: the mapping to
 * Supabase Auth is established server-side at first sign-in ({@code tms.app_user.auth_user_id},
 * nullable exactly for this), which is why V2 could say "a profile can be created by an
 * administrator before the person has accepted an invitation".
 *
 * <p>{@code roleCodes} is required and must not be empty. A membership with no roles produces no
 * permission rows at all, so the company would not even appear in that person's company selector -
 * the access would look granted and be invisible.
 */
public record UserInviteRequest(
        @NotBlank(message = "is required")
        @Email(message = "must be an email address")
        @Size(max = 254, message = "must be at most 254 characters")
        String email,

        @NotBlank(message = "is required")
        @Size(max = 200, message = "must be at most 200 characters")
        String fullName,

        @NotEmpty(message = "at least one role is required")
        List<@NotBlank(message = "must not be blank") String> roleCodes) {
}
