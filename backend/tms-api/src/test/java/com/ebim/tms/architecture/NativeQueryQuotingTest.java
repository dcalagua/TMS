package com.ebim.tms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Every {@link Query} in the application must contain an even number of single quotes.
 *
 * <p><b>Why this test exists.</b> Spring Data scans a query string for value expressions before
 * it ever reaches the database, and to do that it first maps which regions are quoted literals.
 * That scan is a plain character walk: it does not know that {@code --} starts a comment, so an
 * apostrophe written in English prose inside a SQL comment opens a string literal that nothing
 * closes. The repository bean then fails to be created:
 *
 * <pre>
 *   java.lang.IllegalArgumentException: The string &lt;...&gt;
 *   starts a quoted range at 1496, but never ends it.
 * </pre>
 *
 * <p>That is not a query that returns the wrong rows - it is a bean that does not exist, so the
 * whole application context fails to refresh and the service does not start at all. It happened
 * here: a comment reading "keeps the projection's two getters the same type" cost every
 * {@code @SpringBootTest} in the suite and every boot of the API.
 *
 * <p><b>Why it is a convention test and not an integration test.</b> The integration tests that
 * would have caught it all need Testcontainers, and on a host without Docker they are skipped -
 * which is exactly how this shipped. This one reads annotations off the compiled classes in
 * milliseconds and cannot be skipped by a missing daemon.
 *
 * <p>An even count is the precise invariant: a doubled {@code ''} escape inside a literal still
 * balances, and a lone apostrophe never does.
 */
class NativeQueryQuotingTest {

    private static final JavaClasses APPLICATION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ebim.tms");

    @Test
    @DisplayName("no @Query leaves a single quote open, which would stop the repository bean from being created")
    void everyQueryBalancesItsSingleQuotes() {
        List<String> offenders = new ArrayList<>();

        for (JavaMethod method : APPLICATION_CLASSES.stream()
                .flatMap(clazz -> clazz.getMethods().stream())
                .toList()) {
            method.tryGetAnnotationOfType(Query.class).ifPresent(query -> {
                String sql = query.value();
                if (countSingleQuotes(sql) % 2 != 0) {
                    offenders.add("%s.%s%n%s".formatted(
                            method.getOwner().getName(), method.getName(), suspectComments(sql)));
                }
            });
        }

        assertThat(offenders)
                .describedAs("""
                        A @Query with an odd number of single quotes leaves a quoted range open. \
                        Spring Data fails to create the repository bean and the application context \
                        never refreshes. The usual cause is an apostrophe in prose inside a `--` \
                        comment: rewrite it without the apostrophe (\"the projection and its two \
                        getters\") rather than deleting the comment.""")
                .isEmpty();
    }

    private static int countSingleQuotes(String sql) {
        return (int) sql.chars().filter(character -> character == '\'').count();
    }

    /** The {@code --} comment lines carrying an apostrophe, which is nearly always the cause. */
    private static String suspectComments(String sql) {
        return sql.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("--") && line.contains("'"))
                .map("    suspect comment: %s"::formatted)
                .reduce((a, b) -> a + System.lineSeparator() + b)
                .orElse("    (no `--` comment carries an apostrophe; check the literals)");
    }
}
