package com.ebim.tms.shared.security;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Decides which database role the application actually connected as and whether it can enter the
 * ADR-005 runtime role - says so in the log, and hands the verdict to
 * {@link TenantRuntimeRoleHealthIndicator} so that readiness reflects it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>ADR-005 puts a real tenant boundary in PostgreSQL, but only for a connection that is
 * <em>not</em> the schema owner: {@link TenantScopedDataSource} issues {@code SET ROLE tms_app}
 * per company-scoped request, and the policies of {@code V13} apply to that role. Two
 * deployment mistakes silently or noisily undo it:
 *
 * <ol>
 *   <li><b>The runtime role cannot be entered.</b> {@code V13} grants {@code tms_app} to
 *       {@code CURRENT_USER} - the role that <em>applied the migration</em> - and creates the role
 *       itself. A deployment whose runtime credential differs from the one that migrated, or a
 *       database restored from {@code pg_dump} onto a cluster where the role never existed
 *       ({@code pg_dump} carries no global roles, and Flyway reports the schema up to date so
 *       {@code V13} never runs again), fails every company-scoped request with
 *       {@code permission denied to set role} or {@code role "tms_app" does not exist}. On a
 *       Testcontainers run the migrating role is a superuser, which may set any role, so the
 *       difference appears only in a real project.</li>
 *   <li><b>The application connected as the runtime role itself.</b> Then Flyway is not running
 *       as the schema owner, and {@code SET ROLE} is a no-op rather than a downgrade.</li>
 * </ol>
 *
 * <h2>Why it probes rather than inspects the catalogue</h2>
 *
 * <p>It performs the real {@code SET ROLE} instead of asking {@code pg_has_role}, for two
 * reasons: the member privilege that {@code SET ROLE} requires is spelled differently across
 * PostgreSQL versions, and a catalogue answer is a proxy for the operation while the operation
 * is cheap. What it reports is therefore what the next request will experience.
 *
 * <h2>It still never fails startup - but readiness now follows the verdict</h2>
 *
 * <p>The first version of this class only logged, on the argument that refusing to boot would
 * turn a defence-in-depth misconfiguration into an outage while ADR-003's scoping carries on
 * regardless. Half of that argument survived contact with a real restore and half did not.
 *
 * <p>The restore of 2026-09-15 (certification, day 6) put a {@code pg_dump} on a fresh cluster
 * without {@code tms_app}. Flyway said {@code Schema "tms" is up to date}, this class logged
 * {@code ERROR ... This connection CANNOT enter 'tms_app'}, and
 * {@code GET /actuator/health/readiness} still answered {@code 200 {"status":"UP"}}. The premise
 * was wrong: an unenterable runtime role is not "RLS switched off", it is <em>every company-scoped
 * request failing with a 500</em> - the outage already exists, and a green readiness probe only
 * tells the load balancer (Render's {@code healthCheckPath}) to route traffic to it instead of
 * keeping the previous instance serving.
 *
 * <p>So the posture is now published as a health contributor. What stays from the original
 * decision:
 *
 * <ul>
 *   <li><b>Startup is never failed.</b> The process stays up so the deployment can be diagnosed
 *       on the instance itself, and liveness stays UP - restarting it would change nothing, since
 *       the fix is a {@code GRANT} or a {@code CREATE ROLE} in the database.</li>
 *   <li><b>Connecting <em>as</em> {@code tms_app} stays a warning, not a readiness failure.</b>
 *       Company-scoped requests succeed in that posture and every query already runs as a
 *       non-owner, so the tenant policies are, if anything, applied more widely. It is a
 *       configuration defect to fix, not an instance that cannot serve; taking it out of rotation
 *       would be exactly the manufactured outage the original argument warned about.</li>
 *   <li><b>A probe that cannot reach the database proves nothing about the role.</b> A connection
 *       failure, a pool timeout or a server shutting down is reported by the database health
 *       indicator; repeating it here as "cannot enter the runtime role" would send an operator to
 *       run a {@code GRANT} during a network outage. Such a probe is inconclusive: the previous
 *       conclusive verdict stands, and before any there is none.</li>
 * </ul>
 *
 * <h2>When it is evaluated</h2>
 *
 * <p>Once, synchronously, when the application is ready - before readiness starts accepting
 * traffic - and afterwards lazily, when the health indicator is asked and the last evaluation is
 * older than {@link #REEVALUATION_INTERVAL}. Evaluating only at startup would leave an instance out
 * of rotation forever after an operator fixed the grant in place; probing on every health request
 * would put three round trips and a pooled connection on a path a load balancer polls as often as
 * it likes. Thirty seconds bounds both: a hot fix is picked up within half a minute plus two
 * balancer intervals, and the probe costs at most two connection borrows a minute per instance
 * whatever the polling rate. There is no scheduler - an instance nobody asks is never probed - and
 * a re-evaluation runs on its own virtual thread, one at a time, while the health request is
 * answered with the verdict already in hand. A health request never waits on the pool: against a
 * stopped database the probe sits out Hikari's thirty-second connection timeout, and a probe
 * request held that long would fail at the balancer for a reason this indicator exists not to
 * report. See {@link TenantRuntimeRoleVerdicts}.
 *
 * <p>The log follows transitions, not evaluations: the first conclusive verdict is logged (the
 * {@code Database roles:} line operators look for on every start), and afterwards a line is
 * written only when the verdict changes - including the recovery - so a broken deployment does not
 * write an ERROR every thirty seconds.
 */
@Component
public class TenantRuntimeRoleCheck {

    static final Logger log = LoggerFactory.getLogger(TenantRuntimeRoleCheck.class);

    /** The longest a verdict is served before the next health request probes again. */
    static final Duration REEVALUATION_INTERVAL = Duration.ofSeconds(30);

    /** How long a connection that failed a statement is given to prove it is still alive. */
    private static final int VALIDITY_TIMEOUT_SECONDS = 2;

    private final TenantRuntimeRoleVerdicts verdicts;

    public TenantRuntimeRoleCheck(ObjectProvider<DataSource> dataSource) {
        this.verdicts = new TenantRuntimeRoleVerdicts(() -> probe(dataSource.getIfUnique()),
                Clock.systemUTC(), REEVALUATION_INTERVAL, TenantRuntimeRoleVerdicts.virtualThreads());
    }

    /**
     * Runs after the context is up, so it costs nothing on the startup path and cannot deadlock
     * with the pool still being built. Always probes, whatever an earlier health request did.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        verdicts.evaluateNow();
    }

    /**
     * The latest conclusive verdict, scheduling a background re-probe when it is due - never
     * waiting for one. Empty when no probe has
     * ever concluded - no {@link DataSource}, or a database that has not been reachable since this
     * process started. See {@link TenantRuntimeRoleVerdicts}.
     */
    Optional<Outcome> currentOutcome() {
        return verdicts.current();
    }

    /**
     * Reads the two facts from the database. The thread of a health request may carry a company
     * scope, in which case {@link TenantScopedDataSource} would itself try to enter the role and
     * throw; the probe therefore asks the pool underneath it, and what it reads is the login role.
     */
    static Probe probe(DataSource source) {
        if (source == null) {
            return new Probe.Inconclusive("no single DataSource in this application context");
        }
        DataSource target = source;
        if (source instanceof TenantScopedDataSource scoped && scoped.getTargetDataSource() != null) {
            target = scoped.getTargetDataSource();
        }
        try (Connection connection = target.getConnection()) {
            Roles roles = readRoles(connection);
            return new Probe.Concluded(roles, diagnose(roles, canEnterRuntimeRole(connection)));
        } catch (SQLException | RuntimeException unreadable) {
            return new Probe.Inconclusive(unreadable.toString());
        }
    }

    private static Roles readRoles(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT session_user, current_user")) {
            if (!result.next()) {
                throw new SQLException("SELECT session_user, current_user returned no row");
            }
            return new Roles(result.getString(1), result.getString(2));
        }
    }

    /**
     * Enters and leaves the runtime role on a throwaway statement.
     *
     * <p>Returns {@code false} only when PostgreSQL <em>refused</em> the role - {@code 42501}
     * when it exists but was not granted, {@code 22023} when it does not exist at all, which is
     * the restored-dump case - on a connection that is still alive. Anything that looks like the
     * connection or the server failing is thrown, and the caller treats it as inconclusive.
     *
     * <p>{@code RESET ROLE} must succeed before the connection goes back to the pool: one left
     * inside {@code tms_app} would serve the next unscoped caller - principal resolution, the
     * background jobs - under the policies, which is the failure mode
     * {@link TenantScopedDataSource} resets a pooled connection to prevent. If it fails, the
     * physical connection is aborted instead of returned.
     */
    static boolean canEnterRuntimeRole(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try {
                statement.execute("SET ROLE " + TenantScopedDataSource.RUNTIME_ROLE);
            } catch (SQLException refused) {
                if (isConnectionFailure(refused, isStillValid(connection))) {
                    throw refused;
                }
                log.debug("SET ROLE {} was refused (SQLState {})",
                        TenantScopedDataSource.RUNTIME_ROLE, refused.getSQLState(), refused);
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
                return false;
            }
            try {
                statement.execute("RESET ROLE");
            } catch (SQLException resetFailure) {
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException | RuntimeException abortFailure) {
                    resetFailure.addSuppressed(abortFailure);
                }
                throw resetFailure;
            }
            return true;
        }
    }

    private static boolean isStillValid(Connection connection) {
        try {
            return connection.isValid(VALIDITY_TIMEOUT_SECONDS);
        } catch (SQLException | RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Whether a failed {@code SET ROLE} says something about the connection rather than about the
     * role. A pure function so that "a database outage is not reported as an unenterable role" is
     * a claim a unit test can check.
     *
     * <p>Classes {@code 08} (connection exception), {@code 53} (insufficient resources), {@code 57}
     * (operator intervention: cancellation, shutdown) and {@code 58} (system/I-O error) are the
     * server or the link failing; so is a connection that no longer validates, and so are the JDBC
     * connection exception types a pool throws. Everything else on a live connection is a refusal.
     * The rule is deliberately a list of what is <em>not</em> a refusal: missing a new refusal
     * code would put the day-6 incident back behind a neutral health answer, whereas missing a new
     * failure code at worst reports DOWN on an instance whose database is failing anyway.
     */
    static boolean isConnectionFailure(SQLException failure, boolean connectionStillValid) {
        if (failure instanceof SQLTransientConnectionException
                || failure instanceof SQLNonTransientConnectionException) {
            return true;
        }
        String state = failure.getSQLState();
        if (state != null && (state.startsWith("08") || state.startsWith("53")
                || state.startsWith("57") || state.startsWith("58"))) {
            return true;
        }
        return !connectionStillValid;
    }

    /**
     * The whole decision, as a pure function of the two facts read from the database, so that
     * "a deployment that cannot enter the runtime role is reported as an error" is a claim a unit
     * test can check without a database.
     */
    static Outcome diagnose(Roles roles, boolean canEnterRuntimeRole) {
        if (roles.isRuntimeRole()) {
            return Outcome.CONNECTED_AS_RUNTIME_ROLE;
        }
        return canEnterRuntimeRole ? Outcome.RLS_IN_FORCE : Outcome.RUNTIME_ROLE_UNREACHABLE;
    }

    /** What one probe established: either a verdict, or nothing about the role at all. */
    sealed interface Probe {

        record Concluded(Roles roles, Outcome outcome) implements Probe {
        }

        /** The database could not be asked. Carries the cause for the log only. */
        record Inconclusive(String cause) implements Probe {
        }
    }

    /** The login role of the connection and the role it is currently acting as. */
    record Roles(String sessionUser, String currentUser) {

        boolean isRuntimeRole() {
            return sessionUser != null
                    && TenantScopedDataSource.RUNTIME_ROLE.equals(sessionUser.toLowerCase(Locale.ROOT));
        }
    }

    /** What the two facts mean for the tenant boundary, and how loudly to say it. */
    enum Outcome {

        /**
         * The expected posture: an owner connection that can drop into the non-owner role for the
         * work of a company-scoped request.
         */
        RLS_IN_FORCE {
            @Override
            void logTo(Logger logger, Roles roles) {
                logger.info("Database roles: session_user={}, current_user={}. '{}' can be entered, "
                        + "so ADR-005 row level security applies to every company-scoped request.",
                        roles.sessionUser(), roles.currentUser(), TenantScopedDataSource.RUNTIME_ROLE);
            }
        },

        /**
         * Not a degraded boundary but a broken deployment: every company-scoped request will fail
         * on {@code SET ROLE}, so the message names the fix rather than only the symptom, and
         * readiness reports DOWN.
         */
        RUNTIME_ROLE_UNREACHABLE {
            @Override
            void logTo(Logger logger, Roles roles) {
                logger.error("Database roles: session_user={}, current_user={}. This connection "
                        + "CANNOT enter '{}', so every company-scoped request will fail with "
                        + "'permission denied to set role' (or 'role does not exist' after a restore "
                        + "onto a cluster without it - pg_dump carries no roles). Readiness reports "
                        + "DOWN until this is fixed. V13 creates the role and grants it to whichever "
                        + "role applied the migration; a deployment whose runtime credential differs "
                        + "must also run: GRANT {} TO \"{}\" WITH SET TRUE; "
                        + "(see ADR-005 and docs/security/RLS_STRATEGY.md).",
                        roles.sessionUser(), roles.currentUser(), TenantScopedDataSource.RUNTIME_ROLE,
                        TenantScopedDataSource.RUNTIME_ROLE, roles.sessionUser());
            }
        },

        /**
         * The deployment gave the application the runtime role as its login. Flyway then owns
         * nothing, and {@code SET ROLE} changes nothing, so the downgrade ADR-005 relies on has
         * quietly become a no-op. Requests still succeed, so readiness stays UP; the log says it.
         */
        CONNECTED_AS_RUNTIME_ROLE {
            @Override
            void logTo(Logger logger, Roles roles) {
                logger.warn("Database roles: session_user={}, current_user={}. The application "
                        + "connected AS the runtime role. ADR-005 expects the schema owner here, "
                        + "with '{}' entered per company-scoped request; as configured, Flyway does "
                        + "not run as the owner and SET ROLE is a no-op. See "
                        + "docs/security/RLS_STRATEGY.md section 4.",
                        roles.sessionUser(), roles.currentUser(), TenantScopedDataSource.RUNTIME_ROLE);
            }
        };

        abstract void logTo(Logger logger, Roles roles);
    }
}
