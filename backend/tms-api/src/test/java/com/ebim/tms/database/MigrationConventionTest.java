package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rules about the migration history that can be enforced without a database, so they hold
 * even on a machine where Docker is unavailable.
 *
 * <p>They encode the non-negotiable repository boundaries: one migration history owned by
 * Flyway, no destructive DDL, no touching of the Supabase-managed schemas, and no demo or
 * production-like data inside canonical migrations.
 */
class MigrationConventionTest {

    private static final List<Path> SCRIPTS = MigrationScripts.scripts();

    /** Statements that must never appear in an application migration. */
    private static final List<String> FORBIDDEN_STATEMENTS = List.of(
            "drop table",
            "drop schema",
            "drop database",
            "truncate",
            // `create role` is NOT here. The rule this list enforces is "no credential in a
            // versioned file", and ADR-005 needs a passwordless NOLOGIN role that carries
            // none. What actually matters is enforced by roleCreationCarriesNoCredential()
            // below: no password, and no role that can log in.
            "alter role",
            "drop role",
            "drop owned");

    /** A role that can log in, or any password, would be a credential in a versioned file. */
    private static final Pattern LOGIN_ROLE = Pattern.compile(
            "create\\s+role\\b[^;]*?(?<!no)login", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Supabase-managed namespaces Flyway must never create or alter (ADR-002). */
    private static final Pattern SUPABASE_MANAGED_DDL = Pattern.compile(
            "(create|alter|drop)\\s+(table|schema|function|trigger|policy|index)\\s+"
                    + "(if\\s+(not\\s+)?exists\\s+)?(auth|storage|realtime)\\.",
            Pattern.CASE_INSENSITIVE);

    /** The four table privileges a business table can hand the runtime role. */
    private static final Set<String> DML_VERBS = Set.of("select", "insert", "update", "delete");

    /** Policy commands that can admit a row, and therefore need {@code WITH CHECK}. */
    private static final Set<String> WRITABLE_COMMANDS = Set.of("all", "insert", "update");

    /** Business tables whose rows are tenant data, never migration content (seed policy). */
    private static final List<String> TENANT_DATA_TABLES = List.of(
            "tms.app_user", "tms.organization", "tms.company", "tms.membership", "tms.membership_role");

    @Test
    @DisplayName("there is at least one migration and every file follows V<n>__<name>.sql")
    void fileNamesFollowTheConvention() {
        assertThat(SCRIPTS).isNotEmpty();
        assertThat(SCRIPTS)
                .allSatisfy(script -> assertThat(script.getFileName().toString())
                        .matches(MigrationScripts.FILE_NAME.pattern()));
    }

    @Test
    @DisplayName("versions are unique and contiguous from 1, so ordering is unambiguous")
    void versionsAreUniqueAndContiguous() {
        List<Integer> versions = SCRIPTS.stream().map(MigrationScripts::version).toList();

        assertThat(versions).doesNotHaveDuplicates().isSorted();
        assertThat(versions).containsExactlyElementsOf(IntStream.rangeClosed(1, versions.size()).boxed().toList());
    }

    @Test
    @DisplayName("no migration contains destructive or role-management DDL")
    void noDestructiveStatements() {
        for (Path script : SCRIPTS) {
            String sql = MigrationScripts.withoutComments(MigrationScripts.read(script)).toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_STATEMENTS) {
                assertThat(sql)
                        .as("%s must not contain '%s' - applied migrations are immutable and "
                                + "roles/credentials are an operations concern, never migration content",
                                script.getFileName(), forbidden)
                        .doesNotContain(forbidden);
            }
        }
    }

    @Test
    @DisplayName("a migration may create a role, but never one that carries a credential")
    void roleCreationCarriesNoCredential() {
        for (Path script : SCRIPTS) {
            String sql = MigrationScripts.withoutComments(MigrationScripts.read(script));

            assertThat(sql.toLowerCase(Locale.ROOT))
                    .as("%s must not mention a password - a credential must never appear in a "
                            + "versioned file, and provisioning one is an operations concern",
                            script.getFileName())
                    .doesNotContain("password");

            assertThat(LOGIN_ROLE.matcher(sql).find())
                    .as("%s creates a role that can log in. A migration may only create a "
                            + "NOLOGIN role reached through SET ROLE (ADR-005); a connectable "
                            + "role needs a credential, which is provisioned outside the repo",
                            script.getFileName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("no migration creates or alters the Supabase-managed auth/storage schemas")
    void supabaseManagedSchemasAreUntouched() {
        for (Path script : SCRIPTS) {
            String sql = MigrationScripts.withoutComments(MigrationScripts.read(script));
            assertThat(SUPABASE_MANAGED_DDL.matcher(sql).find())
                    .as("%s must not issue DDL against auth/storage/realtime", script.getFileName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("migrations seed schema-contract reference data only, never tenants or users")
    void migrationsContainNoTenantOrUserData() {
        for (Path script : SCRIPTS) {
            String sql = MigrationScripts.withoutComments(MigrationScripts.read(script)).toLowerCase(Locale.ROOT);
            for (String table : TENANT_DATA_TABLES) {
                // Matched as a whole identifier, not as a substring: `tms.company_settings` is a
                // different table from `tms.company`, and a plain contains() check reads the
                // first as a violation of a rule about the second. The rule is about seeding
                // tenants and users, so it has to end where the table name ends.
                Pattern insert = Pattern.compile("insert\\s+into\\s+" + Pattern.quote(table) + "\\b(?!_)");
                assertThat(insert.matcher(sql).find())
                        .as("%s must not insert into %s - demo and local fixtures belong to "
                                + "supabase/seeds or to test code", script.getFileName(), table)
                        .isFalse();
            }
            assertThat(sql)
                    .as("%s must not contain a credential", script.getFileName())
                    .doesNotContain("password '")
                    .doesNotContain("service_role_key")
                    .doesNotContain("anon_key");
        }
    }

    @Test
    @DisplayName("no migration grants privileges to the Supabase API roles")
    void noPrivilegeIsGrantedToSupabaseApiRoles() {
        for (Path script : SCRIPTS) {
            String sql = MigrationScripts.withoutComments(MigrationScripts.read(script)).toLowerCase(Locale.ROOT);
            assertThat(sql)
                    .as("%s: the tms schema is backend-only; anon/authenticated must never be granted access",
                            script.getFileName())
                    .doesNotContain("grant usage on schema tms to anon")
                    .doesNotContain("grant usage on schema tms to authenticated")
                    .doesNotContain("to anon,")
                    .doesNotContain("to authenticated,");
        }
    }

    /**
     * Every application table has row-level security switched on.
     *
     * <p>Checked here, against the SQL text, because every other RLS assertion in this repository
     * needs a container - and on a machine with no Docker those are all skipped, which is exactly
     * the machine where a table gets added without its {@code ENABLE ROW LEVEL SECURITY} and
     * nothing says so. RLS is defence in depth behind the service-layer company predicate
     * (ADR-005); a table that quietly lacks it has one line of defence where the design says two,
     * and nobody finds out until an application query forgets its predicate.
     *
     * <p>This proves the statement is present, not that the policy behind it is right. What the
     * policy actually admits is {@code TenantRlsIsolationIntegrationTest}'s job, and that one does
     * need a database.
     */
    @Test
    @DisplayName("every table created by a migration has row-level security enabled")
    void everyTableEnablesRowLevelSecurity() {
        Pattern created = Pattern.compile("create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?tms\\.([a-z_]+)");
        Pattern secured = Pattern.compile("alter\\s+table\\s+tms\\.([a-z_]+)\\s+enable\\s+row\\s+level\\s+security");

        List<String> tables = new java.util.ArrayList<>();
        java.util.Set<String> withRls = new java.util.LinkedHashSet<>();
        for (Path script : SCRIPTS) {
            String sql = MigrationScripts.withoutComments(MigrationScripts.read(script)).toLowerCase(Locale.ROOT);
            created.matcher(sql).results().map(match -> match.group(1)).forEach(tables::add);
            secured.matcher(sql).results().map(match -> match.group(1)).forEach(withRls::add);
        }

        assertThat(tables).as("the migration history is expected to create tables").isNotEmpty();
        assertThat(tables)
                .as("every tms table must ENABLE ROW LEVEL SECURITY somewhere in the migration history "
                        + "(ADR-005). A table missing it is a tenant boundary with one line of defence "
                        + "instead of two, and the tests that would catch it need Docker.")
                .allSatisfy(table -> assertThat(withRls).contains(table));
    }

    /**
     * Every sequence names the runtime role in a grant of its own.
     *
     * <p>V13 gave {@code tms_app} its sequence privileges twice over: once with
     * {@code GRANT ... ON ALL SEQUENCES}, which is a one-off over the sequences that existed at
     * the time, and once with {@code ALTER DEFAULT PRIVILEGES}, which applies only to objects
     * created by the very role that executed it. Neither survives a rebuild performed under a
     * different administrative role, and a sequence that ends up without an ACL entry for
     * {@code tms_app} fails at {@code nextval} under {@link
     * com.ebim.tms.shared.security.TenantScopedDataSource} - the business stops rather than
     * leaking, and nothing in the migration history says why.
     *
     * <p>Tables never had that problem because each one carries its own named grant. This rule
     * holds sequences to the same standard, and it is checked against the SQL text because the
     * container-backed alternative is skipped on exactly the machine where a new sequence gets
     * added without one.
     */
    @Test
    @DisplayName("every sequence created by a migration grants the runtime role by name")
    void everySequenceGrantsTheRuntimeRole() {
        Pattern created = Pattern.compile("create\\s+sequence\\s+(?:if\\s+not\\s+exists\\s+)?tms\\.([a-z_]+)");
        Pattern granted = Pattern.compile("grant\\s+[^;]*?\\bon\\s+tms\\.([a-z_]+)\\s+to\\s+tms_app");

        List<String> sequences = new java.util.ArrayList<>();
        java.util.Set<String> withGrant = new java.util.LinkedHashSet<>();
        for (Path script : SCRIPTS) {
            String sql = MigrationScripts.withoutComments(MigrationScripts.read(script)).toLowerCase(Locale.ROOT);
            created.matcher(sql).results().map(match -> match.group(1)).forEach(sequences::add);
            granted.matcher(sql).results().map(match -> match.group(1)).forEach(withGrant::add);
        }

        assertThat(sequences).as("the migration history is expected to create sequences").isNotEmpty();
        assertThat(sequences)
                .as("every tms sequence must be granted to tms_app by name somewhere in the history. "
                        + "V13's blanket grant covers only what existed then, and its default "
                        + "privileges bind to the creating role, so a later sequence relying on "
                        + "either can lose the privilege in a rebuild and stop nextval dead.")
                .allSatisfy(sequence -> assertThat(withGrant).contains(sequence));
    }

    /**
     * A grant that omits a verb is not a narrowing; the matching {@code REVOKE} is.
     *
     * <p>V13 attached {@code SELECT, INSERT, UPDATE, DELETE} to every table Flyway creates, once
     * with {@code GRANT ... ON ALL TABLES} and once with {@code ALTER DEFAULT PRIVILEGES}, which
     * fires at {@code CREATE TABLE} time. From then on a shorter per-table
     * {@code GRANT SELECT, INSERT ON tms.x TO tms_app} adds nothing and removes nothing: {@code
     * GRANT} is additive, so the two verbs the migration meant to withhold are still there.
     *
     * <p>V22 knew this and wrote the {@code REVOKE} that actually withholds them; V23, V27, V28
     * and V29 followed. Eleven later tables wrote the intent in a comment and stopped at the
     * {@code GRANT}, so the schema claimed an append-only posture it did not have - and this
     * test class, {@link SchemaExposureIntegrationTest} and {@code docs/database/DATA_MODEL.md}
     * all repeated the claim. V50 performed the missing revocations; this rule is what stops the
     * gap reopening, and it is textual because the alternative needs Docker.
     *
     * <p>Only tables carrying a per-table named grant are examined. The pre-V13 tables have no
     * named grant of their own and are covered entirely by V13's blanket one, so there is no
     * declared intent here to hold them to.
     */
    @Test
    @DisplayName("a table whose named grant omits a verb also revokes it from the runtime role")
    void everyNarrowedGrantIsBackedByARevoke() {
        java.util.Set<String> tables = new java.util.LinkedHashSet<>();
        Pattern created = Pattern.compile("create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?tms\\.([a-z_]+)");
        Pattern granted = Pattern.compile("grant\\s+([a-z,\\s]+?)\\s+on\\s+tms\\.([a-z_]+)\\s+to\\s+tms_app");
        Pattern revoked = Pattern.compile("revoke\\s+([a-z,\\s]+?)\\s+on\\s+tms\\.([a-z_]+)\\s+from\\s+tms_app");

        java.util.Map<String, java.util.Set<String>> grants = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.Set<String>> revocations = new java.util.LinkedHashMap<>();
        for (Path script : SCRIPTS) {
            String sql = MigrationScripts.withoutComments(MigrationScripts.read(script)).toLowerCase(Locale.ROOT);
            created.matcher(sql).results().map(match -> match.group(1)).forEach(tables::add);
            collectVerbs(granted.matcher(sql), grants);
            collectVerbs(revoked.matcher(sql), revocations);
        }

        assertThat(tables).as("the migration history is expected to create tables").isNotEmpty();
        java.util.List<String> narrowed = grants.keySet().stream()
                .filter(tables::contains)
                .filter(table -> !grants.get(table).containsAll(DML_VERBS))
                .toList();
        assertThat(narrowed)
                .as("the history is expected to contain deliberately narrowed grants - V22's "
                        + "append-only audit_event is the first of them")
                .isNotEmpty();

        for (String table : narrowed) {
            java.util.Set<String> withheld = new java.util.LinkedHashSet<>(DML_VERBS);
            withheld.removeAll(grants.get(table));
            java.util.Set<String> actuallyRevoked = revocations.getOrDefault(table, java.util.Set.of());

            assertThat(actuallyRevoked)
                    .as("tms.%s is granted %s, so it means to withhold %s - but a shorter GRANT "
                            + "withholds nothing once V13's ALTER DEFAULT PRIVILEGES has attached "
                            + "all four verbs at CREATE TABLE time. Add "
                            + "'REVOKE %s ON tms.%s FROM tms_app;' in a new migration, the way V22 "
                            + "and V28 do, or grant the verb and delete the claim.",
                            table, grants.get(table), withheld,
                            String.join(", ", withheld).toUpperCase(Locale.ROOT), table)
                    .containsAll(withheld);
        }
    }

    private static void collectVerbs(Matcher matcher, java.util.Map<String, java.util.Set<String>> into) {
        matcher.results().forEach(match -> {
            java.util.Set<String> verbs = java.util.Arrays.stream(match.group(1).split(","))
                    .map(String::trim)
                    .filter(DML_VERBS::contains)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            if (!verbs.isEmpty()) {
                into.computeIfAbsent(match.group(2), key -> new java.util.LinkedHashSet<>()).addAll(verbs);
            }
        });
    }

    /**
     * Every policy that can admit a row declares {@code WITH CHECK}.
     *
     * <p>A {@code USING}-only policy on a writable command is the subtle half of an RLS mistake:
     * it filters what the role may read, update or delete, and says nothing about what it may
     * <em>insert</em>. The row lands in another company and is merely invisible afterwards - a
     * write leak that looks like working isolation from every read path. V13 states the rule in
     * prose; this holds the history to it.
     *
     * <p>{@code FOR SELECT} and {@code FOR DELETE} are the other side of the same rule: they
     * admit no row, PostgreSQL rejects {@code WITH CHECK} on them outright, so one appearing
     * there means the policy's command was mistyped.
     */
    @Test
    @DisplayName("every policy that can write declares WITH CHECK, and no read-only policy does")
    void everyWritePolicyDeclaresWithCheck() {
        Pattern policy = Pattern.compile(
                "create\\s+policy\\s+([a-z_%i]+)\\s+on\\s+tms\\.([a-z_%i.]*)(.*?);",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Pattern command = Pattern.compile("\\bfor\\s+(all|select|insert|update|delete)\\b");

        int examined = 0;
        for (Path script : SCRIPTS) {
            String sql = MigrationScripts.withoutComments(MigrationScripts.read(script)).toLowerCase(Locale.ROOT);
            for (MatchResult match : policy.matcher(sql).results().toList()) {
                examined++;
                String body = match.group(3);
                Matcher verb = command.matcher(body);
                // No FOR clause means FOR ALL, which is the writable case.
                String applies = verb.find() ? verb.group(1) : "all";
                boolean declaresCheck = body.contains("with check");

                if (WRITABLE_COMMANDS.contains(applies)) {
                    assertThat(declaresCheck)
                            .as("%s: policy %s on tms.%s is FOR %s and declares no WITH CHECK. "
                                    + "USING filters reads; without WITH CHECK the role may insert "
                                    + "a row into another company and simply not see it afterwards",
                                    script.getFileName(), match.group(1), match.group(2), applies)
                            .isTrue();
                } else {
                    assertThat(declaresCheck)
                            .as("%s: policy %s on tms.%s is FOR %s, which admits no row - "
                                    + "PostgreSQL rejects WITH CHECK there, so the command is wrong",
                                    script.getFileName(), match.group(1), match.group(2), applies)
                            .isFalse();
                }
            }
        }
        assertThat(examined).as("the history is expected to create policies").isGreaterThan(50);
    }

    /**
     * RLS is never forced on the owner, and that is a decision with a cost on both sides.
     *
     * <p>The backend connects as the schema owner and enters {@code tms_app} only for a
     * company-scoped request, so unforced RLS leaves Flyway, principal resolution and the
     * scheduled webhook dispatcher unfiltered. Forcing it would close that gap and open three
     * worse ones, all silent: the history's 19 cross-company data backfills would match
     * {@code company_id = tms.current_company_id()} with no company set and update zero rows
     * while Flyway reported success; {@code WebhookDispatchScheduler}, which has no security
     * context by design, would drain zero rows instead of every company's queue; and the
     * integration tests that seed business rows as the owner would stop testing anything.
     *
     * <p>Nothing fails with SQLSTATE 42501 in any of those - they return nothing and carry on.
     * ADR-005 rejected forcing on this reasoning and V50 re-examined it against the counted
     * evidence and agreed. Reversing it is allowed; doing it without an ADR is not, and this is
     * the assertion that says so on a machine with no Docker.
     * {@link SchemaExposureIntegrationTest#rlsIsNotForcedForTheOwner} asserts the same posture
     * against a real database.
     */
    @Test
    @DisplayName("no migration forces row-level security on the schema owner (ADR-005, V50)")
    void rowLevelSecurityIsNeverForcedOnTheOwner() {
        for (Path script : SCRIPTS) {
            String sql = MigrationScripts.withoutComments(MigrationScripts.read(script)).toLowerCase(Locale.ROOT);
            assertThat(sql)
                    .as("%s must not FORCE row level security: Flyway, principal resolution and "
                            + "the webhook dispatcher all run as the owner, and forcing turns each "
                            + "of them into a silent zero-row path rather than an error "
                            + "(ADR-005, and V50 section 4 for the counted evidence)",
                            script.getFileName())
                    .doesNotContain("force row level security");
        }
    }

    /**
     * Every function ends up with its {@code search_path} pinned.
     *
     * <p>A function that inherits the caller's {@code search_path} resolves its unqualified
     * names in whatever namespace the caller happens to have first, which is the standing
     * advice against it and what Supabase's own linter reports as
     * {@code function_search_path_mutable}. {@code tms.set_updated_at()} was written without one
     * in V1 and fires on every update in the schema; V50 replaced it with the pin
     * {@code tms.current_company_id()} has carried since V13.
     *
     * <p>Only the <em>last</em> definition of each function counts, because that is the one the
     * database ends up with: V1's unpinned {@code set_updated_at} is superseded by V50's, and a
     * rule that read every definition would forbid ever correcting one.
     */
    @Test
    @DisplayName("the final definition of every function pins its search_path")
    void everyFunctionPinsItsSearchPath() {
        Pattern function = Pattern.compile(
                "create\\s+(?:or\\s+replace\\s+)?function\\s+(tms\\.[a-z_]+)\\s*\\([^)]*\\)(.*?)\\bas\\s+\\$\\$",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        // SCRIPTS is ordered by version, so the last definition seen wins - as it does in the
        // database.
        java.util.Map<String, String> lastDefinition = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> definedIn = new java.util.LinkedHashMap<>();
        for (Path script : SCRIPTS) {
            String sql = MigrationScripts.withoutComments(MigrationScripts.read(script)).toLowerCase(Locale.ROOT);
            function.matcher(sql).results().forEach(match -> {
                lastDefinition.put(match.group(1), match.group(2));
                definedIn.put(match.group(1), script.getFileName().toString());
            });
        }

        assertThat(lastDefinition).as("the history is expected to define functions").isNotEmpty();
        lastDefinition.forEach((name, header) -> assertThat(header)
                .as("%s (%s) does not pin its search_path. Add "
                        + "'SET search_path = pg_catalog, pg_temp' as tms.current_company_id() "
                        + "does, so the body cannot resolve a name out of the caller's namespace",
                        name, definedIn.get(name))
                .contains("set search_path"));
    }

    @Test
    @DisplayName("Flyway is the only migration history: supabase/migrations does not exist")
    void supabaseCarriesNoParallelMigrationHistory() {
        Path supabase = MigrationScripts.repositoryRoot().resolve("supabase");

        assertThat(Files.isDirectory(supabase)).as("supabase/ is expected to exist").isTrue();
        assertThat(Files.exists(supabase.resolve("migrations")))
                .as("supabase/migrations must not exist - Flyway owns application DDL (ADR-002)")
                .isFalse();
    }
}
