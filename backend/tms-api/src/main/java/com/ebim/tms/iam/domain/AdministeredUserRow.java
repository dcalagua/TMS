package com.ebim.tms.iam.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One person who can act in the company being administered, as {@code tms.membership} joined to
 * {@code tms.app_user} yields them.
 *
 * <p>The identity here is the <em>membership</em>, not the user: {@code tms.app_user} is global -
 * one person may work for several organizations (V2's comment on the table says so) - and what an
 * administrator of one company grants, revokes or reads is that person's membership in that
 * company. {@code appUserId} travels alongside because the profile fields (name, email) hang off
 * it, and because the service has to be able to recognise the caller administering themselves.
 *
 * @param organizationWide {@code true} when {@code membership.company_id IS NULL} - an
 *     organization-wide membership that reaches this company and every other company of the
 *     organization. Listed, because "who can act here" that omitted them would be a lie; but not
 *     editable from a company screen, because deactivating one from company A would remove that
 *     person from companies B and C as well. {@code UserAdministrationService} refuses every write
 *     against one.
 * @param userActive {@code tms.app_user.active}, which is global. A person deactivated there is
 *     locked out of the whole installation, so the screen shows it and never writes it: revoking
 *     access to <em>this</em> company is what {@code membershipActive} is for.
 */
public record AdministeredUserRow(
        UUID membershipId,
        UUID appUserId,
        String email,
        String fullName,
        boolean userActive,
        boolean membershipActive,
        boolean organizationWide,
        OffsetDateTime createdAt) {}
