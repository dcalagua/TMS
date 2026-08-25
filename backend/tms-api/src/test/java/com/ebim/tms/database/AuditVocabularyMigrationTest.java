package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditAggregateType;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AuditAction} and {@link AuditAggregateType} must name exactly what
 * {@code ck_audit_event_action} and {@code ck_audit_event_aggregate_type} allow.
 *
 * <p>This test exists because the drift it catches has already happened once and cost a feature.
 * Migration V25 records it: {@code AuditAction.AUTO_PLAN} had existed since automatic planning V1
 * while the CHECK it claims to mirror did not list it, so against a real PostgreSQL <em>every</em>
 * {@code POST /planning/runs/{id}/auto-plan} violated the constraint and rolled the whole apply
 * back. It went unnoticed for a release because the only tests that reach a database are the
 * Testcontainers ones, and Docker is not available in this environment (CLAUDE.md, "Local
 * environment notes").
 *
 * <p>So the check is deliberately made without a database. It reads the migration files off the
 * test classpath and compares the vocabulary they define with the enums, which means it runs on
 * every machine, every time, including the ones where the whole {@code database} package is
 * skipped. A new constant added to an enum without the migration that admits it now fails here in
 * seconds rather than in production at the end of a business transaction.
 *
 * <p>It reads the <em>last</em> migration that redefines each constraint, by version, because both
 * have been dropped and re-added several times (V22, V25, V26, V28, V30, V31, V34) and only the
 * final definition is what the database ends up holding.
 */
class AuditVocabularyMigrationTest {

    private static final Pattern ACTION_CHECK =
            Pattern.compile("ck_audit_event_action\\s+CHECK\\s*\\(\\s*action\\s+IN\\s*\\(([^)]*)\\)");

    private static final Pattern AGGREGATE_CHECK = Pattern.compile(
            "ck_audit_event_aggregate_type\\s+CHECK\\s*\\(\\s*aggregate_type\\s+IN\\s*\\(([^)]*)\\)");

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    @Test
    @DisplayName("AuditAction names exactly the actions ck_audit_event_action allows")
    void actionsMatchTheConstraint() {
        assertThat(latestVocabulary(ACTION_CHECK))
                .as("tms.audit_event.ck_audit_event_action vs com.ebim.tms.shared.audit.AuditAction")
                .containsExactlyInAnyOrderElementsOf(names(AuditAction.values()));
    }

    @Test
    @DisplayName("AuditAggregateType names exactly the aggregates ck_audit_event_aggregate_type allows")
    void aggregateTypesMatchTheConstraint() {
        assertThat(latestVocabulary(AGGREGATE_CHECK))
                .as("tms.audit_event.ck_audit_event_aggregate_type vs "
                        + "com.ebim.tms.shared.audit.AuditAggregateType")
                .containsExactlyInAnyOrderElementsOf(names(AuditAggregateType.values()));
    }

    /**
     * The values allowed by the highest-versioned migration that defines this constraint. Files are
     * walked newest first and the first match wins, so a migration that only drops the constraint -
     * or merely mentions its name in prose - does not shadow the one that defines it.
     */
    private static Set<String> latestVocabulary(Pattern constraint) {
        List<Path> newestFirst = MigrationScripts.scripts().stream()
                .sorted(Comparator.comparingInt(MigrationScripts::version).reversed())
                .toList();

        for (Path script : newestFirst) {
            String sql = MigrationScripts.withoutComments(MigrationScripts.read(script));
            Matcher matcher = constraint.matcher(sql);
            String lastDefinition = null;
            // A single migration may drop and re-add the constraint more than once; the last one
            // in the file is the one that survives it.
            while (matcher.find()) {
                lastDefinition = matcher.group(1);
            }
            if (lastDefinition != null) {
                return quotedValues(lastDefinition);
            }
        }
        throw new AssertionError("no migration defines " + constraint.pattern());
    }

    private static Set<String> quotedValues(String valueList) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = QUOTED.matcher(valueList);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static List<String> names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).toList();
    }
}
