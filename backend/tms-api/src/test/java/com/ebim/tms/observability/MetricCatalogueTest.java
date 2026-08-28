package com.ebim.tms.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every metric the code emits is documented, and every documented metric exists (JOB 24).
 *
 * <p><b>Why this is a test and not a convention.</b> A metric catalogue is exactly the kind of
 * document that is true the day it is written and wrong three jobs later - somebody adds a counter,
 * nobody updates the list, and an operations document quietly becomes fiction. The failure is
 * silent, and it is found at 02:00 by whoever needed the signal the document said existed.
 *
 * <p>The same reasoning as {@code AuditVocabularyMigrationTest} and
 * {@code PersistedEnumConstraintTest}: where a document and the code have to agree, something has
 * to enforce it.
 */
class MetricCatalogueTest {

    private static final Path SOURCE = Path.of("src/main/java/com/ebim/tms");
    private static final Path CATALOGUE = Path.of("../../docs/operations/OBSERVABILITY.md");

    private static final Pattern DECLARED = Pattern.compile("\"(tms\\.[a-z0-9_.]+)\"");

    @Test
    @DisplayName("every metric the code declares appears in the observability catalogue")
    void everyMetricIsDocumented() throws IOException {
        Set<String> emitted = emittedMetrics();
        String catalogue = Files.readString(CATALOGUE, StandardCharsets.UTF_8);

        Set<String> undocumented = new TreeSet<>();
        for (String metric : emitted) {
            if (!catalogue.contains(metric)) {
                undocumented.add(metric);
            }
        }

        assertThat(undocumented)
                .as("metrics emitted by the code and missing from docs/operations/OBSERVABILITY.md."
                        + " A catalogue nobody maintains is worse than none: somebody reads it at"
                        + " 02:00 looking for the signal it says exists")
                .isEmpty();
    }

    @Test
    @DisplayName("the catalogue names no metric the code does not emit")
    void catalogueInventsNothing() throws IOException {
        Set<String> emitted = emittedMetrics();
        String catalogue = Files.readString(CATALOGUE, StandardCharsets.UTF_8);

        Set<String> invented = new TreeSet<>();
        Matcher matcher = Pattern.compile("`(tms\\.[a-z0-9_.]+)`").matcher(catalogue);
        while (matcher.find()) {
            String named = matcher.group(1);
            // The document writes `tms.notification.raised` / `.suppressed` as one row, so a
            // documented prefix counts as documented when a real metric starts with it.
            boolean exists = emitted.stream().anyMatch(metric -> metric.equals(named)
                    || metric.startsWith(named + ".") || named.startsWith(metric));
            if (!exists) {
                invented.add(named);
            }
        }

        assertThat(invented)
                .as("metrics named in the catalogue that nothing emits - the direction that sends"
                        + " somebody looking for a dashboard which will always be empty")
                .isEmpty();
    }

    private static Set<String> emittedMetrics() throws IOException {
        Set<String> metrics = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCE)) {
            List<Path> java = files.filter(path -> path.toString().endsWith(".java")).toList();
            for (Path file : java) {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                for (String line : body.split("\n")) {
                    if (!line.contains("_METRIC") && !line.contains("_TIMER")) {
                        continue;
                    }
                    Matcher matcher = DECLARED.matcher(line);
                    while (matcher.find()) {
                        metrics.add(matcher.group(1));
                    }
                }
            }
        }
        assertThat(metrics).as("the scan found no metrics at all, so it is broken").isNotEmpty();
        return metrics;
    }
}
