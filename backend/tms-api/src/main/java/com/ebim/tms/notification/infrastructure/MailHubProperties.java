package com.ebim.tms.notification.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code tms.mailhub.*}: EBIM MailHub, the mail provider every email of TMS goes through (ADR-031).
 *
 * @param url     the {@code mailhub-send} function, e.g.
 *                {@code https://<ref>.supabase.co/functions/v1/mailhub-send}
 * @param token   the {@code X-MailHub-Key} credential. Blank turns email off. Read from the
 *                environment ({@code MAILHUB_TOKEN}); never written to a log or a response
 * @param region  sent as {@code x-region}
 * @param timeout how long one send may take
 */
@ConfigurationProperties(prefix = "tms.mailhub")
public record MailHubProperties(String url, String token, String region, Duration timeout) {

    public static final String DEFAULT_REGION = "us-east-1";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    public MailHubProperties {
        url = url == null ? "" : url.trim();
        token = token == null ? "" : token.trim();
        region = region == null || region.isBlank() ? DEFAULT_REGION : region.trim();
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
    }

    /** Whether both the endpoint and the token are set. Says nothing about whether the token is valid. */
    public boolean configured() {
        return !url.isEmpty() && !token.isEmpty();
    }

    /** Never prints the token. */
    @Override
    public String toString() {
        return "MailHubProperties[url=" + url + ", token=" + (token.isEmpty() ? "<empty>" : "<set>") + ", region="
                + region + ", timeout=" + timeout + "]";
    }
}
