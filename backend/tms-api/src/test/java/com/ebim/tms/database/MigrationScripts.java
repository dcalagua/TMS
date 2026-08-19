package com.ebim.tms.database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Locates the Flyway migration scripts and the repository root without depending on the
 * working directory of the test runner.
 *
 * <p>Everything is resolved from the classpath, so the same helper works from Maven, an IDE
 * and CI.
 */
final class MigrationScripts {

    /** {@code V<version>__<snake_case_description>.sql} - the only accepted file name shape. */
    static final Pattern FILE_NAME = Pattern.compile("^V(\\d+)__([a-z0-9]+(?:_[a-z0-9]+)*)\\.sql$");

    private MigrationScripts() {}

    /** The {@code db/migration} directory as copied onto the test classpath. */
    static Path directory() {
        URL resource = MigrationScripts.class.getResource("/db/migration");
        if (resource == null) {
            throw new IllegalStateException("db/migration is not on the classpath");
        }
        try {
            return Path.of(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("db/migration path is not readable", e);
        }
    }

    /** Migration files, ordered by version. */
    static List<Path> scripts() {
        try (Stream<Path> files = Files.list(directory())) {
            return files.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(MigrationScripts::version))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list migration scripts", e);
        }
    }

    static int version(Path script) {
        Matcher matcher = FILE_NAME.matcher(script.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("migration file name does not follow V<n>__<name>.sql: " + script);
        }
        return Integer.parseInt(matcher.group(1));
    }

    static String read(Path script) {
        try {
            return Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("migration script is not valid UTF-8: " + script, e);
        }
    }

    /**
     * Strips {@code --} comments so that documentation inside a SQL file cannot trip a content
     * rule - these files explain, for example, why they do <em>not</em> contain a password.
     */
    static String withoutComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .collect(Collectors.joining("\n"));
    }

    /** Repository root, found by walking up from the compiled test classes to {@code CLAUDE.md}. */
    static Path repositoryRoot() {
        Path candidate = directory();
        while (candidate != null && !Files.exists(candidate.resolve("CLAUDE.md"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("repository root (the directory holding CLAUDE.md) was not found");
        }
        return candidate;
    }
}
