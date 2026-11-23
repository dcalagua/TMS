package com.ebim.tms.iam.application;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code tms.auth.*}: the emails of the account lifecycle, sent through MailHub (ADR-031) - the
 * password reset and the new user's invitation.
 *
 * @param passwordReset what the reset email says and where its link lands
 * @param invitation    what the invitation email says; its link lands where the reset's does
 * @param supabase      the project whose Auth issues the links
 */
@ConfigurationProperties(prefix = "tms.auth")
public record AuthEmailProperties(PasswordReset passwordReset, Invitation invitation, Supabase supabase) {

    public AuthEmailProperties {
        passwordReset = passwordReset == null ? new PasswordReset(null, null, null, null) : passwordReset;
        invitation = invitation == null ? new Invitation(null, null) : invitation;
        supabase = supabase == null ? new Supabase(null, null) : supabase;
    }

    /**
     * @param accessUrl    the {@code urlAcceso} the email names: where the person signs in from then on
     *                     (the frontend's root; a carrier user is taken to the portal from there)
     * @param linkValidity the {@code vigencia}: how long the invitation link lasts. It must say what
     *                     the project's Auth email OTP expiry actually is - TMS does not set it
     */
    public record Invitation(String accessUrl, String linkValidity) {

        public Invitation {
            accessUrl = accessUrl == null ? "" : accessUrl.trim();
            linkValidity = linkValidity == null || linkValidity.isBlank() ? "1 hora" : linkValidity.trim();
        }
    }

    /**
     * @param redirectUrl  where the link lands once Supabase accepts it: the frontend's
     *                     {@code /reset-password}. Must be in the project's Auth redirect allow-list
     * @param linkValidity the {@code vigencia} the email states, e.g. {@code 1 hora}. It must say what
     *                     the project's Auth email OTP expiry actually is - TMS does not set it
     * @param productName  the {@code producto} the email names
     * @param timeZone     the zone {@code solicitadoEn} is written in
     */
    public record PasswordReset(String redirectUrl, String linkValidity, String productName, String timeZone) {

        public PasswordReset {
            redirectUrl = redirectUrl == null ? "" : redirectUrl.trim();
            linkValidity = linkValidity == null || linkValidity.isBlank() ? "1 hora" : linkValidity.trim();
            productName = productName == null || productName.isBlank() ? "TMS" : productName.trim();
            timeZone = timeZone == null || timeZone.isBlank() ? "America/Lima" : timeZone.trim();
            ZoneId.of(timeZone);
        }

        public ZoneId zone() {
            return ZoneId.of(timeZone);
        }
    }

    /**
     * @param url        the project's URL, {@code https://<ref>.supabase.co}
     * @param serviceKey the project's {@code service_role} key: the Auth admin API needs it to issue a
     *                   recovery link without sending Supabase's own email. Never logged
     */
    public record Supabase(String url, String serviceKey) {

        public Supabase {
            url = url == null ? "" : url.trim();
            serviceKey = serviceKey == null ? "" : serviceKey.trim();
        }

        @Override
        public String toString() {
            return "Supabase[url=" + url + ", serviceKey=" + (serviceKey.isEmpty() ? "<empty>" : "<set>") + "]";
        }
    }

    /** Whether everything the reset needs on the TMS side is set. */
    public boolean configured() {
        return !passwordReset.redirectUrl().isEmpty() && !supabase.url().isEmpty()
                && !supabase.serviceKey().isEmpty();
    }

    /** Whether everything the invitation needs on the TMS side is set: the reset's, plus where to sign in. */
    public boolean invitationConfigured() {
        return configured() && !invitation.accessUrl().isEmpty();
    }
}
