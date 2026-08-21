package com.ebim.tms.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.api.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The server-side request forgery control, pinned.
 *
 * <p>An administrator types a URL and the <em>server</em> fetches it. Everything asserted here is
 * the difference between a webhook feature and a way to make TMS issue signed requests to its own
 * internal network on somebody's behalf.
 *
 * <p>Nothing here resolves a public name, so the test needs no network: every case is either refused
 * before resolution, or uses a literal address the JDK classifies without a lookup.
 */
class WebhookTargetPolicyTest {

    private static WebhookProperties properties(boolean allowInsecure, boolean allowPrivate) {
        return new WebhookProperties("x".repeat(32), null, null, null, null, null, null, null,
                allowInsecure, allowPrivate);
    }

    private final WebhookTargetPolicy strict = new WebhookTargetPolicy(properties(false, false));
    private final WebhookTargetPolicy permissive = new WebhookTargetPolicy(properties(true, true));

    @Test
    @DisplayName("http is refused unless the deployment has opted in")
    void httpsRequired() {
        assertThatThrownBy(() -> strict.requireAllowed("http://partner.example/hooks"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("https");

        assertThat(permissive.requireAllowed("http://partner.example/hooks"))
                .isEqualTo("http://partner.example/hooks");
    }

    @Test
    @DisplayName("a scheme that is not http(s) is refused")
    void schemeIsChecked() {
        assertThatThrownBy(() -> permissive.requireAllowed("ftp://partner.example/hooks"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> permissive.requireAllowed("file:///etc/passwd"))
                .isInstanceOf(InvalidRequestException.class);
        // A relative path would otherwise be resolved against whatever the client library assumed.
        assertThatThrownBy(() -> permissive.requireAllowed("/hooks"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("credentials in the URL are refused")
    void noUserInfo() {
        // They would be stored in a table an administrator can read and echoed into error messages.
        assertThatThrownBy(() -> permissive.requireAllowed("https://user:pass@partner.example/hooks"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    @DisplayName("a fragment is refused, because it is never sent and would silently do nothing")
    void noFragment() {
        assertThatThrownBy(() -> permissive.requireAllowed("https://partner.example/hooks#section"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("fragment");
    }

    @Test
    @DisplayName("loopback and private addresses are refused")
    void privateAddressesRefused() {
        assertThatThrownBy(() -> strict.requireAllowed("https://127.0.0.1/hooks"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("publicly reachable");
        assertThatThrownBy(() -> strict.requireAllowed("https://10.0.0.5/hooks"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> strict.requireAllowed("https://192.168.1.10/hooks"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> strict.requireAllowed("https://172.16.4.4/hooks"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> strict.requireAllowed("https://[::1]/hooks"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("the cloud metadata address is refused - the one this control exists for")
    void metadataAddressRefused() {
        // 169.254.169.254 is where an instance's own credentials live on every major provider.
        assertThatThrownBy(() -> strict.requireAllowed("https://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("carrier-grade NAT and IPv6 unique local addresses are refused too")
    void lessObviousPrivateRanges() {
        // Neither is site-local by the JDK's definition, and both are routable inside a container
        // platform - which is the network an attacker would be aiming at.
        assertThatThrownBy(() -> strict.requireAllowed("https://100.64.0.1/hooks"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> strict.requireAllowed("https://[fd00::1]/hooks"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("a private address is allowed when the deployment has opted in for development")
    void privateAllowedWhenConfigured() {
        assertThat(permissive.requireAllowed("http://localhost:9000/hooks"))
                .isEqualTo("http://localhost:9000/hooks");
    }

    @Test
    @DisplayName("an empty or over-long URL is refused before anything is resolved")
    void boundsAreChecked() {
        assertThatThrownBy(() -> strict.requireAllowed(null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> strict.requireAllowed("   ")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> strict.requireAllowed("https://partner.example/" + "x".repeat(2100)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("2048");
    }

    @Test
    @DisplayName("the send-time re-check answers with a reason instead of throwing")
    void sendTimeRecheck() {
        // The dispatcher is not a request handler: it records the refusal against the attempt rather
        // than propagating an exception out of a background thread.
        assertThat(strict.rejectionReasonAtSendTime("https://127.0.0.1/hooks")).isNotNull();
        assertThat(permissive.rejectionReasonAtSendTime("https://127.0.0.1/hooks")).isNull();
    }
}
