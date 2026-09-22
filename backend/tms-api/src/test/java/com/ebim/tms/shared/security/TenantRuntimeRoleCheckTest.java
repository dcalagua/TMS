package com.ebim.tms.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.shared.security.TenantRuntimeRoleCheck.Outcome;
import com.ebim.tms.shared.security.TenantRuntimeRoleCheck.Probe;
import com.ebim.tms.shared.security.TenantRuntimeRoleCheck.Roles;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * What the runtime role check concludes from the facts it reads, how it tells a refused role from
 * an unreachable database, and how often it asks.
 *
 * <p>Unit tests on purpose, and for the same reason {@code SupabaseJwtDecodersTest} is one: the
 * interesting case - a deployment whose credential cannot enter {@code tms_app} - is precisely
 * the one no local run reproduces, because a Testcontainers superuser may set any role. If the
 * conclusion were only reachable by booting against a misconfigured project, it would be a
 * conclusion nobody ever checks. The SQLStates used below are the ones PostgreSQL 17 actually
 * returns for {@code SET ROLE tms_app}: {@code 22023} when the role does not exist, {@code 42501}
 * when it exists but was not granted.
 */
class TenantRuntimeRoleCheckTest {

    private static final Roles OWNER = new Roles("tms_owner", "tms_owner");

    @Nested
    @DisplayName("diagnosis")
    class Diagnosis {

        @Test
        @DisplayName("an owner connection that can enter the runtime role is the expected posture")
        void ownerThatCanEnterTheRuntimeRole() {
            assertThat(TenantRuntimeRoleCheck.diagnose(OWNER, true)).isEqualTo(Outcome.RLS_IN_FORCE);
        }

        @Test
        @DisplayName("an owner connection that cannot enter the runtime role is reported, not assumed away")
        void ownerThatCannotEnterTheRuntimeRole() {
            assertThat(TenantRuntimeRoleCheck.diagnose(OWNER, false))
                    .isEqualTo(Outcome.RUNTIME_ROLE_UNREACHABLE);
        }

        @Test
        @DisplayName("connecting as the runtime role itself is a distinct finding, not a success")
        void connectedAsTheRuntimeRole() {
            // SET ROLE trivially succeeds when you are already that role, so the probe alone would
            // read as healthy. It is not: Flyway would not be running as the schema owner and the
            // per-request downgrade ADR-005 relies on would have become a no-op.
            assertThat(TenantRuntimeRoleCheck.diagnose(new Roles("tms_app", "tms_app"), true))
                    .isEqualTo(Outcome.CONNECTED_AS_RUNTIME_ROLE);
        }

        @Test
        @DisplayName("PostgreSQL folds unquoted identifiers, so the role name is matched case-insensitively")
        void runtimeRoleIsRecognisedWhateverTheCase() {
            assertThat(new Roles("TMS_APP", "TMS_APP").isRuntimeRole()).isTrue();
            assertThat(new Roles("tms_owner", "tms_app").isRuntimeRole()).isFalse();
        }

        @Test
        @DisplayName("an unreadable session_user does not throw and does not read as the runtime role")
        void missingSessionUserIsNotTheRuntimeRole() {
            assertThat(new Roles(null, null).isRuntimeRole()).isFalse();
            assertThat(TenantRuntimeRoleCheck.diagnose(new Roles(null, null), false))
                    .isEqualTo(Outcome.RUNTIME_ROLE_UNREACHABLE);
        }
    }

    @Nested
    @DisplayName("a refused role is not a failed database")
    class RefusalVersusConnectionFailure {

        @Test
        @DisplayName("a role that does not exist (the restored pg_dump) is a refusal")
        void missingRoleIsARefusal() throws SQLException {
            Connection connection = ownerConnection(new SQLException(
                    "ERROR: role \"tms_app\" does not exist", "22023"), true);

            Probe probe = TenantRuntimeRoleCheck.probe(dataSourceFor(connection));

            assertThat(probe).isEqualTo(new Probe.Concluded(OWNER, Outcome.RUNTIME_ROLE_UNREACHABLE));
        }

        @Test
        @DisplayName("a role that exists but was not granted is a refusal")
        void ungrantedRoleIsARefusal() throws SQLException {
            Connection connection = ownerConnection(new SQLException(
                    "ERROR: permission denied to set role \"tms_app\"", "42501"), true);

            Probe probe = TenantRuntimeRoleCheck.probe(dataSourceFor(connection));

            assertThat(probe).isEqualTo(new Probe.Concluded(OWNER, Outcome.RUNTIME_ROLE_UNREACHABLE));
        }

        @Test
        @DisplayName("a pool that cannot hand out a connection says nothing about the role")
        void poolTimeoutIsInconclusive() throws SQLException {
            DataSource source = mock(DataSource.class);
            when(source.getConnection()).thenThrow(new SQLTransientConnectionException(
                    "tms-pool - Connection is not available, request timed out after 30000ms"));

            assertThat(TenantRuntimeRoleCheck.probe(source)).isInstanceOf(Probe.Inconclusive.class);
        }

        @Test
        @DisplayName("a link that breaks during SET ROLE says nothing about the role")
        void brokenLinkDuringSetRoleIsInconclusive() throws SQLException {
            Connection connection = ownerConnection(new SQLException(
                    "An I/O error occurred while sending to the backend.", "08006"), false);

            assertThat(TenantRuntimeRoleCheck.probe(dataSourceFor(connection)))
                    .isInstanceOf(Probe.Inconclusive.class);
        }

        @Test
        @DisplayName("a server shutting down during SET ROLE says nothing about the role")
        void adminShutdownIsInconclusive() throws SQLException {
            Connection connection = ownerConnection(new SQLException(
                    "FATAL: terminating connection due to administrator command", "57P01"), true);

            assertThat(TenantRuntimeRoleCheck.probe(dataSourceFor(connection)))
                    .isInstanceOf(Probe.Inconclusive.class);
        }

        @Test
        @DisplayName("any failure on a connection that no longer validates is not a refusal")
        void deadConnectionIsNotARefusal() {
            SQLException refusalLooking = new SQLException("permission denied", "42501");

            assertThat(TenantRuntimeRoleCheck.isConnectionFailure(refusalLooking, false)).isTrue();
            assertThat(TenantRuntimeRoleCheck.isConnectionFailure(refusalLooking, true)).isFalse();
            assertThat(TenantRuntimeRoleCheck.isConnectionFailure(new SQLException("x", "53300"), true))
                    .isTrue();
            assertThat(TenantRuntimeRoleCheck.isConnectionFailure(new SQLException("x", "58030"), true))
                    .isTrue();
        }

        @Test
        @DisplayName("no DataSource at all is inconclusive, not a verdict")
        void noDataSourceIsInconclusive() {
            assertThat(TenantRuntimeRoleCheck.probe(null)).isInstanceOf(Probe.Inconclusive.class);
        }

        @Test
        @DisplayName("an entered role is always left before the connection returns to the pool")
        void enteredRoleIsReset() throws SQLException {
            Connection connection = ownerConnection(null, true);

            assertThat(TenantRuntimeRoleCheck.probe(dataSourceFor(connection)))
                    .isEqualTo(new Probe.Concluded(OWNER, Outcome.RLS_IN_FORCE));
            Statement statement = connection.createStatement();
            verify(statement).execute("RESET ROLE");
            verify(connection, never()).abort(any());
        }

        @Test
        @DisplayName("a connection whose role could not be reset is aborted, never pooled as tms_app")
        void failedResetAbortsTheConnection() throws SQLException {
            Connection connection = ownerConnection(null, true);
            Statement statement = connection.createStatement();
            when(statement.execute("RESET ROLE")).thenThrow(new SQLException("reset failed", "08006"));

            assertThatThrownBy(() -> TenantRuntimeRoleCheck.canEnterRuntimeRole(connection))
                    .isInstanceOf(SQLException.class);
            verify(connection).abort(any());
        }

        @Test
        @DisplayName("the probe reads beneath the tenant-scoped wrapper, so a scoped request thread cannot trip it")
        void probeUnwrapsTheTenantScopedDataSource() throws SQLException {
            Connection connection = ownerConnection(null, true);
            DataSource pool = dataSourceFor(connection);
            SecurityContextHolder.getContext().setAuthentication(new ScopedAuthentication());
            try {
                assertThat(TenantRuntimeRoleCheck.probe(new TenantScopedDataSource(pool)))
                        .isEqualTo(new Probe.Concluded(OWNER, Outcome.RLS_IN_FORCE));
            } finally {
                SecurityContextHolder.clearContext();
            }
            // Through the wrapper, a scoped thread would have published the company first.
            verify(connection, never()).prepareStatement(anyString());
        }

        /**
         * A connection logged in as {@link #OWNER} whose {@code SET ROLE} throws {@code setRole}
         * (or succeeds when {@code null}) and whose {@code isValid} answers {@code valid}.
         */
        private Connection ownerConnection(SQLException setRole, boolean valid) throws SQLException {
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            ResultSet roles = mock(ResultSet.class);
            when(connection.createStatement()).thenReturn(statement);
            when(connection.isValid(anyInt())).thenReturn(valid);
            when(connection.getAutoCommit()).thenReturn(true);
            when(statement.executeQuery("SELECT session_user, current_user")).thenReturn(roles);
            when(roles.next()).thenReturn(true);
            when(roles.getString(1)).thenReturn(OWNER.sessionUser());
            when(roles.getString(2)).thenReturn(OWNER.currentUser());
            if (setRole != null) {
                when(statement.execute("SET ROLE tms_app")).thenThrow(setRole);
            }
            return connection;
        }

        private DataSource dataSourceFor(Connection connection) throws SQLException {
            DataSource source = mock(DataSource.class);
            when(source.getConnection()).thenReturn(connection);
            return source;
        }
    }

    @Nested
    @DisplayName("re-evaluation policy")
    class ReevaluationPolicy {

        private static final Duration INTERVAL = Duration.ofSeconds(30);
        /** Runs the re-evaluation on the calling thread, so each policy step is deterministic. */
        private static final Executor SYNCHRONOUS = Runnable::run;
        private static final Probe REFUSED = new Probe.Concluded(OWNER, Outcome.RUNTIME_ROLE_UNREACHABLE);
        private static final Probe ENTERED = new Probe.Concluded(OWNER, Outcome.RLS_IN_FORCE);
        private static final Probe UNREACHABLE_DATABASE = new Probe.Inconclusive("connection refused");

        private final MutableClock clock = new MutableClock(Instant.parse("2026-09-15T08:00:00Z"));

        @Test
        @DisplayName("startup evaluates once, and health requests inside the interval reuse that verdict")
        void verdictIsReusedInsideTheInterval() {
            ScriptedProbe probe = new ScriptedProbe(REFUSED);
            TenantRuntimeRoleVerdicts check = new TenantRuntimeRoleVerdicts(probe, clock, INTERVAL, SYNCHRONOUS);

            check.evaluateNow();
            clock.advance(Duration.ofSeconds(29));
            for (int i = 0; i < 100; i++) {
                assertThat(check.current()).contains(Outcome.RUNTIME_ROLE_UNREACHABLE);
            }

            assertThat(probe.calls()).isEqualTo(1);
        }

        @Test
        @DisplayName("a grant fixed in place brings the instance back after the interval, without a restart")
        void hotFixIsPickedUpAfterTheInterval() {
            ScriptedProbe probe = new ScriptedProbe(REFUSED, ENTERED);
            TenantRuntimeRoleVerdicts check = new TenantRuntimeRoleVerdicts(probe, clock, INTERVAL, SYNCHRONOUS);

            check.evaluateNow();
            assertThat(check.current()).contains(Outcome.RUNTIME_ROLE_UNREACHABLE);

            clock.advance(INTERVAL);
            assertThat(check.current()).contains(Outcome.RLS_IN_FORCE);
            assertThat(probe.calls()).isEqualTo(2);
        }

        @Test
        @DisplayName("the startup event always probes, even right after a health request did")
        void readyEventAlwaysProbes() {
            ScriptedProbe probe = new ScriptedProbe(ENTERED, ENTERED);
            TenantRuntimeRoleVerdicts check = new TenantRuntimeRoleVerdicts(probe, clock, INTERVAL, SYNCHRONOUS);

            check.current();
            check.evaluateNow();

            assertThat(probe.calls()).isEqualTo(2);
        }

        @Test
        @DisplayName("a database outage keeps the last conclusive verdict instead of inventing one")
        void inconclusiveProbeKeepsTheLastVerdict() {
            ScriptedProbe probe = new ScriptedProbe(REFUSED, UNREACHABLE_DATABASE, ENTERED);
            TenantRuntimeRoleVerdicts check = new TenantRuntimeRoleVerdicts(probe, clock, INTERVAL, SYNCHRONOUS);

            check.evaluateNow();
            clock.advance(INTERVAL);
            assertThat(check.current()).contains(Outcome.RUNTIME_ROLE_UNREACHABLE);

            clock.advance(INTERVAL);
            assertThat(check.current()).contains(Outcome.RLS_IN_FORCE);
            assertThat(probe.calls()).isEqualTo(3);
        }

        @Test
        @DisplayName("a database that was never reachable yields no verdict, and is still retried on the interval")
        void neverConcludedYieldsNothing() {
            ScriptedProbe probe = new ScriptedProbe(UNREACHABLE_DATABASE, UNREACHABLE_DATABASE);
            TenantRuntimeRoleVerdicts check = new TenantRuntimeRoleVerdicts(probe, clock, INTERVAL, SYNCHRONOUS);

            check.evaluateNow();
            assertThat(check.current()).isEmpty();
            clock.advance(INTERVAL);
            assertThat(check.current()).isEmpty();

            assertThat(probe.calls()).isEqualTo(2);
        }

        @Test
        @DisplayName("a probe that throws is inconclusive, never an exception out of the health endpoint")
        void throwingProbeIsInconclusive() {
            TenantRuntimeRoleVerdicts check = new TenantRuntimeRoleVerdicts(() -> {
                throw new IllegalStateException("boom");
            }, clock, INTERVAL, SYNCHRONOUS);

            check.evaluateNow();

            assertThat(check.current()).isEmpty();
        }

        @Test
        @DisplayName("a health request never waits on a slow probe, and only one re-evaluation runs at a time")
        void healthRequestsNeverWaitOnTheProbe() throws Exception {
            // Against a stopped database the real probe waits out Hikari's 30 s connection timeout.
            CountDownLatch probing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            TenantRuntimeRoleVerdicts check = new TenantRuntimeRoleVerdicts(() -> {
                if (calls.incrementAndGet() == 1) {
                    return REFUSED;
                }
                probing.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return ENTERED;
            }, clock, INTERVAL, TenantRuntimeRoleVerdicts.virtualThreads());
            check.evaluateNow();
            clock.advance(INTERVAL);

            long started = System.nanoTime();
            assertThat(check.current()).contains(Outcome.RUNTIME_ROLE_UNREACHABLE);
            assertThat(probing.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 50; i++) {
                assertThat(check.current()).contains(Outcome.RUNTIME_ROLE_UNREACHABLE);
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (check.current().orElseThrow() != Outcome.RLS_IN_FORCE && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(check.current()).contains(Outcome.RLS_IN_FORCE);
            assertThat(calls.get()).isEqualTo(2);
        }
    }

    /** A request authenticated into a company, as a health request from a signed-in user may be. */
    private static final class ScopedAuthentication extends TestingAuthenticationToken
            implements CompanyScopedAuthentication {

        ScopedAuthentication() {
            super("user", "n/a");
            setAuthenticated(true);
        }

        @Override
        public Optional<CompanyScope> companyScope() {
            return Optional.of(new CompanyScope(UUID.randomUUID(), "C1", "Company", "UTC",
                    UUID.randomUUID(), "O1", "Organization", Set.of()));
        }
    }

    /** Returns the scripted results in order, repeating the last one. */
    private static final class ScriptedProbe implements Supplier<Probe> {

        private final Deque<Probe> results;
        private Probe last;
        private int calls;

        ScriptedProbe(Probe... results) {
            this.results = new ArrayDeque<>(java.util.List.of(results));
        }

        @Override
        public Probe get() {
            calls++;
            if (!results.isEmpty()) {
                last = results.poll();
            }
            return last;
        }

        int calls() {
            return calls;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
