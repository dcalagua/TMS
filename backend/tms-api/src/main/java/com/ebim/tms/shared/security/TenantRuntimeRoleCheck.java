package com.ebim.tms.shared.security;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Says out loud, once per boot, which database role the application actually connected as and
 * whether it can enter the ADR-005 runtime role.
 *
 * <h2>Why this exists</h2>
 *
 * <p>ADR-005 puts a real tenant boundary in PostgreSQL, but only for a connection that is
 * <em>not</em> the schema owner: {@link TenantScopedDataSource} issues {@code SET ROLE tms_app}
 * per company-scoped request, and the policies of {@code V13} apply to that role. Two
 * deployment mistakes silently or noisily undo it, and until this class neither announced
 * itself:
 *
 * <ol>
 *   <li><b>The runtime role cannot be entered.</b> {@code V13} grants {@code tms_app} to
 *       {@code CURRENT_USER} - the role that <em>applied the migration</em>. A deployment whose
 *       runtime credential differs from the one that migrated, or one restored onto a project
 *       where that grant never happened, fails every company-scoped request with
 *       "permission denied to set role tms_app" while every migration still applies cleanly and
 *       {@code /actuator/health} still answers UP. On a Testcontainers run the migrating role is
 *       a superuser, which may set any role, so the difference appears only in a real project -
 *       exactly the DEV/QAS drift this check is meant to make visible.</li>
 *   <li><b>The application connected as the runtime role itself.</b> Then Flyway is not running
 *       as the schema owner, and {@code SET ROLE} is a no-op rather than a downgrade.</li>
 * </ol>
 *
 * <p>{@code docs/operations/DEPLOYMENT.md} asks an operator to "confirm the application connects
 * as {@code tms_app}" before the first deployment. Nothing in the process answered that
 * question, and the question as written cannot be answered yes: {@code tms_app} is
 * {@code NOLOGIN} and passwordless by design ({@code V13}, {@code docs/security/RLS_STRATEGY.md}
 * section 4), so the application connects as the owner and <em>enters</em> {@code tms_app}. This
 * check reports the two facts that actually decide whether RLS is in force, in one log line, on
 * the deployment itself.
 *
 * <h2>Why it probes rather than inspects the catalogue</h2>
 *
 * <p>It performs the real {@code SET ROLE} instead of asking {@code pg_has_role}, for two
 * reasons: the member privilege that {@code SET ROLE} requires is spelled differently across
 * PostgreSQL versions, and a catalogue answer is a proxy for the operation while the operation
 * is free. What it reports is therefore what the next request will experience.
 *
 * <h2>Why it never fails startup</h2>
 *
 * <p>Refusing to boot would turn a defence-in-depth misconfiguration into an outage, and would
 * do it on the deployment least able to investigate. The application's own company scoping
 * (ADR-003) is the primary tenant control and is unaffected either way. This check is
 * observability, so every failure inside it - including a database that is not PostgreSQL - is
 * logged and swallowed.
 */
@Component
public class TenantRuntimeRoleCheck {

    private static final Logger log = LoggerFactory.getLogger(TenantRuntimeRoleCheck.class);

    private final ObjectProvider<DataSource> dataSource;

    public TenantRuntimeRoleCheck(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs after the context is up, so it costs nothing on the startup path and cannot deadlock
     * with the pool still being built. The thread carries no security context, so
     * {@link TenantScopedDataSource} hands the connection over untouched and what is read is the
     * login role itself.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        DataSource source = dataSource.getIfUnique();
        if (source == null) {
            return;
        }
        try (Connection connection = source.getConnection()) {
            Roles roles = readRoles(connection);
            Outcome outcome = diagnose(roles, canEnterRuntimeRole(connection));
            outcome.logTo(log, roles);
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("Could not determine the database role this application connected as, so it "
                    + "is unknown whether ADR-005 row level security is in force. Cause: {}",
                    unreadable.toString());
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
     * Enters and leaves the runtime role on a throwaway statement. {@code RESET ROLE} is issued
     * whatever happens: this connection goes back to the pool, and one left inside
     * {@code tms_app} would serve the next unscoped caller - Flyway's data migrations, principal
     * resolution - under the policies, which is the failure mode {@code TenantScopedDataSource}
     * resets a pooled connection to prevent.
     */
    private static boolean canEnterRuntimeRole(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            try {
                statement.execute("SET ROLE " + TenantScopedDataSource.RUNTIME_ROLE);
            } catch (SQLException refused) {
                log.debug("SET ROLE {} was refused during the startup check",
                        TenantScopedDataSource.RUNTIME_ROLE, refused);
                return false;
            }
            statement.execute("RESET ROLE");
            return true;
        } catch (SQLException failure) {
            log.debug("The runtime role probe could not complete", failure);
            return false;
        }
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
         * on {@code SET ROLE}, so the message names the fix rather than only the symptom.
         */
        RUNTIME_ROLE_UNREACHABLE {
            @Override
            void logTo(Logger logger, Roles roles) {
                logger.error("Database roles: session_user={}, current_user={}. This connection "
                        + "CANNOT enter '{}', so every company-scoped request will fail with "
                        + "'permission denied to set role'. V13 grants the role to whichever role "
                        + "applied the migration; a deployment whose runtime credential differs "
                        + "must also run: GRANT {} TO \"{}\" WITH SET TRUE; "
                        + "(see ADR-005 and docs/security/RLS_STRATEGY.md).",
                        roles.sessionUser(), roles.currentUser(), TenantScopedDataSource.RUNTIME_ROLE,
                        TenantScopedDataSource.RUNTIME_ROLE, roles.sessionUser());
            }
        },

        /**
         * The deployment took {@code docs/operations/DEPLOYMENT.md} literally and gave the
         * application the runtime role as its login. Flyway then owns nothing, and {@code SET ROLE}
         * changes nothing, so the downgrade ADR-005 relies on has quietly become a no-op.
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
