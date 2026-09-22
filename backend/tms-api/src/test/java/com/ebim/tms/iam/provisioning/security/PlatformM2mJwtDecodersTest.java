package com.ebim.tms.iam.provisioning.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * The M2M verification rules, one token at a time, without a Spring context.
 */
class PlatformM2mJwtDecodersTest {

    private static final String CREATE = PlatformScopes.TENANT_CREATE;

    private final JwtDecoder decoder = PlatformM2mJwtDecoders.create(PlatformM2mTestTokens.enabledProperties());

    @Test
    @DisplayName("a MasterAdmin ES256 token with the standard claims is accepted and its scope read")
    void acceptsTheStandardToken() {
        Jwt jwt = decoder.decode(PlatformM2mTestTokens.valid(CREATE));

        assertThat(jwt.getSubject()).isEqualTo("masteradmin-provisioning");
        assertThat(PlatformScopes.of(jwt)).containsExactly(CREATE);
        PlatformProvisioningAuthentication authentication = PlatformProvisioningAuthentication.from(jwt);
        assertThat(authentication.getAuthorities()).extracting(Object::toString)
                .containsExactly("SCOPE_tms:tenant:create");
        assertThat(authentication.getPrincipal().actorRole())
                .as("carried for the audit trail").isEqualTo("TECH_LEAD");
        assertThat(authentication.getAuthorities()).extracting(Object::toString)
                .as("actor_role is never an authority").noneMatch(a -> a.contains("TECH_LEAD"));
    }

    @Test
    @DisplayName("scope may arrive as a JSON array as well as a space-separated string")
    void scopeAsArray() {
        Jwt jwt = decoder.decode(PlatformM2mTestTokens.with(CREATE,
                claims -> claims.claim("scope", List.of(CREATE, PlatformScopes.TENANT_READ))));
        assertThat(PlatformScopes.of(jwt)).containsExactly(CREATE, PlatformScopes.TENANT_READ);
    }

    @Nested
    @DisplayName("the algorithm is pinned to ES256")
    class Algorithm {

        @Test
        @DisplayName("HS256 is refused (algorithm confusion)")
        void hs256() {
            assertRejected(PlatformM2mTestTokens.hs256(CREATE));
        }

        @Test
        @DisplayName("RS256 is refused even when well formed")
        void rs256() {
            assertRejected(PlatformM2mTestTokens.rs256(CREATE));
        }

        @Test
        @DisplayName("alg none is refused")
        void none() {
            assertRejected(PlatformM2mTestTokens.unsigned(CREATE));
        }

        @Test
        @DisplayName("an ES256 signature from another key is refused")
        void forged() {
            assertRejected(PlatformM2mTestTokens.forged(CREATE));
        }
    }

    @Nested
    @DisplayName("claims")
    class Claims {

        @Test
        @DisplayName("wrong issuer")
        void wrongIssuer() {
            assertRejected(PlatformM2mTestTokens.with(CREATE, c -> c.issuer("someone-else.ebim")));
        }

        @Test
        @DisplayName("wrong audience (a token MasterAdmin minted for EWM)")
        void wrongAudience() {
            assertRejected(PlatformM2mTestTokens.with(CREATE, c -> c.audience("ewm.ebim")));
        }

        @Test
        @DisplayName("wrong subject")
        void wrongSubject() {
            assertRejected(PlatformM2mTestTokens.with(CREATE, c -> c.subject("admin@masteradmin.test")));
        }

        @Test
        @DisplayName("missing jti")
        void missingJti() {
            assertRejected(PlatformM2mTestTokens.with(CREATE, c -> c.jwtID(null)));
        }

        @Test
        @DisplayName("missing iat")
        void missingIat() {
            assertRejected(PlatformM2mTestTokens.with(CREATE, c -> c.issueTime(null)));
        }

        @Test
        @DisplayName("iat in the future beyond the skew")
        void futureIat() {
            Instant now = Instant.now();
            assertRejected(PlatformM2mTestTokens.with(CREATE, c -> c
                    .issueTime(Date.from(now.plusSeconds(120)))
                    .expirationTime(Date.from(now.plusSeconds(200)))));
        }

        @Test
        @DisplayName("expired")
        void expired() {
            Instant now = Instant.now();
            assertRejected(PlatformM2mTestTokens.with(CREATE, c -> c
                    .issueTime(Date.from(now.minusSeconds(400)))
                    .expirationTime(Date.from(now.minusSeconds(200)))));
        }

        @Test
        @DisplayName("lifetime over 300 s, even if not yet expired")
        void tooLongLived() {
            Instant now = Instant.now();
            assertRejected(PlatformM2mTestTokens.with(CREATE, c -> c
                    .issueTime(Date.from(now.minusSeconds(5)))
                    .expirationTime(Date.from(now.plusSeconds(3600)))));
        }

        @Test
        @DisplayName("no scope at all")
        void noScope() {
            assertRejected(PlatformM2mTestTokens.with(CREATE, c -> c.claim("scope", null)));
        }

        @Test
        @DisplayName("a scope of the wrong type is read as no scope, never as every scope")
        void scopeOfWrongType() {
            assertRejected(PlatformM2mTestTokens.with(CREATE, c -> c.claim("scope", 42)));
        }
    }

    @Nested
    @DisplayName("configuration fails closed")
    class Configuration {

        @Test
        @DisplayName("enabled without a public key does not build")
        void missingKey() {
            PlatformProvisioningProperties properties =
                    new PlatformProvisioningProperties(true, null, null, null, " ", null, null);
            assertThat(properties.problems()).anyMatch(p -> p.contains("TMS_PLATFORM_M2M_PUBLIC_KEY is empty"));
            assertThatThrownBy(() -> PlatformM2mJwtDecoders.create(properties))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a PRIVATE key in the public-key setting is refused")
        void privateKeyRefused() {
            PlatformProvisioningProperties properties = new PlatformProvisioningProperties(true, null, null, null,
                    // Built by concatenation so no secret scanner mistakes a fixture for a leaked key.
                    "-----BEGIN " + "PRIVATE KEY-----\nAAAA\n-----END " + "PRIVATE KEY-----", null, null);
            assertThat(properties.problems()).anyMatch(p -> p.contains("PRIVATE key"));
        }

        @Test
        @DisplayName("a lifetime above the 300 s ceiling is refused")
        void lifetimeCeiling() {
            PlatformProvisioningProperties properties = new PlatformProvisioningProperties(true, null, null, null,
                    PlatformM2mTestTokens.publicKeyPem(), Duration.ofMinutes(10), null);
            assertThat(properties.problems()).anyMatch(p -> p.contains("max-token-lifetime"));
        }

        @Test
        @DisplayName("an EC key off P-256 is refused")
        void wrongCurve() {
            KeyPair p384 = PlatformM2mTestTokens.keyPair("secp384r1");
            String pem = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getEncoder().encodeToString(p384.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
            assertThatThrownBy(() -> PlatformM2mJwtDecoders.parsePublicKey(pem))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("P-256");
        }

        @Test
        @DisplayName("a single-line PEM with literal \\n, as secret stores write it, is accepted")
        void singleLinePem() {
            String singleLine = PlatformM2mTestTokens.publicKeyPem().replace("\n", "\\n");
            assertThat(PlatformM2mJwtDecoders.parsePublicKey(singleLine)).isNotNull();
        }

        @Test
        @DisplayName("defaults are the suite standard: masteradmin.ebim -> tms.ebim, 300 s")
        void defaults() {
            PlatformProvisioningProperties properties = PlatformProvisioningProperties.disabled();
            assertThat(properties.issuer()).isEqualTo("masteradmin.ebim");
            assertThat(properties.audience()).isEqualTo("tms.ebim");
            assertThat(properties.subject()).isEqualTo("masteradmin-provisioning");
            assertThat(properties.maxTokenLifetime()).isEqualTo(Duration.ofSeconds(300));
            assertThat(properties.problems()).as("nothing is required while disabled").isEmpty();
        }
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
