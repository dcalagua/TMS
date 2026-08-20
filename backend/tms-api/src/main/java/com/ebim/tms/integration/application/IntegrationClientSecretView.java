package com.ebim.tms.integration.application;

import java.time.OffsetDateTime;

/**
 * The one and only response that ever contains a secret: what {@code create} and {@code rotate}
 * return.
 *
 * <p>There is no endpoint that reads it back, no field on {@link IntegrationClientView} that
 * exposes it, and no recovery path. An administrator who closes this response without copying the
 * value rotates and gets a new one - which is a minor inconvenience, and exactly the property
 * that makes a database dump worthless.
 *
 * <p>{@code bearerToken} is the ready-to-paste value a partner configures, so nobody has to be
 * told "concatenate these two with a full stop" and get it wrong.
 *
 * @param previousSecretValidUntil on a rotation, when the superseded secret stops being accepted;
 *     {@code null} on creation, and on a rotation that revoked the old secret immediately
 */
public record IntegrationClientSecretView(
        IntegrationClientView client,
        String clientId,
        String secret,
        String bearerToken,
        OffsetDateTime previousSecretValidUntil,
        String notice) {

    static final String NOTICE =
            "This secret is shown once and cannot be recovered. Store it in your secret manager now. "
                    + "If it is lost or exposed, rotate the credential.";

    public static IntegrationClientSecretView of(IntegrationClientView client, String clientId, String secret,
            String bearerToken, OffsetDateTime previousSecretValidUntil) {
        return new IntegrationClientSecretView(client, clientId, secret, bearerToken, previousSecretValidUntil, NOTICE);
    }
}
