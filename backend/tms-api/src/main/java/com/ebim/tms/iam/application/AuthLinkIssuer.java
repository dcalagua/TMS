package com.ebim.tms.iam.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Issues one-time Auth links without sending anything: TMS sends the email itself, through MailHub
 * (ADR-031). {@code iam.infrastructure.SupabaseAuthLinkIssuer} is the only implementation.
 */
public interface AuthLinkIssuer {

    /** Whether the issuer is configured at all. */
    boolean isConfigured();

    /**
     * The link that, opened, signs the person in for a password change and lands on
     * {@code redirectTo}; empty when Auth has no account for {@code email}.
     *
     * @throws IllegalStateException when Auth could not be reached or refused for another reason
     */
    Optional<String> recoveryLink(String email, String redirectTo);

    /**
     * The link a new user opens to create their password, landing on {@code redirectTo}. Creates the
     * Auth account when there is none; when there already is one - invited before and never finished,
     * or created by hand - it is a recovery link to that same account. Either way the answer names the
     * account, so the caller can link it to the TMS profile.
     *
     * @throws IllegalStateException when Auth could not be reached or refused for another reason
     */
    Optional<IssuedLink> invitationLink(String email, String redirectTo);

    /** A link and the Auth account it belongs to. */
    record IssuedLink(String link, UUID authUserId) {
    }
}
