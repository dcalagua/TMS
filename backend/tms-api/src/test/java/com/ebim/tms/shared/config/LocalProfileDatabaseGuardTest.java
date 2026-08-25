package com.ebim.tms.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The one decision {@link LocalProfileDatabaseGuard} makes: is the database this JDBC URL names on
 * this machine?
 *
 * <p>Worth its own suite because the guard's whole value is in the answers it gets wrong. A
 * false positive migrates a shared Supabase project from somebody's laptop; a false negative stops
 * a developer working and teaches them to set the override permanently, which removes the guard.
 */
class LocalProfileDatabaseGuardTest {

    @Nested
    @DisplayName("URLs that name this machine")
    class Local {

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:postgresql://localhost:54322/postgres",
                "jdbc:postgresql://localhost/postgres",
                "jdbc:postgresql://127.0.0.1:5432/tms",
                "jdbc:postgresql://LOCALHOST:54322/postgres",
                "jdbc:postgresql://[::1]:5432/postgres",
                "jdbc:postgresql://host.docker.internal:5432/postgres",
                "jdbc:postgresql://localhost:54322/postgres?sslmode=disable",
                "jdbc:postgresql://postgres:secret@localhost:54322/postgres",
        })
        void areRecognised(String url) {
            assertThat(LocalProfileDatabaseGuard.isLocal(LocalProfileDatabaseGuard.hostOf(url))).isTrue();
        }
    }

    @Nested
    @DisplayName("URLs that do not")
    class NotLocal {

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:postgresql://db.abcdefghijkl.supabase.co:5432/postgres",
                "jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:6543/postgres",
                "jdbc:postgresql://10.0.0.4:5432/tms",
                "jdbc:postgresql://tms-db.internal:5432/tms",
        })
        void areRefused(String url) {
            assertThat(LocalProfileDatabaseGuard.isLocal(LocalProfileDatabaseGuard.hostOf(url))).isFalse();
        }

        @Test
        @DisplayName("a host that only looks like localhost is not localhost")
        void doesNotFallForALookalike() {
            // The reason this is parsed rather than matched with contains(): every one of these
            // reads as "localhost" to a substring check and none of them is this machine.
            assertThat(LocalProfileDatabaseGuard.hostOf("jdbc:postgresql://localhost@db.example.com:5432/tms"))
                    .isEqualTo("db.example.com");
            assertThat(LocalProfileDatabaseGuard.hostOf("jdbc:postgresql://localhost.evil.com:5432/tms"))
                    .isEqualTo("localhost.evil.com");
            assertThat(LocalProfileDatabaseGuard.hostOf("jdbc:postgresql://notlocalhost:5432/tms"))
                    .isEqualTo("notlocalhost");
            assertThat(LocalProfileDatabaseGuard.hostOf("jdbc:postgresql://db.example.com/localhost"))
                    .isEqualTo("db.example.com");
        }

        @Test
        @DisplayName("a URL with no host at all is treated as not local, never as safe")
        void anUnparseableUrlIsNotLocal() {
            assertThat(LocalProfileDatabaseGuard.isLocal(LocalProfileDatabaseGuard.hostOf(""))).isFalse();
            assertThat(LocalProfileDatabaseGuard.isLocal(LocalProfileDatabaseGuard.hostOf("jdbc:h2:mem:tms"))).isFalse();
        }
    }

    /**
     * The path the application actually takes at startup, as opposed to the two above it.
     *
     * <p>Spring Boot builds Flyway from the application's {@code DataSource} whenever
     * {@code spring.flyway.url} is not set - which is every profile in this repository. On that
     * path {@code getConfiguration().getUrl()} is null, because the URL was never a Flyway
     * property; the connection details live in the datasource. A guard that reads only
     * {@code getUrl()} therefore sees nothing, calls nothing local, and refuses every start:
     *
     * <pre>Refusing to run Flyway against '' on the 'local' profile.</pre>
     *
     * <p>That is the false negative the class javadoc warns about, and it is worse than an
     * absent guard: the only way to work is to set {@code TMS_ALLOW_REMOTE_DB=true} and leave it
     * set, which disarms the check permanently and for every database.
     */
    @Nested
    @DisplayName("when Flyway was built from the DataSource and carries no URL of its own")
    class FromDataSource {

        private final LocalProfileDatabaseGuard guard = new LocalProfileDatabaseGuard();
        private final MockEnvironment environment = new MockEnvironment();

        @Test
        @DisplayName("a datasource pointing at this machine is allowed to migrate")
        void aLocalDataSourceMigrates() throws SQLException {
            Flyway flyway = flywayBackedBy(dataSourceReporting("jdbc:postgresql://localhost:54322/postgres"));

            assertThatCode(() -> guard.localOnlyMigrationStrategy(environment).migrate(flyway))
                    .doesNotThrowAnyException();
            verify(flyway).migrate();
        }

        @Test
        @DisplayName("a datasource pointing somewhere else is still refused")
        void aRemoteDataSourceIsRefused() throws SQLException {
            Flyway flyway = flywayBackedBy(
                    dataSourceReporting("jdbc:postgresql://db.abcdefgh.supabase.co:5432/postgres"));

            assertThatThrownBy(() -> guard.localOnlyMigrationStrategy(environment).migrate(flyway))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("db.abcdefgh.supabase.co")
                    .hasMessageContaining(LocalProfileDatabaseGuard.ALLOW_REMOTE);
            verify(flyway, never()).migrate();
        }

        @Test
        @DisplayName("a datasource that cannot be probed fails closed rather than open")
        void anUnreachableDataSourceIsRefused() throws SQLException {
            DataSource unreachable = mock(DataSource.class);
            when(unreachable.getConnection()).thenThrow(new SQLException("connection refused"));
            Flyway flyway = flywayBackedBy(unreachable);

            assertThatThrownBy(() -> guard.localOnlyMigrationStrategy(environment).migrate(flyway))
                    .isInstanceOf(IllegalStateException.class);
            verify(flyway, never()).migrate();
        }

        @Test
        @DisplayName("an explicit spring.flyway.url still wins over the datasource")
        void anExplicitUrlIsPreferred() throws SQLException {
            Flyway flyway = mock(Flyway.class);
            Configuration configuration = mock(Configuration.class);
            when(flyway.getConfiguration()).thenReturn(configuration);
            when(configuration.getUrl()).thenReturn("jdbc:postgresql://127.0.0.1:5432/postgres");

            assertThatCode(() -> guard.localOnlyMigrationStrategy(environment).migrate(flyway))
                    .doesNotThrowAnyException();
            verify(flyway).migrate();
            // The datasource must not even be probed when the URL is stated outright.
            verify(configuration, never()).getDataSource();
        }

        @Test
        @DisplayName("a Hikari pool is read from its configuration, without opening a connection")
        void aHikariPoolIsReadWithoutConnecting() {
            // The guard must not log in to a host it is about to refuse. This pool is never
            // started and points at a port nothing listens on: if the guard connected to decide,
            // this test would fail on the attempt rather than on the assertion.
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl("jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:6543/postgres");
            Flyway flyway = mock(Flyway.class);
            Configuration configuration = mock(Configuration.class);
            when(flyway.getConfiguration()).thenReturn(configuration);
            when(configuration.getUrl()).thenReturn(null);
            when(configuration.getDataSource()).thenReturn(pool);

            assertThatThrownBy(() -> guard.localOnlyMigrationStrategy(environment).migrate(flyway))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("aws-0-us-east-1.pooler.supabase.com");
            verify(flyway, never()).migrate();
        }

        @Test
        @DisplayName("the override still lets an authorised run through, and says so")
        void theOverrideStillWorks() throws SQLException {
            environment.setProperty(LocalProfileDatabaseGuard.ALLOW_REMOTE, "true");
            Flyway flyway = flywayBackedBy(
                    dataSourceReporting("jdbc:postgresql://db.abcdefgh.supabase.co:5432/postgres"));

            assertThatCode(() -> guard.localOnlyMigrationStrategy(environment).migrate(flyway))
                    .doesNotThrowAnyException();
            verify(flyway).migrate();
        }

        private Flyway flywayBackedBy(DataSource dataSource) {
            Flyway flyway = mock(Flyway.class);
            Configuration configuration = mock(Configuration.class);
            when(flyway.getConfiguration()).thenReturn(configuration);
            when(configuration.getUrl()).thenReturn(null);
            when(configuration.getDataSource()).thenReturn(dataSource);
            return flyway;
        }

        private DataSource dataSourceReporting(String jdbcUrl) throws SQLException {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            DatabaseMetaData metaData = mock(DatabaseMetaData.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.getMetaData()).thenReturn(metaData);
            when(metaData.getURL()).thenReturn(jdbcUrl);
            return dataSource;
        }
    }
}
