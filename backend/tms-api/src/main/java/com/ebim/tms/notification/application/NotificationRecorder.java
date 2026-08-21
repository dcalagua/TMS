package com.ebim.tms.notification.application;

import com.ebim.tms.notification.domain.Notification;
import com.ebim.tms.notification.infrastructure.NotificationRepository;
import com.ebim.tms.shared.notification.NotificationPublisher;
import com.ebim.tms.shared.notification.NotificationRequest;
import com.ebim.tms.shared.notification.NotificationType;
import com.ebim.tms.shared.security.CompanyScope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The only implementation of {@link NotificationPublisher}: writes one row of
 * {@code tms.notification} per alert, in the caller's own transaction.
 *
 * <p>See {@link NotificationPublisher} for why every other business module reaches this through the
 * port rather than importing this class, and for the two guarantees it makes - that an alert rolls
 * back with the fact it announces, and that it can never be the reason that fact fails to commit.
 * This class is where the second one is actually kept: the insert is a single
 * {@code ON CONFLICT DO NOTHING} statement, and the only other thing that could throw - serialising
 * the message arguments - is caught and degraded rather than propagated.
 *
 * <p>Every raise increments {@code tms.notification.raised}, tagged {@code type} and
 * {@code severity}, and a duplicate that changed nothing is counted separately as
 * {@code tms.notification.suppressed}. Watching the two together is how a badly chosen dedupe key
 * shows up - as a suppression rate near 100% for one type - without querying the table itself.
 */
@Service
public class NotificationRecorder implements NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationRecorder.class);

    /** Matches {@code ck_notification_message_args_length} (migration V32). */
    private static final int MAX_MESSAGE_ARGS_LENGTH = 2000;

    private static final String RAISED_METRIC = "tms.notification.raised";
    private static final String SUPPRESSED_METRIC = "tms.notification.suppressed";

    private final NotificationRepository repository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public NotificationRecorder(NotificationRepository repository, ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void raise(CompanyScope scope, NotificationRequest request) {
        int inserted = repository.insertIfAbsent(
                scope.companyId(),
                request.type().name(),
                request.severity().name(),
                request.entityType().name(),
                request.entityId(),
                request.entityLabel(),
                writeMessageArgs(request.type(), request.messageArgs()),
                request.dedupeKey(),
                request.occurredAt());

        Counter.builder(inserted > 0 ? RAISED_METRIC : SUPPRESSED_METRIC)
                .tag("type", request.type().name())
                .tag("severity", request.severity().name())
                .register(meterRegistry)
                .increment();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Loaded and mutated rather than updated in place, unlike {@code markAllRead}: this touches
     * at most one row, and going through {@link Notification#resolve} keeps "the first resolution
     * is the one that happened" a rule of the entity instead of a {@code WHERE resolved_at IS NULL}
     * that a second caller could forget.
     */
    @Override
    public void resolve(CompanyScope scope, String dedupeKey, OffsetDateTime resolvedAt) {
        repository.findByCompanyIdAndDedupeKey(scope.companyId(), dedupeKey)
                .ifPresent(notification -> {
                    notification.resolve(resolvedAt);
                    repository.save(notification);
                });
    }

    /**
     * The placeholders, as compact JSON.
     *
     * <p>A serialisation failure drops the arguments and keeps the alert. The sentence then renders
     * with empty placeholders, which is worse to read and far better than the alternative: an
     * unserialisable value in a metadata map would otherwise take down the dispatch, delivery or
     * assignment that raised it. Same trade {@code AuditEventRecorder} makes, and for the same
     * reason.
     *
     * <p>Truncation is belt and braces against {@code ck_notification_message_args_length}. It
     * produces invalid JSON when it fires, which the read side handles by showing the alert without
     * its arguments - a hit that would mean somebody put a payload in a placeholder map.
     */
    private String writeMessageArgs(NotificationType type, Map<String, Object> messageArgs) {
        if (messageArgs == null || messageArgs.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(messageArgs);
            if (json.length() > MAX_MESSAGE_ARGS_LENGTH) {
                log.warn("Message arguments for a {} alert exceeded {} characters and were dropped; "
                        + "the alert is raised without them.", type, MAX_MESSAGE_ARGS_LENGTH);
                return null;
            }
            return json;
        } catch (JacksonException notSerialisable) {
            log.warn("Message arguments for a {} alert could not be serialised; raising it without them.",
                    type, notSerialisable);
            return null;
        }
    }
}
