package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.WebhookSecrets;

/**
 * The one and only response that ever contains a signing secret: what {@code create} and
 * {@code rotate-secret} return.
 *
 * <p>There is no endpoint that reads it back and no recovery path. The value is held encrypted so
 * that TMS can sign with it - see {@code WebhookSecretCipher} for why that differs from every other
 * secret in the product - but it is never decrypted into a response. An administrator who closes
 * this without copying it rotates and configures the new one.
 *
 * <p>{@code signatureHeader} and {@code signedPayloadFormat} travel with it because this is the
 * moment somebody is about to go and write the verification code on the other side, and a secret
 * handed over without saying what to do with it is a secret nobody checks.
 */
public record WebhookSubscriptionSecretView(
        WebhookSubscriptionView subscription,
        String secret,
        String signatureHeader,
        String signedPayloadFormat,
        String notice) {

    static final String SIGNATURE_HEADER = "X-TMS-Signature";

    static final String SIGNED_PAYLOAD_FORMAT =
            "t=<unix seconds>," + WebhookSecrets.SIGNATURE_VERSION + "=<hex HMAC-SHA256 of \"<t>.<raw body>\">";

    static final String NOTICE =
            "This signing secret is shown once and cannot be recovered. Store it in your secret manager now, "
                    + "verify every delivery's signature with it, and rotate if it is exposed. Rotation takes "
                    + "effect immediately and without a grace window, so accept both secrets on your side while "
                    + "you redeploy.";

    public static WebhookSubscriptionSecretView of(WebhookSubscriptionView subscription, String secret) {
        return new WebhookSubscriptionSecretView(
                subscription, secret, SIGNATURE_HEADER, SIGNED_PAYLOAD_FORMAT, NOTICE);
    }
}
