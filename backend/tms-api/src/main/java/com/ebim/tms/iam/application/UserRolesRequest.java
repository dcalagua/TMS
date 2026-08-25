package com.ebim.tms.iam.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * The complete set of roles one membership should hold, replacing whatever it holds now.
 *
 * <p>A whole set and not an add/remove pair, because that is how the screen presents it - a list of
 * checkboxes and a save - and because a partial API would let two administrators editing at once
 * end up with a union neither of them chose.
 *
 * <p>Empty is refused for the reason {@link UserInviteRequest} gives: a membership with no roles is
 * access that grants nothing and shows nothing. Revoking access is
 * {@code POST /admin/users/{id}/revoke}, which says so.
 */
public record UserRolesRequest(
        @NotEmpty(message = "at least one role is required")
        List<@NotBlank(message = "must not be blank") String> roleCodes) {
}
