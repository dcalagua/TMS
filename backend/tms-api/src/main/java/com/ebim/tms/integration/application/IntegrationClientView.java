package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.IntegrationClient;
import com.ebim.tms.integration.domain.IntegrationScope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The administrative view of a credential.
 *
 * <p>Everything an administrator needs to manage it - who it is, what it may do, whether it is
 * still in use, whether a rotation is still in its grace window - and <b>nothing about the
 * secret</b>: not the hash, not a prefix, not a length. The hash is a database column, not an API
 * field; publishing it would hand an attacker with read access to this endpoint the one thing the
 * one-way hash exists to withhold.
 *
 * @param rotationGraceEndsAt non-null while a superseded secret is still accepted, so the screen
 *     can say "the old key stops working on ..." instead of leaving an operator guessing
 */
public record IntegrationClientView(
        UUID id,
        String clientId,
        String name,
        String description,
        List<String> scopes,
        boolean active,
        OffsetDateTime lastUsedAt,
        OffsetDateTime secretRotatedAt,
        OffsetDateTime rotationGraceEndsAt,
        OffsetDateTime revokedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static IntegrationClientView from(IntegrationClient client) {
        return new IntegrationClientView(
                client.id(),
                client.clientId(),
                client.name(),
                client.description(),
                client.scopeValues().stream().map(IntegrationScope::code).toList(),
                client.active(),
                client.lastUsedAt(),
                client.secretRotatedAt(),
                client.previousSecretExpiresAt(),
                client.revokedAt(),
                client.createdAt(),
                client.updatedAt());
    }
}
