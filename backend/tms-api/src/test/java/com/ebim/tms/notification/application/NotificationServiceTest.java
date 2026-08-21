package com.ebim.tms.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.notification.domain.Notification;
import com.ebim.tms.notification.infrastructure.NotificationRepository;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.notification.NotificationEntityType;
import com.ebim.tms.shared.notification.NotificationSeverity;
import com.ebim.tms.shared.notification.NotificationType;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the bell is allowed to say, and to whom (migration V32 section 4).
 *
 * <p>The disclosure rule is the whole point of this file. {@code NotificationController} carries no
 * {@code @PreAuthorize} - the bell is a permanent control and a 403 on every page load would be
 * worse than an empty panel - so the <em>only</em> thing standing between an account with no fleet
 * permission and a driver's licence expiry is the type filter tested below. If it stops working,
 * nothing fails: the panel just starts showing more than it should.
 */
class NotificationServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ALERT_ID = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-20T09:35:00Z");

    private NotificationRepository repository;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        AuditActorProvider actors = mock(AuditActorProvider.class);
        when(actors.requireAppUserId()).thenReturn(ACTOR);
        // A real mapper, not a mock: half of what this service does to an alert on the way out is
        // parse its stored arguments, and a stubbed parser would test nothing.
        service = new NotificationService(repository, actors, JsonMapper.builder().build());
    }

    private static CompanyScope scopeWith(Permission... permissions) {
        return new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
                UUID.randomUUID(), "ORG", "Organization", Set.of(permissions));
    }

    /**
     * Always call this <em>before</em> the {@code when(...)} it feeds, never inside the
     * {@code thenReturn(...)}. It stubs a mock of its own, and Mockito tracks one stubbing at a
     * time: starting this one while the outer {@code when(...)} is still open is what
     * {@code UnfinishedStubbingException} is reporting, and the outer stub silently never gets
     * registered.
     */
    private static Notification alert(NotificationType type, String messageArgs) {
        Notification notification = mock(Notification.class);
        when(notification.id()).thenReturn(ALERT_ID);
        when(notification.type()).thenReturn(type);
        when(notification.severity()).thenReturn(type.severity());
        when(notification.entityType()).thenReturn(type.entityType());
        when(notification.entityId()).thenReturn(UUID.randomUUID());
        when(notification.entityLabel()).thenReturn("SH-00000042");
        when(notification.messageArgs()).thenReturn(messageArgs);
        when(notification.occurredAt()).thenReturn(NOW);
        return notification;
    }

    @Nested
    @DisplayName("who sees what")
    class Visibility {

        @Test
        @DisplayName("answers an account with no relevant permission without touching the database")
        void nothingVisibleMeansNoQuery() {
            NotificationFeedView feed = service.feed(scopeWith(Permission.ORDERS_ORDER_READ), null);

            assertThat(feed.unreadCount()).isZero();
            assertThat(feed.notifications()).isEmpty();
            // Also the reason this shortcut exists: an empty type set would reach the query as
            // `type IN ()`, which is a syntax error rather than an empty result.
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("asks only for the types the caller's permissions cover")
        void queryIsFilteredByPermission() {
            CompanyScope scope = scopeWith(Permission.FLEET_DRIVER_READ);
            when(repository.findByCompanyIdAndTypeInOrderByOccurredAtDescCreatedAtDesc(
                    eq(COMPANY), anyCollection(), any(Pageable.class))).thenReturn(List.of());

            service.feed(scope, null);

            // Compared against the exact set rather than captured: EnumSet and Set.of are equal by
            // AbstractSet's contract, and asserting equality says "these and nothing else", which
            // is the whole claim.
            verify(repository).findByCompanyIdAndTypeInOrderByOccurredAtDescCreatedAtDesc(
                    eq(COMPANY), eq(Set.of(NotificationType.DRIVER_LICENSE_EXPIRING)), any(Pageable.class));
        }

        @Test
        @DisplayName("keeps a transport monitor away from tender outcomes and licence expiries")
        void monitorSeesOperationsOnly() {
            assertThat(NotificationType.visibleTo(Set.of(Permission.MONITORING_TRANSPORT_READ)))
                    .containsExactlyInAnyOrder(
                            NotificationType.TRIP_DELAYED,
                            NotificationType.TRIP_COMPLETED,
                            NotificationType.EXCEPTION_OPENED,
                            NotificationType.DELIVERY_FAILED);
        }

        @Test
        @DisplayName("refuses to acknowledge an alert of a type the caller may not be told about")
        void cannotClearWhatYouCannotSee() {
            CompanyScope scope = scopeWith(Permission.MONITORING_TRANSPORT_READ);
            Notification unseeable = alert(NotificationType.DRIVER_LICENSE_EXPIRING, null);
            when(repository.findByIdAndCompanyId(ALERT_ID, COMPANY)).thenReturn(Optional.of(unseeable));

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> service.markRead(scope, ALERT_ID, null));
            verify(repository, never()).save(any(Notification.class));
        }

        @Test
        @DisplayName("clears the badge only over the types the caller may see")
        void markAllReadIsScopedToo() {
            CompanyScope scope = scopeWith(Permission.PLANNING_TENDER_READ);

            service.markAllRead(scope, null);

            verify(repository).markAllRead(eq(COMPANY),
                    eq(Set.of(NotificationType.TENDER_REJECTED, NotificationType.TENDER_EXPIRED)),
                    any(OffsetDateTime.class), eq(ACTOR));
        }
    }

    @Nested
    @DisplayName("the panel")
    class Panel {

        private final CompanyScope scope = scopeWith(Permission.MONITORING_TRANSPORT_READ);

        @Test
        @DisplayName("caps an oversized limit instead of refusing a top-bar control over a query parameter")
        void limitIsCapped() {
            when(repository.findByCompanyIdAndTypeInOrderByOccurredAtDescCreatedAtDesc(
                    eq(COMPANY), anyCollection(), any(Pageable.class))).thenReturn(List.of());

            service.feed(scope, 5_000);

            ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
            verify(repository).findByCompanyIdAndTypeInOrderByOccurredAtDescCreatedAtDesc(
                    eq(COMPANY), anyCollection(), page.capture());
            assertThat(page.getValue().getPageSize()).isEqualTo(NotificationService.MAX_FEED_SIZE);
        }

        @Test
        @DisplayName("falls back to the default size for an absent or nonsensical limit")
        void limitDefaults() {
            when(repository.findByCompanyIdAndTypeInOrderByOccurredAtDescCreatedAtDesc(
                    eq(COMPANY), anyCollection(), any(Pageable.class))).thenReturn(List.of());

            service.feed(scope, 0);

            ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
            verify(repository).findByCompanyIdAndTypeInOrderByOccurredAtDescCreatedAtDesc(
                    eq(COMPANY), anyCollection(), page.capture());
            assertThat(page.getValue().getPageSize()).isEqualTo(NotificationService.DEFAULT_FEED_SIZE);
        }

        @Test
        @DisplayName("hands the stored placeholders to the caller, never a rendered sentence")
        void argumentsTravelAsData() {
            Notification delayed = alert(NotificationType.TRIP_DELAYED,
                    "{\"shipmentNumber\":\"SH-00000042\",\"minutes\":95}");
            when(repository.findByCompanyIdAndTypeInOrderByOccurredAtDescCreatedAtDesc(
                    eq(COMPANY), anyCollection(), any(Pageable.class)))
                    .thenReturn(List.of(delayed));
            when(repository.countByCompanyIdAndTypeInAndReadAtIsNull(eq(COMPANY), anyCollection())).thenReturn(3L);

            NotificationFeedView feed = service.feed(scope, null);

            assertThat(feed.unreadCount()).isEqualTo(3L);
            NotificationView view = feed.notifications().getFirst();
            assertThat(view.type()).isEqualTo(NotificationType.TRIP_DELAYED);
            assertThat(view.severity()).isEqualTo(NotificationSeverity.WARNING);
            assertThat(view.entityType()).isEqualTo(NotificationEntityType.TRIP);
            assertThat(view.entityLabel()).isEqualTo("SH-00000042");
            assertThat(view.messageArgs())
                    .containsEntry("shipmentNumber", "SH-00000042")
                    .containsEntry("minutes", 95);
        }

        /**
         * The bell is the only alert surface there is. Losing the whole panel because one row's
         * argument map was written by a build that shaped it differently would be the by-product
         * taking down the feature.
         */
        @Test
        @DisplayName("serves an alert whose stored arguments can no longer be parsed, without them")
        void malformedArgumentsDegrade() {
            Notification truncated = alert(NotificationType.TRIP_DELAYED, "{\"shipmentNumber\":");
            when(repository.findByCompanyIdAndTypeInOrderByOccurredAtDescCreatedAtDesc(
                    eq(COMPANY), anyCollection(), any(Pageable.class)))
                    .thenReturn(List.of(truncated));

            NotificationFeedView feed = service.feed(scope, null);

            assertThat(feed.notifications()).hasSize(1);
            assertThat(feed.notifications().getFirst().messageArgs()).isEmpty();
        }

        @Test
        @DisplayName("counts unread over the whole history, not over the page it just returned")
        void badgeIsNotThePageSize() {
            Notification completed = alert(NotificationType.TRIP_COMPLETED, null);
            when(repository.findByCompanyIdAndTypeInOrderByOccurredAtDescCreatedAtDesc(
                    eq(COMPANY), anyCollection(), any(Pageable.class)))
                    .thenReturn(List.of(completed));
            when(repository.countByCompanyIdAndTypeInAndReadAtIsNull(eq(COMPANY), anyCollection())).thenReturn(137L);

            assertThat(service.feed(scope, null).unreadCount()).isEqualTo(137L);
        }
    }
}
