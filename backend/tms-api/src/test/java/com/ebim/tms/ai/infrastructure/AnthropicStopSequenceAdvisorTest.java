package com.ebim.tms.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.api.FeatureNotConfiguredException;
import com.ebim.tms.shared.reference.GeoPoint;
import com.ebim.tms.shared.reference.StopSequenceAdvisorPort.StopSequenceQuestion;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parts of the Anthropic adapter that need no network (ADR-030): what is sent, what is
 * configured, and that an unconfigured deployment refuses before it builds a client.
 */
class AnthropicStopSequenceAdvisorTest {

    private static final GeoPoint ORIGIN = GeoPoint.of(new BigDecimal("-12.046374"), new BigDecimal("-77.042793"));
    private static final GeoPoint STOP_1 = GeoPoint.of(new BigDecimal("-12.100000"), new BigDecimal("-77.000000"));
    private static final GeoPoint STOP_2 = GeoPoint.of(new BigDecimal("-12.200000"), new BigDecimal("-77.010000"));

    private static StopSequenceQuestion question() {
        List<List<BigDecimal>> matrix = List.of(
                List.of(new BigDecimal("0"), new BigDecimal("7.456"), new BigDecimal("18.1")),
                List.of(new BigDecimal("7.5"), new BigDecimal("0"), new BigDecimal("11.2")),
                Arrays.asList(new BigDecimal("18.0"), null, new BigDecimal("0")));
        return new StopSequenceQuestion(ORIGIN, List.of(STOP_1, STOP_2), matrix);
    }

    @Test
    @DisplayName("the message carries the stops by number, their coordinates and the matrix - and nothing else")
    void messageIsGeometryOnly() {
        String message = AnthropicStopSequenceAdvisor.userMessage(question());

        assertThat(message)
                .contains("\"origin\":{\"lat\":-12.046374,\"lng\":-77.042793}")
                .contains("{\"number\":1,\"lat\":-12.100000,\"lng\":-77.000000}")
                .contains("{\"number\":2,\"lat\":-12.200000,\"lng\":-77.010000}")
                .contains("\"currentOrder\":[1,2]")
                // Two decimals, and an unmeasured leg is an explicit null rather than a guess.
                .contains("\"distanceKm\":[[0.00,7.46,18.10],[7.50,0.00,11.20],[18.00,null,0.00]]");
        // The JSON is all digits, signs, punctuation and the six key names.
        String json = message.substring(message.indexOf('{'));
        assertThat(json.replaceAll("\"(origin|stops|number|lat|lng|currentOrder|distanceKm)\"", "")
                .replace("null", "")).matches("[\\d\\-.,:\\[\\]{}]*");
    }

    @Test
    @DisplayName("the matrix must be square over the origin and every stop")
    void matrixShapeIsChecked() {
        assertThatThrownBy(() -> new StopSequenceQuestion(ORIGIN, List.of(STOP_1, STOP_2),
                List.of(List.of(BigDecimal.ZERO, BigDecimal.ONE), List.of(BigDecimal.ONE, BigDecimal.ZERO))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("without a key the advisor is unavailable and refuses before building a client")
    void unconfiguredRefuses() {
        AnthropicStopSequenceAdvisor advisor =
                new AnthropicStopSequenceAdvisor(new AnthropicProperties("  ", null, null, null, null, null));

        assertThat(advisor.isAvailable()).isFalse();
        assertThatThrownBy(() -> advisor.advise(question())).isInstanceOf(FeatureNotConfiguredException.class);
    }

    @Test
    @DisplayName("the properties default to the current Opus at medium effort and never print the key")
    void propertiesDefaultsAndRedaction() {
        AnthropicProperties properties = new AnthropicProperties("sk-ant-not-a-real-key", null, "HIGH", null, null, null);

        assertThat(properties.configured()).isTrue();
        assertThat(properties.model()).isEqualTo("claude-opus-5-5");
        assertThat(properties.effort()).isEqualTo("high");
        assertThat(properties.toString()).doesNotContain("sk-ant").contains("apiKey=<set>");
        assertThat(new AnthropicProperties(null, null, null, null, null, null).effort()).isEqualTo("medium");
    }

    @Test
    @DisplayName("an effort the API does not know is a startup error, not a 400 on the first click")
    void unknownEffortFailsFast() {
        assertThatThrownBy(() -> new AnthropicProperties("key", null, "extreme", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effort");
    }
}
