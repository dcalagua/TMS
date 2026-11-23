package com.ebim.tms.ai.infrastructure;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.StructuredTextBlock;
import com.ebim.tms.shared.api.FeatureNotConfiguredException;
import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.api.ProblemTypedException;
import com.ebim.tms.shared.reference.GeoPoint;
import com.ebim.tms.shared.reference.StopSequenceAdvisorPort;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The only implementation of {@link StopSequenceAdvisorPort}: one call to Claude with structured
 * output (ADR-030).
 *
 * <h2>Why one call and not an agent loop</h2>
 *
 * The whole problem fits in one message - a dozen or two points and their distance matrix - and
 * the answer is one permutation. A tool loop would add turns, latency and cost to reach the same
 * place. The model reasons over the matrix with adaptive thinking; the caller then measures the
 * proposal against the current order with the same matrix, so a weak answer is visible as one.
 *
 * <h2>Why the matrix and not just the coordinates</h2>
 *
 * The matrix is what {@code RoutingPort} would charge the trip with - road estimates, possibly
 * asymmetric. Coordinates alone would invite the model to optimise straight lines, and the totals
 * the planner sees would then disagree with the distances the rest of TMS uses.
 *
 * <h2>Refusals</h2>
 *
 * The request opts into server-side fallbacks ({@code fallbacks: "default"}): if a safety
 * classifier declines it, the API retries on a model it picks for that category. A question made of
 * numbers should never be declined; this is insurance, and a refusal that still comes back is
 * reported as {@code ai-unavailable} like any other unusable answer.
 */
@Component
@EnableConfigurationProperties(AnthropicProperties.class)
public class AnthropicStopSequenceAdvisor implements StopSequenceAdvisorPort {

    private static final Logger log = LoggerFactory.getLogger(AnthropicStopSequenceAdvisor.class);

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    /** Fixed text: a byte that varies per request here would defeat prompt caching for no reason. */
    static final String SYSTEM_PROMPT = """
            You sequence the stops of a delivery route for a transport management system.

            A vehicle leaves the origin (point 0) and visits every stop exactly once. The route ends at \
            the last stop: the drive back to the origin is not part of it. Choose the visiting order \
            that makes the total driving distance as short as you can.

            Judge distance with the matrix "distanceKm": road estimates in kilometres, row = from, \
            column = to, possibly asymmetric. Index 0 is the origin and index N is stop N. The \
            coordinates are there so you can see the geography; when they and the matrix disagree, \
            the matrix wins. A null entry is a leg nobody could measure: avoid depending on it.

            A dispatcher will see your proposal next to the current order, with both totals. Only \
            propose a different order when it is genuinely shorter; returning the current order is a \
            valid answer.

            Put every stop number exactly once in "sequence". In "rationale", explain the order to the \
            dispatcher in Spanish, in at most three short sentences, naming stops as "parada N" with \
            the numbers given here.
            """;

    private final AnthropicProperties properties;
    private volatile AnthropicClient client;

    public AnthropicStopSequenceAdvisor(AnthropicProperties properties) {
        this.properties = properties;
    }

    /** What the model must return. Each field's description becomes part of the JSON schema it is held to. */
    public record SequenceAnswer(
            @JsonPropertyDescription("Every stop number exactly once, in the proposed visiting order.")
            List<Integer> sequence,
            @JsonPropertyDescription("Why this order, in Spanish, in at most three short sentences.")
            String rationale) {
    }

    @Override
    public boolean isAvailable() {
        return properties.configured();
    }

    @Override
    public StopSequenceAdvice advise(StopSequenceQuestion question) {
        if (!properties.configured()) {
            throw new FeatureNotConfiguredException(
                    "The AI assistant is not configured: set tms.ai.anthropic.api-key (ANTHROPIC_API_KEY).");
        }
        StructuredMessageCreateParams<SequenceAnswer> params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokens())
                .system(SYSTEM_PROMPT)
                .outputConfig(StructuredOutputConfig.<SequenceAnswer>builder()
                        .effort(OutputConfig.Effort.of(properties.effort()))
                        .format(SequenceAnswer.class)
                        .build())
                .addUserMessage(userMessage(question))
                .putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();

        long started = System.nanoTime();
        StructuredMessage<SequenceAnswer> response;
        try {
            response = client().messages().create(params);
        } catch (RateLimitException failure) {
            throw unavailable("Anthropic rate limit reached.", failure);
        } catch (UnauthorizedException | PermissionDeniedException failure) {
            log.error("Anthropic rejected the configured API key (HTTP {}); check tms.ai.anthropic.api-key",
                    failure.statusCode());
            throw unavailable("Anthropic rejected the configured API key.", failure);
        } catch (AnthropicServiceException failure) {
            log.warn("Anthropic answered HTTP {} to a stop-sequence request", failure.statusCode());
            throw unavailable("Anthropic answered HTTP " + failure.statusCode() + ".", failure);
        } catch (AnthropicException failure) {
            // Network, timeout, or an answer that did not parse against the schema.
            log.warn("Stop-sequence request to Anthropic failed: {}", failure.getClass().getSimpleName());
            throw unavailable("Anthropic could not be reached or gave an unreadable answer.", failure);
        }

        StopReason stopReason = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stopReason)) {
            throw unavailable("The model declined the request.", null);
        }
        if (StopReason.MAX_TOKENS.equals(stopReason)) {
            throw unavailable("The model ran out of tokens before answering; raise tms.ai.anthropic.max-tokens.", null);
        }
        SequenceAnswer answer;
        try {
            answer = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(StructuredTextBlock::text)
                    .findFirst()
                    .orElseThrow(() -> unavailable("The model gave no answer.", null));
        } catch (AnthropicException failure) {
            throw unavailable("The model's answer did not match the schema.", failure);
        }

        String model = response.model().asString();
        log.info("Stop sequence proposed by {} for {} stops in {} ms", model, question.stops().size(),
                (System.nanoTime() - started) / 1_000_000);
        return new StopSequenceAdvice(answer.sequence(), answer.rationale(), model);
    }

    /**
     * The question as compact JSON. Numbers only, by construction: the origin, the stops by their
     * position in the planner's list, and the matrix. No name, code or id leaves TMS.
     */
    static String userMessage(StopSequenceQuestion question) {
        int stops = question.stops().size();
        StringBuilder json = new StringBuilder(64 + stops * stops * 8);
        json.append("{\"origin\":").append(point(question.origin()));
        json.append(",\"stops\":[");
        for (int index = 0; index < stops; index++) {
            if (index > 0) {
                json.append(',');
            }
            GeoPoint stop = question.stops().get(index);
            json.append("{\"number\":").append(index + 1)
                    .append(",\"lat\":").append(stop.latitude().toPlainString())
                    .append(",\"lng\":").append(stop.longitude().toPlainString()).append('}');
        }
        json.append("],\"currentOrder\":[");
        for (int number = 1; number <= stops; number++) {
            json.append(number == 1 ? "" : ",").append(number);
        }
        json.append("],\"distanceKm\":[");
        for (int row = 0; row < question.distanceKm().size(); row++) {
            json.append(row == 0 ? "[" : ",[");
            List<BigDecimal> cells = question.distanceKm().get(row);
            for (int column = 0; column < cells.size(); column++) {
                BigDecimal km = cells.get(column);
                json.append(column == 0 ? "" : ",")
                        .append(km == null ? "null" : km.setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
            json.append(']');
        }
        json.append("]}");
        return "Sequence the stops of this route.\n\n" + json;
    }

    private static String point(GeoPoint point) {
        return "{\"lat\":" + point.latitude().toPlainString() + ",\"lng\":" + point.longitude().toPlainString() + "}";
    }

    private static ProblemTypedException unavailable(String message, Throwable cause) {
        return new ProblemTypedException(ProblemType.AI_UNAVAILABLE, message, cause);
    }

    /** Built on first use, so a deployment without a key never constructs an HTTP client at all. */
    private AnthropicClient client() {
        AnthropicClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = AnthropicOkHttpClient.builder()
                            .apiKey(properties.apiKey())
                            .timeout(properties.timeout())
                            .maxRetries(properties.maxRetries())
                            .build();
                    client = current;
                }
            }
        }
        return current;
    }

    @PreDestroy
    void close() {
        AnthropicClient current = client;
        if (current != null) {
            current.close();
        }
    }
}
