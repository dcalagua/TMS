package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.WebhookSecretCipher;
import com.ebim.tms.integration.domain.WebhookSecrets;
import com.ebim.tms.integration.domain.WebhookSubscription;
import com.ebim.tms.shared.api.FeatureNotConfiguredException;
import org.springframework.stereotype.Component;

/**
 * The only thing in the application that holds the webhook encryption key (migration V35).
 *
 * <p>One bean, built once from {@code tms.integration.webhooks.secret-key}, so that every other
 * class asks it for a secret rather than carrying a cipher of its own. That is what makes "where can
 * a signing secret be decrypted" a question with one answer, and it is why {@code WebhookSubscription}
 * holds ciphertext and no key.
 *
 * <p>When the deployment has not configured a key the bean still exists and refuses every call with
 * {@link FeatureNotConfiguredException}, which the API turns into a 503 naming the missing
 * capability. The alternative - not registering the bean - would turn a configuration gap into a
 * startup failure for every deployment that does not use webhooks, and would make the two dozen
 * classes that touch this feature all conditional.
 */
@Component
public class WebhookSecretVault {

    private final WebhookSecretCipher cipher;

    public WebhookSecretVault(WebhookProperties properties) {
        this.cipher = properties.configured() ? buildCipher(properties.secretKey()) : null;
    }

    public boolean configured() {
        return cipher != null;
    }

    /** Generates a signing secret and returns it with the ciphertext and hint to persist. */
    public NewSecret issue() {
        String secret = WebhookSecrets.newSecret();
        return new NewSecret(secret, requireCipher().encrypt(secret), WebhookSecrets.hint(secret));
    }

    /**
     * The plaintext secret of one subscription, for the dispatcher that is about to sign with it.
     *
     * <p>The returned value must not be logged, stored, or put in a view - it is handed straight to
     * {@code WebhookSecrets.signatureHeader} and goes out of scope. The single call site is
     * {@code WebhookDeliveryQueue}, which copies it into a record that never leaves the dispatch
     * chain.
     */
    public String reveal(WebhookSubscription subscription) {
        return requireCipher().decrypt(subscription.secretCiphertext());
    }

    /** Refuses rather than pretends: a deployment with no key cannot hold a secret at all. */
    public void requireConfigured() {
        requireCipher();
    }

    private WebhookSecretCipher requireCipher() {
        if (cipher == null) {
            throw new FeatureNotConfiguredException(
                    "Webhook delivery is not configured in this deployment. An administrator must set a webhook "
                            + "signing key before subscriptions can be created.");
        }
        return cipher;
    }

    private static WebhookSecretCipher buildCipher(String keyMaterial) {
        try {
            return WebhookSecretCipher.of(keyMaterial);
        } catch (IllegalArgumentException tooWeak) {
            // Deliberately fatal at startup rather than degraded at runtime. A key that is present
            // but too short is a deployment that believes webhooks are configured and secured; the
            // honest answers are "no key" or "a good key", and this refuses the third state.
            throw new IllegalStateException(
                    "tms.integration.webhooks.secret-key is set but unusable: " + tooWeak.getMessage(), tooWeak);
        }
    }

    /** A freshly issued secret: the value to show once, the ciphertext to store, the hint to render. */
    public record NewSecret(String secret, String ciphertext, String hint) {
    }
}
