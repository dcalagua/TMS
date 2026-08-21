package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.IntegrationClient;
import com.ebim.tms.integration.domain.IntegrationSecrets;
import com.ebim.tms.integration.infrastructure.IntegrationClientRepository;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.CompanyScopeLoader;
import com.ebim.tms.shared.security.MachineCredentialException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a bearer token into an {@link IntegrationPrincipal}, or refuses it.
 *
 * <h2>One rejection, many reasons</h2>
 *
 * <p>Malformed token, unknown client, revoked client, deactivated client, wrong secret,
 * deactivated company: every one of them produces the same {@link MachineCredentialException} with
 * the same message. A partner debugging a genuine misconfiguration loses nothing - the server log
 * says which it was, under the request's correlation id - while an attacker enumerating client
 * ids learns nothing at all, because "no such credential" and "wrong secret" are indistinguishable
 * from the outside.
 *
 * <p>The secret is never logged, never included in a message and never returned. The log lines
 * here carry the client id, which is public by design.
 *
 * <h2>Why this runs unscoped</h2>
 *
 * <p>Resolving a client id to its company is what <em>decides</em> the tenant, so it cannot
 * already be inside one - the same chicken-and-egg {@code TenantScopedDataSource} documents for
 * principal resolution. The read therefore happens on the owner connection, before any company is
 * published to PostgreSQL. Every statement after authentication runs as {@code tms_app} with the
 * credential's company set, so RLS covers the actual work.
 */
@Service
public class IntegrationAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationAuthenticationService.class);

    /** What every failure says, whatever actually happened. */
    private static final String REJECTION = "The integration credential is missing, malformed, revoked or not valid.";

    /**
     * How stale {@code last_used_at} may get before it is rewritten. Without a threshold, a
     * partner posting 10,000 orders a day would produce 10,000 pointless UPDATEs on one row, all
     * of them contending on the same tuple.
     */
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(5);

    private final IntegrationClientRepository clientRepository;
    private final CompanyScopeLoader companyScopeLoader;
    private final Clock clock;

    public IntegrationAuthenticationService(IntegrationClientRepository clientRepository,
            CompanyScopeLoader companyScopeLoader, Clock clock) {
        this.clientRepository = clientRepository;
        this.companyScopeLoader = companyScopeLoader;
        this.clock = clock;
    }

    /**
     * @param bearerToken the value after {@code Bearer }, i.e. the client id, a full stop and the
     *     secret
     * @throws MachineCredentialException for every possible failure, indistinguishably
     */
    @Transactional
    public IntegrationPrincipal authenticate(String bearerToken) {
        Credential credential = parse(bearerToken);

        IntegrationClient client = clientRepository.findByClientId(credential.clientId())
                .orElseThrow(() -> reject("unknown client id {}", credential.clientId()));

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (client.isRevoked() || !client.active()) {
            throw reject("client {} is revoked or inactive", client.clientId());
        }
        if (!client.acceptsSecret(credential.secret(), now)) {
            // The security-relevant event: a real client id with a secret that does not open it.
            throw reject("secret rejected for client {}", client.clientId());
        }

        CompanyScope scope = companyScopeLoader.loadActiveCompanyScope(client.companyId())
                .orElseThrow(() -> reject("client {} is bound to an inactive company", client.clientId()));

        if (client.scopeValues().isEmpty()) {
            // A credential with no scope can call nothing, so refusing at the door is both
            // cheaper and clearer than letting every endpoint answer 403.
            throw reject("client {} holds no scopes", client.clientId());
        }

        touch(client, now);
        // Drops a superseded secret whose window has closed, so a stale hash never lingers in the
        // row. Doing it on use rather than on a schedule keeps it free of a job.
        client.expireRotationWindow(now);

        // The carrier travels on the principal for the same reason the company does: it decides
        // whose data the caller may reach, so it is resolved from the credential here and never
        // read from anything the caller sends (migration V31).
        return new IntegrationPrincipal(client.id(), client.clientId(), client.name(), scope, client.carrierId(),
                client.scopeValues());
    }

    private void touch(IntegrationClient client, OffsetDateTime now) {
        OffsetDateTime lastUsed = client.lastUsedAt();
        if (lastUsed == null || Duration.between(lastUsed, now).compareTo(LAST_USED_RESOLUTION) >= 0) {
            client.markUsed(now);
        }
    }

    private static Credential parse(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw reject("no credential presented", null);
        }
        String token = bearerToken.trim();
        int separator = token.indexOf(IntegrationSecrets.TOKEN_SEPARATOR);
        if (separator <= 0 || separator == token.length() - 1) {
            throw reject("token is not in the clientId.secret form", null);
        }

        String clientId = token.substring(0, separator);
        String secret = token.substring(separator + 1);
        if (!IntegrationSecrets.isWellFormedClientId(clientId) || !IntegrationSecrets.isWellFormedSecret(secret)) {
            // Shape-checked before any database work: a malformed token cannot be a real
            // credential, and refusing it here keeps a flood of junk off the connection pool.
            throw reject("token halves are not well formed (client id {})",
                    IntegrationSecrets.isWellFormedClientId(clientId) ? clientId : "<malformed>");
        }
        return new Credential(clientId, secret);
    }

    /**
     * Builds the one exception this class throws, logging the real reason first. The message
     * given to the caller never varies; the log line always does.
     */
    private static MachineCredentialException reject(String reason, String clientId) {
        if (clientId == null) {
            log.warn("Integration authentication refused: {}", reason);
        } else {
            log.warn("Integration authentication refused: " + reason, clientId);
        }
        return new MachineCredentialException(REJECTION);
    }

    /** The two halves of a presented token. Never logged, never stored, never returned. */
    private record Credential(String clientId, String secret) {}

    /** Exposed for the filter so it can build the same rejection without duplicating the message. */
    public static MachineCredentialException rejection() {
        return new MachineCredentialException(REJECTION);
    }
}
