package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.TrackingLink;
import com.ebim.tms.orders.domain.TrackingTimeline;
import com.ebim.tms.orders.domain.TrackingToken;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.orders.infrastructure.TrackingLinkRepository;
import com.ebim.tms.orders.infrastructure.TransportOrderRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.OrderTrackingPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The office side of customer tracking links (migration V74, ADR-029): list, generate, revoke.
 *
 * <p><b>Generating takes the order's row lock.</b> The per-order cap (ADR-029 T12) is a count, and two
 * people pressing "Compartir" at once would both see four and both make a fifth-and-sixth; the lock
 * serialises them, as {@code OrderHoldService.place} does for holds.
 *
 * <p><b>Audited, without the secret.</b> Generate and revoke are events on the order
 * ({@code TRANSPORT_ORDER}) naming the link and its expiry. The token appears in exactly one place: the
 * body of the response to the person who generated it.
 */
@Service
@EnableConfigurationProperties(TrackingLinkProperties.class)
public class TrackingLinkService {

    static final String PUBLIC_PATH_PREFIX = "/t/";

    private final TrackingLinkRepository links;
    private final TransportOrderRepository orders;
    private final OrderTrackingPort orderTracking;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;
    private final TrackingLinkProperties properties;
    private final Clock clock;

    public TrackingLinkService(TrackingLinkRepository links, TransportOrderRepository orders,
            OrderTrackingPort orderTracking, AuditActorProvider auditActorProvider, AuditRecorder auditRecorder,
            TrackingLinkProperties properties, Clock clock) {
        this.links = links;
        this.orders = orders;
        this.orderTracking = orderTracking;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TrackingLinkView> list(CompanyScope scope, UUID orderId) {
        TransportOrder order = orders.findByIdAndCompanyId(orderId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        OffsetDateTime closedAt = closedAt(order);
        OffsetDateTime now = now();
        return links.findByOrderIdAndCompanyIdOrderByCreatedAtDesc(orderId, scope.companyId()).stream()
                .map(link -> TrackingLinkView.of(link, link.effectiveExpiry(closedAt, properties.afterClosure()), now))
                .toList();
    }

    @Transactional
    public IssuedTrackingLinkView issue(CompanyScope scope, UUID orderId) {
        TransportOrder order = orders.findByIdAndCompanyIdForUpdate(orderId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        OffsetDateTime now = now();
        OffsetDateTime closedAt = closedAt(order);
        if (closedAt != null && !closedAt.plus(properties.afterClosure()).isAfter(now)) {
            throw new ConflictException("Order " + order.orderNumber() + " closed more than "
                    + properties.afterClosure().toDays() + " days ago; a tracking link would already be expired.");
        }
        long live = links.countLive(orderId, scope.companyId(), now);
        if (live >= properties.maxActivePerOrder()) {
            throw new ConflictException("Order " + order.orderNumber() + " already has " + live
                    + " active tracking links. Revoke one before generating another.");
        }

        TrackingToken token = TrackingToken.generate();
        TrackingLink saved = links.saveAndFlush(TrackingLink.issue(scope.companyId(), orderId, token, now,
                now.plus(properties.maxLifetime()), auditActorProvider.requireAppUserId()));
        OffsetDateTime effective = saved.effectiveExpiry(closedAt, properties.afterClosure());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderNumber", order.orderNumber());
        detail.put("trackingLinkId", saved.id().toString());
        detail.put("expiresAt", effective.toString());
        auditRecorder.record(scope, AuditAggregateType.TRANSPORT_ORDER, orderId, AuditAction.TRACKING_LINK_CREATED,
                detail);
        return new IssuedTrackingLinkView(TrackingLinkView.of(saved, effective, now), token.value(),
                PUBLIC_PATH_PREFIX + token.value());
    }

    @Transactional
    public TrackingLinkView revoke(CompanyScope scope, UUID orderId, UUID linkId, RevokeTrackingLinkRequest request) {
        TransportOrder order = orders.findByIdAndCompanyId(orderId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        TrackingLink link = links.findByIdAndOrderIdAndCompanyId(linkId, orderId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Tracking link not found."));
        if (link.isRevoked()) {
            throw new ConflictException("This tracking link was already revoked on " + link.revokedAt() + ".");
        }
        if (request.version() != null && request.version() != link.version()) {
            throw new ConflictException("This tracking link was changed by someone else since it was loaded. "
                    + "Reload and try again.");
        }

        OffsetDateTime now = now();
        link.revoke(request.reason().trim(), auditActorProvider.requireAppUserId(), now);
        TrackingLink saved;
        try {
            saved = links.saveAndFlush(link);
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw new ConflictException("This tracking link was changed by someone else since it was loaded. "
                    + "Reload and try again.");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderNumber", order.orderNumber());
        detail.put("trackingLinkId", saved.id().toString());
        detail.put("reason", saved.revokeReason());
        auditRecorder.record(scope, AuditAggregateType.TRANSPORT_ORDER, orderId, AuditAction.TRACKING_LINK_REVOKED,
                detail);
        return TrackingLinkView.of(saved, saved.effectiveExpiry(closedAt(order), properties.afterClosure()), now);
    }

    private OffsetDateTime closedAt(TransportOrder order) {
        return TrackingTimeline.of(order.status(), order.createdAt(), order.updatedAt(),
                orderTracking.legsOf(order.id(), order.destinationId(), order.companyId())).closedAt();
    }

    /** Microseconds, like PostgreSQL's timestamptz: what is returned is what a re-read would show. */
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
