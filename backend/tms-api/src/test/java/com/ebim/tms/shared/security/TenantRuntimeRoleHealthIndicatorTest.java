package com.ebim.tms.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ebim.tms.shared.security.TenantRuntimeRoleCheck.Outcome;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * What readiness is told about the runtime role, through the real check and probe over a
 * simulated JDBC connection.
 *
 * <p>The case that matters is the day-6 restore: a {@code pg_dump} on a cluster without
 * {@code tms_app}, a startup log that said so at ERROR, and a readiness probe that still answered
 * {@code 200 UP}. These tests pin the other side of that: the same verdict now reads DOWN, and
 * nothing a caller of the health endpoint can see names the role.
 */
class TenantRuntimeRoleHealthIndicatorTest {

    @Test
    @DisplayName("a role that does not exist - the restored pg_dump - is DOWN")
    void missingRoleIsDown() throws SQLException {
        Health health = indicatorOver(connectionAs("tms_owner",
                new SQLException("ERROR: role \"tms_app\" does not exist", "22023"))).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("a role that was not granted is DOWN")
    void ungrantedRoleIsDown() throws SQLException {
        Health health = indicatorOver(connectionAs("tms_owner",
                new SQLException("ERROR: permission denied to set role \"tms_app\"", "42501"))).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("the expected posture is UP")
    void expectedPostureIsUp() throws SQLException {
        Health health = indicatorOver(connectionAs("tms_owner", null)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("connecting as the runtime role serves requests, so it stays UP and is only a logged warning")
    void connectedAsRuntimeRoleIsUp() throws SQLException {
        Health health = indicatorOver(connectionAs("tms_app", null)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("a database that cannot be reached is not reported as an unenterable role")
    void connectionFailureIsNotDown() throws SQLException {
        DataSource unreachable = mock(DataSource.class);
        when(unreachable.getConnection()).thenThrow(new SQLTransientConnectionException(
                "tms-pool - Connection is not available, request timed out after 30000ms"));

        Health health = indicatorOver(unreachable).health();

        // UNKNOWN ranks below UP in the default aggregation: it neither holds the instance out of
        // rotation nor vouches for it. Whether the database is up is the db indicator's answer.
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    @DisplayName("no status carries a detail: no role name, no SQL, no exception")
    void noDetailsAreExposed() throws SQLException {
        for (Optional<Outcome> outcome : List.of(Optional.<Outcome>empty(),
                Optional.of(Outcome.RLS_IN_FORCE), Optional.of(Outcome.RUNTIME_ROLE_UNREACHABLE),
                Optional.of(Outcome.CONNECTED_AS_RUNTIME_ROLE))) {
            Health health = TenantRuntimeRoleHealthIndicator.toHealth(outcome);
            assertThat(health.getDetails()).as("details for %s", outcome).isEmpty();
            assertThat(health.toString()).doesNotContain("tms_app").doesNotContain("ROLE");
        }
        Health refused = indicatorOver(connectionAs("tms_owner",
                new SQLException("ERROR: role \"tms_app\" does not exist", "22023"))).health(true);
        assertThat(refused.getDetails()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static TenantRuntimeRoleHealthIndicator indicatorOver(DataSource source) {
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(source);
        TenantRuntimeRoleCheck check = new TenantRuntimeRoleCheck(provider);
        // What ApplicationReadyEvent does, before readiness starts accepting traffic.
        check.report();
        return new TenantRuntimeRoleHealthIndicator(check);
    }

    /** A live connection logged in as {@code login} whose SET ROLE throws {@code setRole}, if given. */
    private static DataSource connectionAs(String login, SQLException setRole) throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet roles = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.isValid(anyInt())).thenReturn(true);
        when(connection.getAutoCommit()).thenReturn(true);
        when(statement.executeQuery("SELECT session_user, current_user")).thenReturn(roles);
        when(roles.next()).thenReturn(true);
        when(roles.getString(1)).thenReturn(login);
        when(roles.getString(2)).thenReturn(login);
        if (setRole != null) {
            when(statement.execute("SET ROLE tms_app")).thenThrow(setRole);
        }
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        return source;
    }
}
