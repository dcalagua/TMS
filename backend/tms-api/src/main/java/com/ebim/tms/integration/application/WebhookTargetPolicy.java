package com.ebim.tms.integration.application;

import com.ebim.tms.shared.api.InvalidRequestException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * What a webhook may be pointed at (migration V35).
 *
 * <h2>Why this is a security control and not validation</h2>
 *
 * <p>A webhook target is a URL supplied by a user that the <em>server</em> then fetches. That is the
 * definition of a server-side request forgery primitive, and the addresses worth forging a request
 * to are exactly the ones a company administrator should never be able to reach: the cloud
 * provider's instance metadata endpoint, an internal admin port, a database's HTTP interface, a
 * neighbouring service that trusts anything arriving from inside the network. TMS would make those
 * requests with its own network position and would helpfully sign them.
 *
 * <p>So the rule is an allow-list by exclusion: a public host over TLS, and nothing else, unless the
 * deployment has explicitly said otherwise for a development environment.
 *
 * <h2>What this cannot do</h2>
 *
 * <p>The address is resolved when the subscription is saved <em>and</em> again immediately before
 * each send, which is what closes the obvious hole - a hostname that resolves publicly at save time
 * and to {@code 169.254.169.254} an hour later. It still cannot close the last millisecond between
 * this check and the socket connect, because the JDK's HTTP client resolves the name itself; a
 * deployment that needs that guarantee puts an egress proxy in front of the application, which is
 * where a network control belongs anyway. Stating the residual risk is part of the control.
 */
@Component
public class WebhookTargetPolicy {

    private static final int MAX_URL_LENGTH = 2048;

    private final WebhookProperties properties;

    public WebhookTargetPolicy(WebhookProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates a target as an administrator typed it.
     *
     * @return the normalised URL to store
     * @throws InvalidRequestException with a message that says which rule was broken, because
     *     "invalid URL" sends somebody hunting for a typo in a URL that has none
     */
    public String requireAllowed(String candidate) {
        String value = candidate == null ? "" : candidate.strip();
        if (value.isEmpty()) {
            throw new InvalidRequestException("targetUrl is required.");
        }
        if (value.length() > MAX_URL_LENGTH) {
            throw new InvalidRequestException("targetUrl must be at most " + MAX_URL_LENGTH + " characters.");
        }

        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException malformed) {
            throw new InvalidRequestException("targetUrl is not a valid URL.");
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new InvalidRequestException("targetUrl must be an absolute URL including the host.");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new InvalidRequestException("targetUrl must use http or https.");
        }
        if ("http".equals(scheme) && !properties.allowInsecureTargets()) {
            throw new InvalidRequestException(
                    "targetUrl must use https: a webhook body carries operational data and a signature is not encryption.");
        }
        if (uri.getUserInfo() != null) {
            // Credentials in a URL end up in logs and in error messages, and this one would be
            // stored in a table an administrator can read. A receiver that needs authentication
            // verifies the signature, which is what it is for.
            throw new InvalidRequestException("targetUrl must not contain credentials.");
        }
        if (uri.getFragment() != null) {
            // A fragment is never sent on the wire, so accepting one would store something that
            // silently does nothing.
            throw new InvalidRequestException("targetUrl must not contain a fragment.");
        }
        requireReachableAddress(uri.getHost());
        return value;
    }

    /**
     * Re-checks a stored target immediately before a send, so a hostname that has been re-pointed at
     * an internal address since it was saved is refused rather than fetched.
     *
     * @return null when the target is still allowed, or the reason to record against the attempt
     */
    public String rejectionReasonAtSendTime(String targetUrl) {
        try {
            requireAllowed(targetUrl);
            return null;
        } catch (InvalidRequestException refused) {
            return refused.getMessage();
        }
    }

    private void requireReachableAddress(String host) {
        if (properties.allowPrivateNetworkTargets()) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException unresolvable) {
            // Refused rather than allowed: an endpoint TMS cannot resolve now is an endpoint every
            // delivery would fail against, and saving it would turn a typo into a queue of failures
            // discovered hours later. A temporary DNS outage costs one retry of the save.
            throw new InvalidRequestException("targetUrl's host could not be resolved.");
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                throw new InvalidRequestException(
                        "targetUrl must be a publicly reachable address; internal and loopback addresses are refused.");
            }
        }
    }

    /**
     * Everything that is not routable on the public internet. {@code isSiteLocalAddress} covers
     * 10/8, 172.16/12 and 192.168/16; the rest are the cases it does not - loopback, link-local
     * (which includes the 169.254.169.254 metadata address every cloud provider uses), multicast,
     * the wildcard, and IPv6 unique local addresses.
     */
    private static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] octets = address.getAddress();
        if (octets.length == 16) {
            // fc00::/7 - IPv6 unique local, the equivalent of RFC 1918 and not covered above.
            return (octets[0] & 0xFE) == 0xFC;
        }
        // 100.64.0.0/10, carrier-grade NAT: not site-local by the JDK's definition, not public
        // either, and used inside container platforms.
        return octets.length == 4 && (octets[0] & 0xFF) == 100 && (octets[1] & 0xC0) == 0x40;
    }
}
