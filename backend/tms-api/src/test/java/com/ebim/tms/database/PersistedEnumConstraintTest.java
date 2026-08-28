package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Every persisted enum column whose database guards it with a {@code CHECK} must allow exactly the
 * values its Java enum declares (Phase 2 JOB 18, closing debt D8).
 *
 * <h2>Why this exists</h2>
 *
 * <p>The drift it catches has already happened once in this codebase and cost a release. Migration
 * V25 records it: {@code AuditAction.AUTO_PLAN} existed while the {@code CHECK} it claims to mirror
 * did not list it, so against a real PostgreSQL <em>every</em> auto-plan violated the constraint and
 * rolled the whole transaction back. Nothing failed at compile time, nothing failed in a unit test,
 * and the failure surfaced at the end of a business operation in production.
 *
 * <p>{@code AuditVocabularyMigrationTest} closed that hole for the audit vocabulary specifically -
 * the one that drifts most - by reading the migration files. This generalises it across <b>every</b>
 * enum column in the schema.
 *
 * <h2>Why it asks PostgreSQL rather than parsing SQL</h2>
 *
 * <p>The audit test parses migration files with a regex, and says why: it must run on machines with
 * no Docker. That is a real constraint and a real cost - the regex has to cope with a constraint
 * being dropped and re-added across seven migrations, and only the last definition counts.
 *
 * <p>This test takes the other trade. It runs against a migrated database and asks PostgreSQL for
 * {@code pg_get_constraintdef}, which is the <b>normalised</b> definition of what the database
 * actually ended up holding - after every drop, re-add and alter, in whatever order they happened.
 * There is no history to reconstruct and no SQL dialect to parse. The two tests are complementary:
 * the audit one runs everywhere and covers one table; this one needs Docker and covers all of them.
 *
 * <h2>What is deliberately not asserted</h2>
 *
 * <p><b>Not every enum column needs a {@code CHECK}.</b> A column may be constrained by a foreign
 * key to a catalogue table, or be genuinely open. A column with no {@code CHECK} is therefore
 * <em>reported</em> by {@link Coverage#everyEnumColumnIsAccountedFor} and not failed - so the set is
 * visible and deliberate rather than silently empty.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class PersistedEnumConstraintTest {

    private static final String SCHEMA = "tms";

    /** Values inside a PostgreSQL-normalised {@code CHECK}: {@code (status)::text = ANY (ARRAY['A'::text, ...])}. */
    private static final Pattern QUOTED_VALUE = Pattern.compile("'([^']+)'");

    private static String jdbcUrl;
    private static List<EnumColumn> enumColumns;

    /**
     * One persisted enum column: where it lives, and what Java says may be in it.
     *
     * @param owner  the entity, named so a failure says which class to open
     * @param table  the physical table, from {@code @Table} or the entity's own name
     * @param column the physical column, from {@code @Column} or the field's own name
     * @param values what the Java enum declares
     */
    private record EnumColumn(String owner, String table, String column, Set<String> values) {

        String qualified() {
            return SCHEMA + "." + table + "." + column;
        }
    }

    @BeforeAll
    static void migrateAndScan() {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_enum_guard");
        enumColumns = scanEntities();
    }

    // ------------------------------------------------------------------ the guard

    @Test
    @DisplayName("every enum column guarded by a CHECK allows exactly its Java enum's values")
    void everyCheckedEnumColumnMatchesItsJavaEnum() throws SQLException {
        List<String> drift = new ArrayList<>();

        try (Connection connection = PostgresTestDatabase.connect(jdbcUrl)) {
            for (EnumColumn column : enumColumns) {
                Optional<Set<String>> allowed = allowedValues(connection, column);
                if (allowed.isEmpty()) {
                    continue; // No CHECK on this column - reported by the coverage test, not failed.
                }
                Set<String> inDatabase = allowed.get();
                if (inDatabase.equals(column.values())) {
                    continue;
                }

                Set<String> missingInDb = new TreeSet<>(column.values());
                missingInDb.removeAll(inDatabase);
                Set<String> extraInDb = new TreeSet<>(inDatabase);
                extraInDb.removeAll(column.values());

                drift.add("""

                        %s  (%s)
                          Java values : %s
                          DB values   : %s
                          missing in DB : %s
                          extra in DB   : %s"""
                        .formatted(column.qualified(), column.owner(),
                                new TreeSet<>(column.values()), new TreeSet<>(inDatabase),
                                missingInDb.isEmpty() ? "-" : missingInDb,
                                extraInDb.isEmpty() ? "-" : extraInDb));
            }
        }

        assertThat(drift)
                .as("""
                        Enum columns whose CHECK disagrees with their Java enum.

                        A value MISSING IN DB fails at runtime, at the end of a business \
                        transaction, on the first write that uses it - it compiles and no unit test \
                        sees it (migration V25 records exactly this happening). A value EXTRA IN DB \
                        is a value the database will accept and Java cannot read back.

                        Add the value to a NEW migration - never edit an applied one.""")
                .isEmpty();
    }

    // ------------------------------------------------------------------ coverage

    @Nested
    @DisplayName("coverage")
    @EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
    class Coverage {

        /**
         * The scan must actually find the columns, or the guard above passes by finding nothing.
         *
         * <p>A guard that silently covers zero columns is worse than no guard, because it reports
         * green. This is the assertion that stops that.
         */
        @Test
        @DisplayName("the scan finds the enum columns it is supposed to guard")
        void theScanIsNotEmpty() {
            assertThat(enumColumns)
                    .as("persisted @Enumerated(STRING) columns discovered by reflection")
                    .hasSizeGreaterThan(30);
        }

        /**
         * Every enum column is either guarded by a {@code CHECK} or knowingly not.
         *
         * <p>Reported rather than failed: a column may be constrained by a foreign key to a
         * catalogue table, or be genuinely open. What must not happen is for the set to be
         * <em>unknown</em>, so it is printed on every run.
         */
        @Test
        @DisplayName("enum columns with no CHECK are listed, so the set is deliberate and not accidental")
        void everyEnumColumnIsAccountedFor() throws SQLException {
            List<String> unguarded = new ArrayList<>();
            int guarded = 0;

            try (Connection connection = PostgresTestDatabase.connect(jdbcUrl)) {
                for (EnumColumn column : enumColumns) {
                    if (allowedValues(connection, column).isPresent()) {
                        guarded++;
                    } else {
                        unguarded.add(column.qualified() + "  (" + column.owner() + ")");
                    }
                }
            }

            System.out.println("[enum guard] " + guarded + " of " + enumColumns.size()
                    + " persisted enum columns are guarded by a CHECK.");
            if (!unguarded.isEmpty()) {
                System.out.println("[enum guard] not guarded by a CHECK:");
                unguarded.forEach(name -> System.out.println("[enum guard]   " + name));
            }

            // Every one of them, since V44 closed the last gap - tms.trip_cost.rate_card_scope,
            // a snapshot of a value that was constrained at its source and not at its copy. If a
            // future column is legitimately unguarded this becomes a named exemption with a
            // reason, not a lowered threshold.
            assertThat(unguarded)
                    .as("enum columns with no CHECK behind them")
                    .isEmpty();
            assertThat(guarded).isEqualTo(enumColumns.size());
        }
    }

    // ------------------------------------------------------------------ the guard's own guard

    @Nested
    @DisplayName("the guard detects drift")
    @EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
    class DriftDetection {

        /**
         * A controlled negative: introduce drift in a throwaway database and prove the comparison
         * notices it.
         *
         * <p>Without this, a bug in {@link #allowedValues} - a regex that matches nothing, a query
         * that returns no rows - would make the guard pass on every schema, including a broken one.
         * The test that proves a guard works is the one that breaks something on purpose.
         *
         * <p>Runs against its <b>own</b> database, so nothing here can affect the real schema the
         * other tests read.
         */
        @Test
        @DisplayName("a value present in Java and missing from the CHECK is caught")
        void catchesAValueMissingFromTheCheck() throws SQLException {
            String url = PostgresTestDatabase.createEmptyDatabase("tms_enum_guard_drift");
            try (Connection connection = PostgresTestDatabase.connect(url);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
                statement.execute("""
                        CREATE TABLE tms.drift_probe (
                            id     integer NOT NULL,
                            status text    NOT NULL,
                            CONSTRAINT ck_drift_probe_status CHECK (status IN ('ALPHA', 'BETA'))
                        )""");

                EnumColumn java3 = new EnumColumn("DriftProbe", "drift_probe", "status",
                        Set.of("ALPHA", "BETA", "GAMMA"));
                EnumColumn java2 = new EnumColumn("DriftProbe", "drift_probe", "status",
                        Set.of("ALPHA", "BETA"));

                Set<String> inDatabase = allowedValues(connection, java2).orElseThrow(
                        () -> new AssertionError("the guard could not read a CHECK it just created"));

                // The database is read correctly...
                assertThat(inDatabase).containsExactlyInAnyOrder("ALPHA", "BETA");
                // ...it agrees when Java agrees...
                assertThat(inDatabase).isEqualTo(java2.values());
                // ...and it disagrees the moment Java gains a value the migration never added.
                assertThat(inDatabase).isNotEqualTo(java3.values());
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * What the database will actually accept in this column, or empty when no {@code CHECK} governs
     * it.
     *
     * <p>Asks for {@code pg_get_constraintdef}, which PostgreSQL renders in its own normalised form
     * - typically {@code CHECK (((status)::text = ANY ((ARRAY['A'::character varying, ...])::text[])))}.
     * Whatever the migration wrote, however many times it was dropped and re-added, this is the one
     * definition that survived, so there is no history to reconstruct.
     *
     * <p><b>Only single-column constraints are read</b>, and that is load-bearing rather than
     * tidy. A cross-column rule such as {@code ck_trip_cost_component_status_consistent} mentions
     * several enums in one expression; unioning its literals into the answer invents drift that
     * does not exist. The first version of this test did exactly that and reported three false
     * positives on {@code trip_cost_component} - the constraints were right and the query was
     * wrong. {@code cardinality(conkey) = 1} is the fix, expressed in PostgreSQL's own metadata
     * rather than by pattern-matching the definition text.
     */
    private static Optional<Set<String>> allowedValues(Connection connection, EnumColumn column)
            throws SQLException {
        String sql = """
                SELECT pg_get_constraintdef(c.oid) AS definition
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE c.contype = 'c' AND n.nspname = ? AND t.relname = ?
                  -- EXACTLY one column, and it is this one. A multi-column CHECK such as
                  -- ck_trip_cost_component_status_consistent mentions several enums at once, and
                  -- unioning its literals in would invent drift that does not exist - which is
                  -- what the first version of this test did.
                  AND cardinality(c.conkey) = 1
                  AND c.conkey[1] = (
                      SELECT a.attnum FROM pg_attribute a
                      WHERE a.attrelid = t.oid AND a.attname = ?)
                """;
        Set<String> values = new LinkedHashSet<>();
        boolean found = false;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, column.table());
            statement.setString(3, column.column());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Set<String> quoted = quotedValues(rows.getString("definition"));
                    if (!quoted.isEmpty()) {
                        values.addAll(quoted);
                        found = true;
                    }
                }
            }
        }
        return found ? Optional.of(values) : Optional.empty();
    }

    private static Set<String> quotedValues(String constraintDefinition) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = QUOTED_VALUE.matcher(constraintDefinition);
        while (matcher.find()) {
            String value = matcher.group(1);
            // PostgreSQL renders the cast target inside the definition too; only SCREAMING_CASE
            // literals are enum values. 'character varying' and 'text' are not.
            if (value.matches("[A-Z][A-Z0-9_]*")) {
                values.add(value);
            }
        }
        return values;
    }

    /** Every {@code @Enumerated(STRING)} field on every {@code @Entity}, with its physical names. */
    private static List<EnumColumn> scanEntities() {
        // ArchUnit's importer rather than a new reflection dependency - it is already used by
        // NativeQueryQuotingTest, so this adds a test and not a build change.
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ebim.tms");
        List<EnumColumn> found = new ArrayList<>();

        for (JavaClass type : classes) {
            if (!type.isAnnotatedWith(Entity.class)) {
                continue;
            }
            Class<?> entity = type.reflect();
            String table = tableNameOf(entity);
            for (Field field : entity.getDeclaredFields()) {
                Enumerated enumerated = field.getAnnotation(Enumerated.class);
                if (enumerated == null || enumerated.value() != EnumType.STRING) {
                    continue;
                }
                if (!field.getType().isEnum()) {
                    continue;
                }
                Set<String> values = Arrays.stream(field.getType().getEnumConstants())
                        .map(constant -> ((Enum<?>) constant).name())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                found.add(new EnumColumn(entity.getSimpleName(), table, columnNameOf(field), values));
            }
        }
        found.sort(java.util.Comparator.comparing(EnumColumn::qualified));
        return List.copyOf(found);
    }

    private static String tableNameOf(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        return camelToSnake(entity.getSimpleName());
    }

    private static String columnNameOf(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.name().isBlank()) {
            return column.name();
        }
        return camelToSnake(field.getName());
    }

    /** Hibernate's implicit naming: {@code serviceWindowStart} becomes {@code service_window_start}. */
    private static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
