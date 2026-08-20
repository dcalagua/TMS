package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.IntegrationClient;
import com.ebim.tms.integration.domain.IntegrationRequest;
import com.ebim.tms.integration.domain.IntegrationScope;
import com.ebim.tms.integration.domain.IntegrationSecrets;
import com.ebim.tms.integration.infrastructure.IntegrationClientRepository;
import com.ebim.tms.integration.infrastructure.IntegrationRequestRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration of integration credentials: the screen behind "give our WMS a key".
 *
 * <p>Takes a {@link CompanyScope}, never a company id, exactly like every other use case. That is
 * what binds a new credential to the administrator's own tenant and makes creating one for
 * another company impossible rather than merely unsupported.
 *
 * <p>The actor here is {@code requireAppUserId}, not {@code writerAppUserId}: issuing a credential
 * that can write orders unattended is a decision a <em>person</em> takes, so an integration must
 * not be able to mint another integration. That single method call is the whole privilege-
 * escalation defence, and it is deliberate.
 */
@Service
public class IntegrationClientService {

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("name", "active", "lastUsedAt", "createdAt", "updatedAt");

    private static final Set<String> HISTORY_SORTABLE_PROPERTIES =
            Set.of("receivedAt", "status", "operation");

    private final IntegrationClientRepository clientRepository;
    private final IntegrationRequestRepository requestRepository;
    private final IntegrationProperties properties;
    private final AuditActorProvider auditActorProvider;
    private final Clock clock;

    public IntegrationClientService(IntegrationClientRepository clientRepository,
            IntegrationRequestRepository requestRepository, IntegrationProperties properties,
            AuditActorProvider auditActorProvider, Clock clock) {
        this.clientRepository = clientRepository;
        this.requestRepository = requestRepository;
        this.properties = properties;
        this.auditActorProvider = auditActorProvider;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<IntegrationClientView> list(CompanyScope scope, PageQuery pageQuery) {
        Page<IntegrationClient> page = clientRepository.findByCompanyId(scope.companyId(),
                toPageable(pageQuery, SORTABLE_PROPERTIES, "name", Sort.Direction.ASC));
        List<IntegrationClientView> content = page.getContent().stream().map(IntegrationClientView::from).toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public IntegrationClientView get(CompanyScope scope, UUID id) {
        return IntegrationClientView.from(find(scope, id));
    }

    /**
     * Issues a credential. The secret exists in memory for the length of this method and in the
     * response it produces; only its hash is persisted.
     */
    @Transactional
    public IntegrationClientSecretView create(CompanyScope scope, IntegrationClientRequest request) {
        String name = request.name().trim();
        Set<IntegrationScope> scopes = parseScopes(request.scopes());
        if (clientRepository.existsByCompanyIdAndName(scope.companyId(), name)) {
            throw new ConflictException("An integration credential named '" + name + "' already exists in this company.");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        String clientId = IntegrationSecrets.newClientId();
        String secret = IntegrationSecrets.newSecret();

        IntegrationClient client = new IntegrationClient(scope.companyId(), clientId, name,
                blankToNull(request.description()), IntegrationSecrets.hash(secret), actorId);
        client.replaceScopes(scopes, actorId);

        IntegrationClient saved = clientRepository.saveAndFlush(client);
        return IntegrationClientSecretView.of(IntegrationClientView.from(saved), clientId, secret,
                IntegrationSecrets.toBearerToken(clientId, secret), null);
    }

    /**
     * Renames a credential and re-scopes it. Not a way to change its secret - {@link #rotate} is -
     * and not a way to change its company, which no method offers.
     */
    @Transactional
    public IntegrationClientView update(CompanyScope scope, UUID id, IntegrationClientRequest request) {
        IntegrationClient client = find(scope, id);
        requireNotRevoked(client);
        String name = request.name().trim();
        Set<IntegrationScope> scopes = parseScopes(request.scopes());
        if (clientRepository.existsByCompanyIdAndNameAndIdNot(scope.companyId(), name, id)) {
            throw new ConflictException("An integration credential named '" + name + "' already exists in this company.");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        client.rename(name, blankToNull(request.description()), actorId);
        client.replaceScopes(scopes, actorId);
        return IntegrationClientView.from(clientRepository.saveAndFlush(client));
    }

    /**
     * Issues a new secret and, unless asked otherwise, keeps the old one working for a grace
     * window so the partner can redeploy on their own schedule.
     *
     * @param graceHours {@code null} uses the configured default; {@code 0} revokes the old secret
     *     immediately, which is the correct choice when it is believed to have leaked
     */
    @Transactional
    public IntegrationClientSecretView rotate(CompanyScope scope, UUID id, Integer graceHours) {
        IntegrationClient client = find(scope, id);
        requireNotRevoked(client);
        if (!client.active()) {
            throw new ConflictException("A deactivated credential cannot be rotated.");
        }

        Duration grace = resolveGrace(graceHours);
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime graceUntil = grace.isZero() ? null : now.plus(grace);

        UUID actorId = auditActorProvider.requireAppUserId();
        String secret = IntegrationSecrets.newSecret();
        client.rotateSecret(IntegrationSecrets.hash(secret), graceUntil, now, actorId);

        IntegrationClient saved = clientRepository.saveAndFlush(client);
        return IntegrationClientSecretView.of(IntegrationClientView.from(saved), saved.clientId(), secret,
                IntegrationSecrets.toBearerToken(saved.clientId(), secret), saved.previousSecretExpiresAt());
    }

    /**
     * Terminal, and immediate: no grace window, and the superseded secret is dropped with the
     * current one. Revocation is the button someone presses when a key has leaked, and a
     * revocation that kept working for a week would be worse than none.
     */
    @Transactional
    public IntegrationClientView revoke(CompanyScope scope, UUID id) {
        IntegrationClient client = find(scope, id);
        if (client.isRevoked()) {
            throw new ConflictException("This credential is already revoked.");
        }
        client.revoke(OffsetDateTime.now(clock), auditActorProvider.requireAppUserId());
        return IntegrationClientView.from(clientRepository.saveAndFlush(client));
    }

    /** Every delivery this company received, newest first - the integration inbox as a screen. */
    @Transactional(readOnly = true)
    public PageResponse<IntegrationRequestView> history(CompanyScope scope, UUID clientId, PageQuery pageQuery) {
        Pageable pageable = toPageable(pageQuery, HISTORY_SORTABLE_PROPERTIES, "receivedAt",
                Sort.Direction.DESC);
        Page<IntegrationRequest> page = clientId == null
                ? requestRepository.findByCompanyId(scope.companyId(), pageable)
                // find() first, so asking for the history of another company's credential answers
                // 404 rather than an empty page that might read as "it sent nothing".
                : requestRepository.findByCompanyIdAndIntegrationClientId(
                        scope.companyId(), find(scope, clientId).id(), pageable);
        List<IntegrationRequestView> content = page.getContent().stream().map(IntegrationRequestView::from).toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    private IntegrationClient find(CompanyScope scope, UUID id) {
        return clientRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Integration credential not found."));
    }

    private static void requireNotRevoked(IntegrationClient client) {
        if (client.isRevoked()) {
            throw new ConflictException("A revoked credential cannot be changed. Create a new one instead.");
        }
    }

    private Duration resolveGrace(Integer graceHours) {
        if (graceHours == null) {
            return properties.rotationGrace();
        }
        if (graceHours < 0) {
            throw new InvalidRequestException("graceHours must be zero or greater.");
        }
        Duration requested = Duration.ofHours(graceHours);
        if (requested.compareTo(properties.rotationGrace()) > 0) {
            throw new InvalidRequestException("graceHours must not exceed the configured maximum of "
                    + properties.rotationGrace().toHours() + " hours.");
        }
        return requested;
    }

    private static Set<IntegrationScope> parseScopes(Set<String> codes) {
        return codes.stream()
                .map(code -> IntegrationScope.byCode(code)
                        .orElseThrow(() -> new InvalidRequestException("scopes must be chosen from "
                                + Arrays.stream(IntegrationScope.values()).map(IntegrationScope::code)
                                        .collect(Collectors.joining(", "))
                                + " (received: " + code + ").")))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Pageable toPageable(PageQuery pageQuery, Set<String> sortable, String defaultProperty,
            Sort.Direction defaultDirection) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(sortable);
        Sort sort = terms.isEmpty()
                ? Sort.by(defaultDirection, defaultProperty)
                : Sort.by(terms.stream()
                        .map(term -> new Sort.Order(
                                term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, term.property()))
                        .toList());
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
