package com.ebim.tms.notification.application;

import com.ebim.tms.notification.domain.Notification;
import com.ebim.tms.notification.infrastructure.NotificationRepository;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.notification.NotificationType;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The read side of the alert board (migration V32): what this company should look at, and marking
 * it as looked at.
 *
 * <p><b>The permission check lives here, per alert type, not on the endpoint.</b>
 * {@code NotificationController} is open to any authenticated member of the company, because the
 * bell is a permanent control in the top bar and answering 403 to something nobody can hide is a
 * worse experience than an empty panel. What is actually protected is the disclosure: every query
 * below is filtered to {@link NotificationType#visibleTo}, so an account without
 * {@code fleet.driver:read} is never told whose licence is running out, and one without
 * {@code planning.tender:read} is never told a carrier said no. See migration V32 section 4.
 *
 * <p>A caller who may see nothing is answered without going to the database at all - which is both
 * the honest cost of that account and what keeps an empty {@code IN ()} out of the query.
 *
 * <p><b>No write verbs beyond acknowledging.</b> Alerts are not created, edited or deleted through
 * this service; they are raised by the business transactions that produced the facts, through
 * {@code NotificationRecorder}. A screen that could invent an alert would be a second source of
 * operational truth, and there is exactly one.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /**
     * The most alerts one response will ever carry.
     *
     * <p>The panel is "what happened recently", not an archive, and forty rows is already more than
     * anybody reads standing up. Deliberately not paginated: paging a bell turns a glanceable
     * control into a screen, and the alert that is genuinely old is answered by the board it came
     * from - the control tower for a day's shipments, the trip workspace for one shipment's
     * problems.
     */
    public static final int MAX_FEED_SIZE = 40;

    /** What the panel opens with when the caller asks for no particular number. */
    public static final int DEFAULT_FEED_SIZE = 20;

    private final NotificationRepository repository;
    private final AuditActorProvider auditActorProvider;
    private final ObjectMapper objectMapper;

    public NotificationService(NotificationRepository repository, AuditActorProvider auditActorProvider,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.auditActorProvider = auditActorProvider;
        this.objectMapper = objectMapper;
    }

    /** The badge and the panel behind it. */
    @Transactional(readOnly = true)
    public NotificationFeedView feed(CompanyScope scope, Integer requestedLimit) {
        Set<NotificationType> visible = NotificationType.visibleTo(scope.permissions());
        if (visible.isEmpty()) {
            return new NotificationFeedView(0, List.of());
        }
        List<Notification> latest = repository.findByCompanyIdAndTypeInOrderByOccurredAtDescCreatedAtDesc(
                scope.companyId(), visible, PageRequest.of(0, resolveLimit(requestedLimit)));
        long unread = repository.countByCompanyIdAndTypeInAndReadAtIsNull(scope.companyId(), visible);
        return new NotificationFeedView(unread, latest.stream().map(this::toView).toList());
    }

    /**
     * Acknowledges one alert on behalf of the company, and answers with the whole feed.
     *
     * <p>The feed rather than the one row, the same shape {@code TripTenderService} uses: the badge
     * has just changed, and a caller that had to ask for it separately would render a stale count
     * for one paint.
     *
     * <p>A second call is not an error - {@link Notification#markRead} keeps the first reader - so a
     * double click, or two dispatchers clicking the same row, leaves the alert acknowledged once by
     * whoever got there first.
     */
    @Transactional
    public NotificationFeedView markRead(CompanyScope scope, UUID notificationId, Integer requestedLimit) {
        Notification notification = repository.findByIdAndCompanyId(notificationId, scope.companyId())
                // Filtered by visibility as well as by tenant. Inside this company the row exists,
                // but this caller was never entitled to be told about it, and letting them clear it
                // would let an account act on an alert it cannot see. "Not found" is the honest
                // answer for that path, the same one TripDeliveryService gives for a delivery id
                // that belongs to another of this company's trips.
                .filter(candidate -> NotificationType.visibleTo(scope.permissions()).contains(candidate.type()))
                .orElseThrow(() -> new ResourceNotFoundException("Alert not found."));

        notification.markRead(OffsetDateTime.now(), auditActorProvider.requireAppUserId());
        repository.save(notification);
        return feed(scope, requestedLimit);
    }

    /**
     * Clears the badge over everything this caller is entitled to see.
     *
     * <p>Scoped to the visible types for the same reason the feed is: an account that cannot be
     * told about licence expiries must not be able to acknowledge them either, which would clear
     * them out from under the people who can.
     */
    @Transactional
    public NotificationFeedView markAllRead(CompanyScope scope, Integer requestedLimit) {
        Set<NotificationType> visible = NotificationType.visibleTo(scope.permissions());
        if (!visible.isEmpty()) {
            repository.markAllRead(scope.companyId(), visible, OffsetDateTime.now(),
                    auditActorProvider.requireAppUserId());
        }
        return feed(scope, requestedLimit);
    }

    private static int resolveLimit(Integer requested) {
        if (requested == null || requested < 1) {
            return DEFAULT_FEED_SIZE;
        }
        return Math.min(requested, MAX_FEED_SIZE);
    }

    private NotificationView toView(Notification notification) {
        return new NotificationView(
                notification.id(),
                notification.type(),
                notification.severity(),
                notification.entityType(),
                notification.entityId(),
                notification.entityLabel(),
                readMessageArgs(notification),
                notification.occurredAt(),
                notification.readAt(),
                notification.resolvedAt());
    }

    /**
     * The stored placeholders, or none.
     *
     * <p>Unparseable JSON degrades to an empty map instead of failing the response. The row is
     * still a true statement that something happened, and losing the whole panel - which is the
     * only alert surface there is - over one malformed argument map would be the by-product taking
     * down the feature again.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readMessageArgs(Notification notification) {
        String json = notification.messageArgs();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JacksonException unreadable) {
            log.warn("Message arguments of notification {} could not be parsed; serving it without them.",
                    notification.id(), unreadable);
            return Map.of();
        }
    }
}
