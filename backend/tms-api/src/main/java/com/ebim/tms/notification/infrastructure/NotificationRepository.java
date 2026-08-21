package com.ebim.tms.notification.infrastructure;

import com.ebim.tms.notification.domain.Notification;
import com.ebim.tms.shared.notification.NotificationType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Raises one alert, or leaves the existing one exactly as it is.
     *
     * <p><b>Native, and native on purpose.</b> The alternative - read by dedupe key, insert if
     * absent - races between two application instances, and the loser takes a unique violation
     * <em>inside the business transaction that raised the alert</em>. In PostgreSQL a constraint
     * violation aborts the whole transaction, so that shape would let a duplicate bell entry fail a
     * driver assignment or a delivery record. {@code ON CONFLICT DO NOTHING} resolves it inside the
     * statement instead, which is what makes {@code NotificationPublisher.raise}'s "never fails the
     * caller" contract true rather than merely intended.
     *
     * <p>{@code DO NOTHING} and not {@code DO UPDATE}: re-raising must not un-read an alert somebody
     * has already acknowledged, and must not move its {@code occurred_at} to the second time
     * somebody happened to touch the row.
     *
     * <p>The text parameters are cast explicitly. Every one of them can legitimately be null, and
     * PostgreSQL cannot infer the type of a bare null parameter in an {@code INSERT ... VALUES}
     * whose target it has not yet resolved.
     *
     * @return 1 when the alert was new, 0 when it was already on the board
     */
    @Modifying
    @Query(value = """
            INSERT INTO tms.notification (
                company_id, type, severity, entity_type, entity_id, entity_label,
                message_args, dedupe_key, occurred_at)
            VALUES (
                :companyId, CAST(:type AS text), CAST(:severity AS text),
                CAST(:entityType AS text), :entityId, CAST(:entityLabel AS text),
                CAST(:messageArgs AS text), CAST(:dedupeKey AS text), :occurredAt)
            ON CONFLICT (company_id, dedupe_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("companyId") UUID companyId,
            @Param("type") String type,
            @Param("severity") String severity,
            @Param("entityType") String entityType,
            @Param("entityId") UUID entityId,
            @Param("entityLabel") String entityLabel,
            @Param("messageArgs") String messageArgs,
            @Param("dedupeKey") String dedupeKey,
            @Param("occurredAt") OffsetDateTime occurredAt);

    Optional<Notification> findByCompanyIdAndDedupeKey(UUID companyId, String dedupeKey);

    Optional<Notification> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * The feed, newest fact first.
     *
     * <p>Ordered by {@code occurred_at} and tie-broken by {@code created_at}, because a backdated
     * departure and the alert it raises can share a business time with something else recorded in
     * the same batch - and a feed whose order changes between two reads of the same data is a feed
     * an operator loses their place in.
     *
     * <p>{@code types} is never empty when this runs: {@code NotificationService} answers a caller
     * who may see nothing without going to the database at all, which also keeps this query out of
     * the {@code IN ()} syntax error that an empty collection would produce.
     */
    List<Notification> findByCompanyIdAndTypeInOrderByOccurredAtDescCreatedAtDesc(
            UUID companyId, Collection<NotificationType> types, Pageable pageable);

    long countByCompanyIdAndTypeInAndReadAtIsNull(UUID companyId, Collection<NotificationType> types);

    /**
     * Acknowledges every alert the caller is entitled to see in one statement.
     *
     * <p>A bulk {@code UPDATE} rather than a loop over loaded entities: "mark all read" on a desk
     * that has not been looked at since Friday is hundreds of rows, and the alternative is hundreds
     * of round trips to set one column. Restricted to {@code type IN (:types)} for the reason the
     * feed is - a caller must not be able to clear a badge over alerts they were never shown.
     *
     * <p>{@code read_at IS NULL} in the predicate keeps it idempotent and keeps the first reader's
     * credit, exactly as {@code Notification.markRead} does on the single-row path.
     *
     * @return how many were still unread
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Notification n
               SET n.readAt = :readAt, n.readBy = :readBy
             WHERE n.companyId = :companyId
               AND n.type IN :types
               AND n.readAt IS NULL
            """)
    int markAllRead(
            @Param("companyId") UUID companyId,
            @Param("types") Collection<NotificationType> types,
            @Param("readAt") OffsetDateTime readAt,
            @Param("readBy") UUID readBy);
}
