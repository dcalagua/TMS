package com.ebim.tms.shared.config;

import java.util.Locale;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Refuses to migrate a database that is not on this machine while the {@code local} profile is
 * active.
 *
 * <p>This exists because it already happened. A developer `.env` pointing
 * {@code TMS_DB_URL} at a hosted Supabase project, with {@code TMS_FLYWAY_ENABLED=true}, was found
 * in the working tree twice - quarantined once, and back a day later. Nothing about starting the
 * application locally announces which database it is about to migrate, and Flyway does not ask:
 * the first sign would have been eleven new tables in a shared project. A convention ("remember to
 * check your .env") is not a control, and the accident is one keystroke wide.
 *
 * <p><b>What it does.</b> It replaces the migration step, looks at the JDBC URL the datasource was
 * actually built with, and lets Flyway run only if the host is this machine. Anything else stops
 * startup with a message naming the setting to change. It is deliberately the migration strategy
 * and not an ordinary bean: this is the one hook that is guaranteed to run <em>before</em> the
 * first statement is issued, so there is no window in which the schema has already been touched.
 *
 * <p><b>What it does not do.</b> It is not a security boundary - anyone who can edit the
 * environment can also set {@link #ALLOW_REMOTE}, which is the point: a deployment that genuinely
 * migrates a remote database says so once, on purpose, and a laptop never says it by accident.
 * It is scoped to the {@code local} profile because {@code prod} is where migrating a remote
 * database is the entire job.
 */
@Configuration
@Profile("local")
public class LocalProfileDatabaseGuard {

    private static final Logger log = LoggerFactory.getLogger(LocalProfileDatabaseGuard.class);

    /** Set to true to migrate a database that is not on this machine from the local profile. */
    static final String ALLOW_REMOTE = "TMS_ALLOW_REMOTE_DB";

    /**
     * The hosts that mean "this machine". {@code host.docker.internal} is here because a
     * containerised application reaching the developer's own PostgreSQL is still local - it is the
     * loopback address seen from inside a container, not somebody else's database.
     */
    private static final Set<String> LOCAL_HOSTS =
            Set.of("localhost", "127.0.0.1", "::1", "0.0.0.0", "host.docker.internal");

    @Bean
    FlywayMigrationStrategy localOnlyMigrationStrategy(Environment environment) {
        return flyway -> {
            String url = urlOf(flyway);
            String host = hostOf(url);
            if (isLocal(host) || allowsRemote(environment)) {
                if (!isLocal(host)) {
                    // Loud, because the developer asked for this and should see that they got it.
                    log.warn("Flyway is migrating the NON-LOCAL host '{}' because {} is set.", host, ALLOW_REMOTE);
                }
                flyway.migrate();
                return;
            }
            throw new IllegalStateException(String.format("""
                    Refusing to run Flyway against '%s' on the 'local' profile.

                    The local profile migrates a database on this machine. This datasource points \
                    somewhere else, which on a developer's laptop is almost always a .env left \
                    pointing at a shared project - and Flyway would have created the whole schema \
                    there without asking.

                    If that is what you meant, set %s=true for this run. If it is not, check \
                    TMS_DB_URL (backend/tms-api/.env.example is the local one) and see \
                    docs/development/DATABASE_SAFETY.md.""", host, ALLOW_REMOTE));
        };
    }

    /**
     * The URL Flyway will actually use, taken from Flyway's own configuration rather than from the
     * environment. The two can differ - a property is one thing, the datasource that got built is
     * another - and the one that matters is the connection about to be opened.
     */
    private static String urlOf(Flyway flyway) {
        String url = flyway.getConfiguration().getUrl();
        return url == null ? "" : url;
    }

    /**
     * The host inside a JDBC URL, lower-cased, or empty when there is none to find.
     *
     * <p>Parsed rather than pattern-matched for a reason: {@code jdbc:postgresql://localhost@evil}
     * is not localhost, and a naive {@code contains("localhost")} would wave it through. Anything
     * this cannot parse is treated as non-local, because "I could not tell" and "it is safe" are
     * different answers.
     */
    static String hostOf(String jdbcUrl) {
        int authorityStart = jdbcUrl.indexOf("//");
        if (authorityStart < 0) {
            return "";
        }
        String rest = jdbcUrl.substring(authorityStart + 2);
        int end = indexOfFirst(rest, '/', '?', ';');
        String authority = end < 0 ? rest : rest.substring(0, end);

        // user:password@host:port - the host is what follows the last '@', never what precedes it.
        int credentials = authority.lastIndexOf('@');
        String hostAndPort = credentials < 0 ? authority : authority.substring(credentials + 1);
        if (hostAndPort.startsWith("[")) {
            int closing = hostAndPort.indexOf(']');
            return closing < 0 ? "" : hostAndPort.substring(1, closing).toLowerCase(Locale.ROOT);
        }
        int port = hostAndPort.indexOf(':');
        return (port < 0 ? hostAndPort : hostAndPort.substring(0, port)).toLowerCase(Locale.ROOT);
    }

    static boolean isLocal(String host) {
        return LOCAL_HOSTS.contains(host);
    }

    private static boolean allowsRemote(Environment environment) {
        return Boolean.parseBoolean(environment.getProperty(ALLOW_REMOTE, "false"));
    }

    private static int indexOfFirst(String value, char... candidates) {
        int found = -1;
        for (char candidate : candidates) {
            int index = value.indexOf(candidate);
            if (index >= 0 && (found < 0 || index < found)) {
                found = index;
            }
        }
        return found;
    }
}
