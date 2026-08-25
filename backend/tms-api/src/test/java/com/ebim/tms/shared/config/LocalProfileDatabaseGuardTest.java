package com.ebim.tms.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
}
