package com.ebim.tms.shared.reference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends an email built from a template the mail provider holds (ADR-031). The caller names the
 * template and gives its variables; the provider owns the layout, the sender and the delivery.
 * {@code notification.infrastructure.MailHubEmailSender} is the only implementation.
 *
 * <p>Not transactional, and not to be called inside a transaction that may still roll back: an
 * email cannot be unsent. The first caller, the password reset, sends from a background task after
 * every read it needed is done.
 */
public interface TemplateEmailPort {

    /** Whether this deployment can send at all - false when no provider token is configured. */
    boolean isAvailable();

    /**
     * Sends one email.
     *
     * @throws IllegalStateException when the provider refused it or could not be reached; the
     *     message never contains the provider's token
     */
    void send(TemplateEmail email);

    /**
     * @param to             the recipients' addresses
     * @param templateKey    the provider's template, e.g. {@code restablecer-contrasena}
     * @param variables      the template's variables, already formatted as text
     * @param referenceId    what the email is about, for tracing it on the provider's side
     * @param idempotencyKey the provider sends one email per key: a retry with the same key is not
     *                       a second email
     */
    record TemplateEmail(List<String> to, String templateKey, Map<String, String> variables, String referenceId,
            String idempotencyKey) {

        public TemplateEmail {
            to = List.copyOf(to);
            variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        }
    }
}
