package com.ebim.tms.ai.infrastructure;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code tms.ai.anthropic.*} (ADR-030). Every value has a default here as well as in
 * {@code application.yml}, so a deployment that forgets the block gets an assistant that is off,
 * not one that is half-configured.
 *
 * @param apiKey     the Anthropic API key. Blank turns every assistant off. Read from the
 *                   environment ({@code ANTHROPIC_API_KEY}); never written to a log or a response
 * @param model      the model id
 * @param effort     how hard the model thinks: low, medium, high, xhigh or max
 * @param timeout    how long one attempt may take before it is abandoned
 * @param maxRetries how many times a failed attempt is retried (429, 5xx, timeouts); the worst
 *                   wait is {@code timeout x (maxRetries + 1)}
 * @param maxTokens  the cap on one answer, thinking included
 */
@ConfigurationProperties(prefix = "tms.ai.anthropic")
public record AnthropicProperties(
        String apiKey,
        String model,
        String effort,
        Duration timeout,
        Integer maxRetries,
        Long maxTokens) {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    public static final String DEFAULT_EFFORT = "medium";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(90);
    public static final int DEFAULT_MAX_RETRIES = 1;
    public static final long DEFAULT_MAX_TOKENS = 16_000L;

    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

    public AnthropicProperties {
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        effort = effort == null || effort.isBlank() ? DEFAULT_EFFORT : effort.trim().toLowerCase(Locale.ROOT);
        if (!EFFORTS.contains(effort)) {
            throw new IllegalArgumentException("tms.ai.anthropic.effort must be one of " + EFFORTS + ", was: " + effort);
        }
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
        maxRetries = maxRetries == null || maxRetries < 0 ? DEFAULT_MAX_RETRIES : maxRetries;
        maxTokens = maxTokens == null || maxTokens < 1_024L ? DEFAULT_MAX_TOKENS : maxTokens;
    }

    /** Whether a key is configured at all. Says nothing about whether the key is valid. */
    public boolean configured() {
        return !apiKey.isEmpty();
    }

    /** Never prints the key: a properties object ends up in a log sooner or later. */
    @Override
    public String toString() {
        return "AnthropicProperties[apiKey=" + (configured() ? "<set>" : "<empty>") + ", model=" + model
                + ", effort=" + effort + ", timeout=" + timeout + ", maxRetries=" + maxRetries
                + ", maxTokens=" + maxTokens + "]";
    }
}
