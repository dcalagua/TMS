package com.ebim.tms.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The configuration rules that decide whether the API can start at all.
 *
 * <p>Written as unit tests on purpose. "Production fails safely when the auth settings are
 * missing" is a claim that must be verifiable without booting an application against a
 * production profile - otherwise it is a claim nobody ever checks.
 */
class SupabaseJwtDecodersTest {

    private static final String ISSUER = "https://project.supabase.co/auth/v1";
    private static final String JWKS = "https://project.supabase.co/auth/v1/.well-known/jwks.json";

    @Nested
    @DisplayName("missing configuration")
    class MissingConfiguration {

        @Test
        @DisplayName("no issuer and no JWKS: the application refuses to start, in every profile")
        void unconfiguredFailsEverywhere() {
            for (boolean production : new boolean[] {true, false}) {
                assertThatThrownBy(() -> SupabaseJwtDecoders.validate(jwt(null, null), production))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("TMS_SUPABASE_JWT_ISSUER_URI")
                        .hasMessageContaining("TMS_SUPABASE_JWKS_URI");
            }
        }

        @Test
        @DisplayName("a blank value counts as missing, not as an empty allow-list")
        void blankIsTreatedAsMissing() {
            assertThatThrownBy(() -> SupabaseJwtDecoders.validate(jwt("   ", JWKS), false))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("half-configured is still misconfigured")
        void issuerWithoutJwksFails() {
            assertThatThrownBy(() -> SupabaseJwtDecoders.validate(jwt(ISSUER, null), false))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("production hardening")
    class ProductionHardening {

        @Test
        @DisplayName("a development configuration that reaches production fails at startup")
        void loopbackIsRefusedInProduction() {
            assertThatThrownBy(() -> SupabaseJwtDecoders.validate(
                            jwt("https://127.0.0.1/auth/v1", "https://127.0.0.1/auth/v1/jwks"), true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("loopback");
        }

        @Test
        @DisplayName("plain http is refused in production: keys must not be fetched over a tappable channel")
        void plainHttpIsRefusedInProduction() {
            assertThatThrownBy(() -> SupabaseJwtDecoders.validate(
                            jwt("http://project.supabase.co/auth/v1", JWKS), true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("https");
        }

        @Test
        @DisplayName("the same local configuration is accepted outside production")
        void loopbackIsAcceptedLocally() {
            assertThatCode(() -> SupabaseJwtDecoders.validate(
                            jwt("http://127.0.0.1:54321/auth/v1",
                                    "http://127.0.0.1:54321/auth/v1/.well-known/jwks.json"), false))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a proper Supabase configuration is accepted in production")
        void productionConfigurationIsAccepted() {
            assertThatCode(() -> SupabaseJwtDecoders.validate(jwt(ISSUER, JWKS), true))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("other settings")
    class OtherSettings {

        @Test
        @DisplayName("a URI without a scheme or host is rejected rather than silently trusted")
        void relativeUriIsRejected() {
            assertThatThrownBy(() -> SupabaseJwtDecoders.validate(jwt("/auth/v1", JWKS), false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("absolute");
        }

        @Test
        @DisplayName("an unbounded clock skew would make expiry meaningless, so it is capped")
        void excessiveClockSkewIsRejected() {
            TmsSecurityProperties.Jwt jwt = new TmsSecurityProperties.Jwt(
                    ISSUER, JWKS, List.of("authenticated"), Duration.ofHours(2));

            assertThatThrownBy(() -> SupabaseJwtDecoders.validate(jwt, false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("clock-skew");
        }

        @Test
        @DisplayName("the audience defaults to Supabase's own, and there is no signing-secret setting")
        void defaultsAreSafe() {
            TmsSecurityProperties.Jwt jwt = new TmsSecurityProperties.Jwt(ISSUER, JWKS, null, null);

            assertThat(jwt.audiences()).containsExactly("authenticated");
            assertThat(jwt.clockSkew()).isEqualTo(Duration.ofSeconds(30));
            assertThat(TmsSecurityProperties.Jwt.class.getRecordComponents())
                    .as("a shared secret must not be configurable: verification is JWKS-only")
                    .noneMatch(component -> component.getName().toLowerCase().contains("secret"));
        }
    }

    @Nested
    @DisplayName("the decoder bean")
    class DecoderBean {

        private final SupabaseJwtConfig config = new SupabaseJwtConfig();

        @Test
        @DisplayName("an unconfigured deployment fails at startup instead of starting unsecured")
        void unconfiguredContextFailsToStart() {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("prod");

            assertThatThrownBy(() -> config.jwtDecoder(properties(null, null), environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot start");
        }

        @Test
        @DisplayName("a local configuration is refused under a production profile")
        void localConfigurationIsRefusedInProduction() {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("prod");

            assertThatThrownBy(() -> config.jwtDecoder(
                            properties("http://127.0.0.1:54321/auth/v1",
                                    "http://127.0.0.1:54321/auth/v1/.well-known/jwks.json"), environment))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("staging counts as production for these rules; local and test do not")
        void productionProfilesAreRecognised() {
            for (String profile : List.of("prod", "production", "staging", "PROD")) {
                MockEnvironment environment = new MockEnvironment();
                environment.setActiveProfiles(profile);
                assertThat(SupabaseJwtConfig.isProduction(environment)).as(profile).isTrue();
            }
            for (String profile : List.of("local", "test", "dev")) {
                MockEnvironment environment = new MockEnvironment();
                environment.setActiveProfiles(profile);
                assertThat(SupabaseJwtConfig.isProduction(environment)).as(profile).isFalse();
            }
        }

        @Test
        @DisplayName("a valid configuration produces a decoder without contacting the key server")
        void validConfigurationBuildsLazily() {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("prod");

            // NimbusJwtDecoder fetches the JWK set on first use, so building it here performs
            // no network call - which is why the tests never need a live auth service.
            assertThat(config.jwtDecoder(properties(ISSUER, JWKS), environment)).isNotNull();
        }

        private TmsSecurityProperties properties(String issuerUri, String jwkSetUri) {
            return new TmsSecurityProperties(jwt(issuerUri, jwkSetUri), null);
        }
    }

    private static TmsSecurityProperties.Jwt jwt(String issuerUri, String jwkSetUri) {
        return new TmsSecurityProperties.Jwt(issuerUri, jwkSetUri, null, null);
    }
}
