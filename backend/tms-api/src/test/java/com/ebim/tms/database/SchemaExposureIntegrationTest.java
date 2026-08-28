package com.ebim.tms.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Proves the exposure decision documented in {@code docs/security/RLS_STRATEGY.md}: the
 * {@code tms} schema is backend-only, nobody but its owner and the non-owner runtime role
 * holds a privilege on it, and Row Level Security is enabled on every table - with tenant
 * policies on business data (ADR-005) and none at all for the Supabase API roles, so any
 * accidental exposure denies instead of leaking.
 *
 * <p>The Supabase API roles ({@code anon}, {@code authenticated}) do not exist on a plain
 * PostgreSQL container, so the tests create them and then assert that the schema still
 * refuses them. That reproduces the platform situation instead of assuming it.
 */
@EnabledIf(value = DockerAvailability.CONDITION, disabledReason = DockerAvailability.DISABLED_REASON)
class SchemaExposureIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static final List<String> APPLICATION_TABLES = List.of(
            "app_user", "organization", "company", "role", "permission",
            "role_permission", "membership", "membership_role", "origin", "zone",
            "location", "location_role", "location_frequency",
            "destination", "frequency", "frequency_weekly_rule", "frequency_exception",
            "route", "route_stop", "carrier", "vehicle_type", "vehicle",
            "transport_order", "transport_order_line", "order_import_batch",
            "integration_client", "integration_client_scope", "integration_request",
            "planning_run", "trip", "trip_stop", "trip_order_assignment",
            "shipment_outbox_event", "import_batch",
            // Append-only logs: SELECT and INSERT only, UPDATE/DELETE revoked from tms_app.
            "audit_event", "transport_event", "delivery_evidence",
            "driver", "trip_exception", "order_delivery", "tracking_position",
            "rate_card", "trip_cost", "trip_cost_component", "trip_tender", "notification",
            // V34: the one settings row per company. V35: outbound webhooks.
            "company_settings", "webhook_subscription", "webhook_subscription_event",
            "webhook_delivery", "webhook_delivery_attempt",
            // V38: the routing cache. A cache rather than a record - rows are disposable and
            // tms_app may DELETE them - but company-scoped like everything else, because the
            // coordinates that key it are a tenant's master data.
            "travel_estimate",
            // V40: the tender waterfall and its ranked candidates. No DELETE grant: who was offered
            // a shipment, and in what order, is exactly what a carrier disputing a rate asks for.
            "tender_waterfall", "tender_waterfall_candidate",
            // V41: dock scheduling. The appointment has no DELETE grant - who booked which door and
            // what happened is what a carrier disputing a detention charge asks for; it is
            // cancelled, never removed. The three master tables are ordinary master data.
            "location_resource", "resource_calendar", "resource_blocked_slot", "appointment",
            // V42: fleet availability. Both carry a DELETE grant, unlike the appointment: lifting a
            // block is not an operational outcome to keep, it is a correction, and the decision
            // survives on the audit trail (RESOURCE_BLOCKED / RESOURCE_RELEASED).
            "resource_unavailability", "driver_shift",
            // V45: what arrived, per order line. No DELETE grant, matching order_delivery itself -
            // what was signed for is a commercial fact somebody may be invoiced or credited
            // against, so a line entered by mistake is corrected to zero and never erased.
            "order_delivery_line",
            // V46: freight audit. The approval and the export carry no UPDATE or DELETE grant - a
            // record of a decision that can be edited is not a record, and an export that can be
            // deleted is an obligation somebody can make disappear.
            "carrier_invoice", "carrier_invoice_line", "tolerance_policy", "freight_match",
            "freight_discrepancy", "settlement_approval", "payable_export",
            // V47: a driver and vehicle's day. Both take the full grant - a day is rewritten all
            // morning as shipments are added, dropped and reordered.
            "work_assignment", "work_assignment_trip");

    /**
     * The tables whose rows belong to a company and are therefore filtered by RLS for the
     * runtime role (ADR-005). Identity and authorization-catalogue tables are excluded on
     * purpose: they are read before a company scope exists, so they carry the explicit
     * {@code p_backend_managed} policy instead.
     *
     * <p>{@code audit_event} (V22), {@code transport_event} (V27) and {@code delivery_evidence}
     * (V28) are absent for a different reason and are not an omission: all three are append-only,
     * so instead of one {@code FOR ALL} policy they carry {@code p_tenant_company_scope_select} and
     * {@code p_tenant_company_scope_insert}. The query below matches the exact name, so they
     * cannot appear in its result - {@link #everyCompanyColumnIsPoliced()} is what covers them.
     */
    private static final List<String> TENANT_SCOPED_TABLES = List.of(
            "origin", "zone", "location", "location_role", "location_frequency",
            "destination", "frequency", "frequency_weekly_rule",
            "frequency_exception", "route", "route_stop", "carrier", "vehicle_type", "vehicle",
            "transport_order", "transport_order_line", "order_import_batch",
            "integration_client", "integration_client_scope", "integration_request",
            "planning_run", "trip", "trip_stop", "trip_order_assignment",
            "shipment_outbox_event", "import_batch", "driver", "trip_exception",
            "order_delivery", "tracking_position", "rate_card", "trip_cost", "trip_cost_component",
            "trip_tender", "notification",
            "company_settings", "webhook_subscription", "webhook_subscription_event",
            "webhook_delivery", "webhook_delivery_attempt", "travel_estimate",
            "tender_waterfall", "tender_waterfall_candidate",
            "location_resource", "resource_calendar", "resource_blocked_slot", "appointment",
            "resource_unavailability", "driver_shift",
            "order_delivery_line",
            // V46: freight audit. The approval and the export carry no UPDATE or DELETE grant - a
            // record of a decision that can be edited is not a record, and an export that can be
            // deleted is an obligation somebody can make disappear.
            "carrier_invoice", "carrier_invoice_line", "tolerance_policy", "freight_match",
            "freight_discrepancy", "settlement_approval", "payable_export",
            // V47: a driver and vehicle's day. Both take the full grant - a day is rewritten all
            // morning as shipments are added, dropped and reordered.
            "work_assignment", "work_assignment_trip");

    /**
     * The only tables allowed to carry a {@code company_id} and <em>not</em> the tenant policy.
     *
     * <p>{@code membership} is the join between an {@code app_user} and a company, and it is read
     * by {@code PrincipalResolutionService} in order to <em>decide</em> which companies the caller
     * may select - before any company scope exists. A tenant policy on it would make
     * authentication impossible, so it carries {@code p_backend_managed} like the rest of the
     * identity tables (V13 section 5).
     */
    private static final List<String> COMPANY_COLUMN_WITHOUT_TENANT_POLICY = List.of("membership");

    private static String jdbcUrl;

    private Connection connection;

    @BeforeAll
    static void migrate() {
        jdbcUrl = PostgresTestDatabase.createMigratedDatabase("tms_exposure");
    }

    @BeforeEach
    void openTransaction() throws SQLException {
        connection = PostgresTestDatabase.connect(jdbcUrl);
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    @Test
    @DisplayName("every application table exists and has Row Level Security enabled")
    void rowLevelSecurityIsEnabledEverywhere() throws SQLException {
        List<String> withoutRls = strings("""
                SELECT c.relname
                FROM pg_class c
                WHERE c.relnamespace = 'tms'::regnamespace
                  AND c.relkind = 'r'
                  AND c.relname <> 'flyway_schema_history'
                  AND NOT c.relrowsecurity
                ORDER BY 1
                """);

        assertThat(withoutRls).as("tables missing ENABLE ROW LEVEL SECURITY").isEmpty();
        assertThat(strings("""
                SELECT c.relname FROM pg_class c
                WHERE c.relnamespace = 'tms'::regnamespace AND c.relkind = 'r' AND c.relrowsecurity
                ORDER BY 1
                """)).containsExactlyInAnyOrderElementsOf(APPLICATION_TABLES);
    }

    @Test
    @DisplayName("the migrating role may actually SET ROLE tms_app, not merely administer it")
    void theRuntimeRoleCanBeEntered() throws SQLException {
        // Since PostgreSQL 16 creating a role grants ADMIN OPTION but not the SET option, so
        // this is not implied by V13 having created tms_app. The distinction is invisible on a
        // superuser connection - and the Testcontainers user is one - which is precisely why it
        // is asserted on the catalogue rather than by attempting SET ROLE and seeing it pass.
        assertThat(bool("""
                SELECT m.set_option
                FROM pg_auth_members m
                JOIN pg_roles r ON r.oid = m.roleid
                JOIN pg_roles g ON g.oid = m.member
                WHERE r.rolname = 'tms_app' AND g.rolname = current_user
                """))
                .as("without the SET option the backend cannot enter the runtime role, and "
                        + "every company-scoped request fails with 'permission denied to set "
                        + "role tms_app' on any cluster where it is not a superuser")
                .isTrue();
    }

    @Test
    @DisplayName("every company-scoped table carries the tenant policy (ADR-005)")
    void businessTablesCarryTheTenantPolicy() throws SQLException {
        assertThat(strings("""
                SELECT tablename FROM pg_policies
                WHERE schemaname = 'tms' AND policyname = 'p_tenant_company_scope'
                ORDER BY 1
                """))
                .as("a company-scoped table without a tenant policy is readable across "
                        + "tenants by the runtime role; add the policy in the migration that "
                        + "creates the table")
                .containsExactlyInAnyOrderElementsOf(TENANT_SCOPED_TABLES);
    }

    /**
     * The self-maintaining half of the guard above.
     *
     * <p>{@link #businessTablesCarryTheTenantPolicy()} compares against a hand-written list, which
     * is precise but only as current as the last person to edit it - and a list that goes stale
     * while Docker is unavailable stops guarding anything, silently. This test asks PostgreSQL the
     * structural question instead: <em>every</em> table carrying a {@code company_id} must carry
     * the tenant policy, whatever it is called and whenever it was added. A table introduced in
     * some future migration that forgets its policy fails here without anyone remembering to
     * update a constant.
     */
    @Test
    @DisplayName("any table with a company_id carries the tenant policy, list or no list (ADR-005)")
    void everyCompanyColumnIsPoliced() throws SQLException {
        List<String> unpoliced = strings("""
                SELECT c.relname
                FROM pg_class c
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'company_id'
                                   AND a.attnum > 0 AND NOT a.attisdropped
                WHERE c.relnamespace = 'tms'::regnamespace
                  AND c.relkind = 'r'
                  AND NOT EXISTS (SELECT 1 FROM pg_policies p
                                   WHERE p.schemaname = 'tms' AND p.tablename = c.relname
                                     -- LIKE, not '=': an append-only table splits the policy into
                                     -- p_tenant_company_scope_select and _insert because it has no
                                     -- UPDATE/DELETE grant left for a FOR ALL policy to guard
                                     -- (tms.audit_event V22, tms.transport_event V27). The filter
                                     -- the tenant predicate applies is identical either way, which
                                     -- is what this test is actually asking about.
                                     AND p.policyname LIKE 'p_tenant_company_scope%')
                ORDER BY 1
                """);

        assertThat(unpoliced)
                .as("a table storing a company_id without p_tenant_company_scope is readable "
                        + "across tenants by the runtime role; add the policy in the migration "
                        + "that creates the table, or justify it in "
                        + "COMPANY_COLUMN_WITHOUT_TENANT_POLICY")
                .containsExactlyInAnyOrderElementsOf(COMPANY_COLUMN_WITHOUT_TENANT_POLICY);
    }

    /**
     * No table may be left with RLS enabled and no policy at all. That combination denies every
     * row to {@code tms_app}, so it does not leak - but it breaks the feature instead, and it
     * would do so only on a deployment where the runtime role is actually entered. Failing here
     * turns a production-only outage into a test failure.
     */
    @Test
    @DisplayName("every table carries exactly one of the two policies - none is left policy-less")
    void noTableIsLeftWithoutAPolicy() throws SQLException {
        assertThat(strings("""
                SELECT c.relname
                FROM pg_class c
                WHERE c.relnamespace = 'tms'::regnamespace
                  AND c.relkind = 'r'
                  AND c.relname <> 'flyway_schema_history'
                  AND NOT EXISTS (SELECT 1 FROM pg_policies p
                                   WHERE p.schemaname = 'tms' AND p.tablename = c.relname)
                ORDER BY 1
                """))
                .as("RLS is enabled on every table, so a table with no policy reads zero rows "
                        + "for the runtime role: add p_tenant_company_scope for business data or "
                        + "p_backend_managed for the identity catalogue")
                .isEmpty();
    }

    @Test
    @DisplayName("no policy is granted to the Supabase API roles: the Data API stays denied")
    void policiesTargetTheRuntimeRoleOnly() throws SQLException {
        assertThat(strings("""
                SELECT DISTINCT unnest(roles)::text FROM pg_policies WHERE schemaname = 'tms'
                ORDER BY 1
                """))
                .as("V13 grants tenant access to the non-owner runtime role only; a policy "
                        + "naming anon/authenticated/service_role - or PUBLIC - would open the "
                        + "Data API path that ADR-004 closes")
                .containsExactly("tms_app");
    }

    @Test
    @DisplayName("RLS is not forced, so the owning application role keeps working by design")
    void rlsIsNotForcedForTheOwner() throws SQLException {
        assertThat(strings("""
                SELECT relname FROM pg_class
                WHERE relnamespace = 'tms'::regnamespace AND relkind = 'r' AND relforcerowsecurity
                ORDER BY 1
                """))
                .as("forcing RLS would lock out the backend connection, whose authorization is "
                        + "Spring Boot's responsibility (architecture section 4.2)")
                .isEmpty();
    }

    @Test
    @DisplayName("PUBLIC holds no privilege on the schema, its tables or its functions")
    void publicHasNothing() throws SQLException {
        assertThat(bool("SELECT has_schema_privilege('public', 'tms', 'USAGE')")).isFalse();
        assertThat(bool("SELECT has_function_privilege('public', 'tms.set_updated_at()', 'EXECUTE')")).isFalse();
        for (String table : APPLICATION_TABLES) {
            assertThat(bool("SELECT has_table_privilege('public', 'tms." + table + "', 'SELECT')"))
                    .as("PUBLIC must not be able to read tms.%s", table)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Supabase API roles cannot reach the schema even if the platform creates them")
    void supabaseApiRolesAreDenied() throws SQLException {
        execute("CREATE ROLE anon NOLOGIN");
        execute("CREATE ROLE authenticated NOLOGIN");

        for (String apiRole : List.of("anon", "authenticated")) {
            assertThat(bool("SELECT has_schema_privilege('" + apiRole + "', 'tms', 'USAGE')"))
                    .as("%s must not have USAGE on schema tms", apiRole)
                    .isFalse();
            assertThat(bool("SELECT has_table_privilege('" + apiRole + "', 'tms.organization', 'SELECT')"))
                    .as("%s must not be able to read tenant data", apiRole)
                    .isFalse();
        }

        // And it is a real refusal at query time, not only a catalogue statement.
        execute("SET LOCAL ROLE anon");
        Throwable denied = catchThrowable(() -> execute("SELECT id FROM tms.organization"));
        assertThat(denied).isInstanceOf(SQLException.class);
        assertThat(((SQLException) denied).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    @DisplayName("application tables live outside public, where the Supabase Data API looks")
    void nothingIsPublishedInThePublicSchema() throws SQLException {
        assertThat(strings("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name IN ('app_user','organization','company','role','permission',
                                     'role_permission','membership','membership_role',
                                     'origin','zone','destination','frequency',
                                     'frequency_weekly_rule','frequency_exception',
                                     'route','route_stop','carrier','vehicle_type','vehicle',
                                     'transport_order','transport_order_line')
                ORDER BY 1
                """)).isEmpty();
    }

    // --- helpers -----------------------------------------------------------------

    private List<String> strings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }

    private boolean bool(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getBoolean(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
