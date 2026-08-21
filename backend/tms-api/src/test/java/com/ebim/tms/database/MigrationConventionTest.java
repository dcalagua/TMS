package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
