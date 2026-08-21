package com.ebim.tms.iam.application;

import com.ebim.tms.iam.domain.AdministeredUserRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One person who can act in the company being administered, with the roles they hold there.
 *
 * <p>The identifier a caller acts on is {@code membershipId}, not {@code appUserId}: what an
 * administrator of one company grants and revokes is a membership, and addressing the endpoints by
 * the global user id would be a URL that means something different depending on which company
 * header accompanied it.
 *
 * <p>{@code appUserId} is present so the screen can recognise the signed-in administrator among the
 * rows and stop them from revoking their own access - the same guard
 * {@code UserAdministrationService} enforces server-side.
 *
 * @param organizationWide the membership reaches every company of the organization. Listed here
 *     because "who can act in this company" that omitted them would be false, and read-only here
 *     because revoking one from this screen would remove the person from the other companies too.
 * @param userActive {@code tms.app_user.active}, the installation-wide flag. Shown so an
 *     administrator can see why an account with an active membership still cannot sign in; never
 *     written from this screen, because it reaches organizations this caller does not administer.
 * @param roleCodes the role codes held in this membership, alphabetically. Codes and not names: the
 *     name is display text that a translation may change, and this is what the roles endpoint keys
 *     on.
 */
public record AdministeredUserView(
        UUID membershipId,
        UUID appUserId,
        String email,
        String fullName,
        boolean userActive,
        boolean membershipActive,
        boolean organizationWide,
        List<String> roleCodes,
        OffsetDateTime createdAt) {

    public static AdministeredUserView from(AdministeredUserRow row, List<String> roleCodes) {
        return new AdministeredUserView(
                row.membershipId(),
                row.appUserId(),
                row.email(),
                row.fullName(),
                row.userActive(),
                row.membershipActive(),
                row.organizationWide(),
                List.copyOf(roleCodes),
                row.createdAt());
    }
}
