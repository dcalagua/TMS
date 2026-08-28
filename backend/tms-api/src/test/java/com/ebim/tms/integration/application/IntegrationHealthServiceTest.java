package com.ebim.tms.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ebim.tms.integration.domain.IntegrationRequestStatus;
import com.ebim.tms.integration.domain.WebhookDeliveryStatus;
import com.ebim.tms.integration.infrastructure.IntegrationRequestRepository;
import com.ebim.tms.integration.infrastructure.WebhookDeliveryRepository;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The integration health summary (JOB 13).
 *
 * <p>Everything it reports was already reachable by paging through two lists, which is exactly the
 * problem: an operator asking "is anything broken" should get an answer rather than a search.
 *
 * <p>The two cases worth the most here are {@link Stuck#reportsTheAgeOfTheOldestPending} and
 * {@link Silence#countsInactiveSubscriptionsHoldingABacklog} - the first because a count of pending
 * deliveries cannot tell a moving queue from a stuck one, and the second because a subscription
 * switched off during an incident and never switched back on produces no errors at all.
 */
class IntegrationHealthServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    private WebhookDeliveryRepository deliveryRepository;
    private IntegrationRequestRepository requestRepository;
    private IntegrationHealthService service;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(WebhookDeliveryRepository.class);
        requestRepository = mock(IntegrationRequestRepository.class);
        when(deliveryRepository.countByStatus(any())).thenReturn(List.of());
        when(deliveryRepository.findOldestCreatedAt(any(), any())).thenReturn(Optional.empty());
        when(deliveryRepository.countInactiveSubscriptionsWithBacklog(any(), any())).thenReturn(0L);
        when(requestRepository.countByStatusSince(any(), any())).thenReturn(List.of());
        service = new IntegrationHealthService(deliveryRepository, requestRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CompanyScope scope() {
        return new CompanyScope(COMPANY, "INT", "Integration Co", "America/Lima", UUID.randomUUID(),
                "ORG", "Org", EnumSet.allOf(Permission.class));
    }

    private static WebhookDeliveryRepository.DeliveryStatusCount deliveries(
            WebhookDeliveryStatus status, long count) {
        return new WebhookDeliveryRepository.DeliveryStatusCount() {
            @Override
            public WebhookDeliveryStatus getStatus() {
                return status;
            }

            @Override
            public long getDeliveryCount() {
                return count;
            }
        };
    }

    private static IntegrationRequestRepository.RequestStatusCount requests(
            IntegrationRequestStatus status, long count) {
        return new IntegrationRequestRepository.RequestStatusCount() {
            @Override
            public IntegrationRequestStatus getStatus() {
                return status;
            }

            @Override
            public long getRequestCount() {
                return count;
            }
        };
    }

    @Nested
    @DisplayName("when nothing has gone wrong")
    class Healthy {

        @Test
        @DisplayName("every figure is zero and nothing is null except the age nobody has")
        void allZero() {
            IntegrationHealthView health = service.health(scope());

            assertThat(health.deliveriesPending()).isZero();
            assertThat(health.deliveriesFailed()).isZero();
            assertThat(health.inactiveSubscriptionsWithBacklog()).isZero();
            // Null because nothing is waiting - the good answer, not a missing one.
            assertThat(health.oldestPendingAt()).isNull();
        }

        /**
         * A status absent from the grouped count means none in that state, and reading it as zero
         * is the whole reason the query groups rather than counting three times.
         */
        @Test
        @DisplayName("a status the query did not return counts as zero, not as unknown")
        void absentStatusIsZero() {
            when(deliveryRepository.countByStatus(any()))
                    .thenReturn(List.of(deliveries(WebhookDeliveryStatus.PROCESSED, 1_000)));

            IntegrationHealthView health = service.health(scope());

            assertThat(health.deliveriesProcessed()).isEqualTo(1_000);
            assertThat(health.deliveriesFailed()).isZero();
        }
    }

    @Nested
    @DisplayName("a queue that is not draining")
    class Stuck {

        /**
         * The signal a count cannot carry. A thousand pending rows that are moving is healthy;
         * three that have been waiting since Tuesday is not, and only the age separates them.
         */
        @Test
        @DisplayName("reports when the oldest waiting delivery was created")
        void reportsTheAgeOfTheOldestPending() {
            OffsetDateTime tuesday = OffsetDateTime.parse("2026-09-01T08:00:00Z");
            when(deliveryRepository.countByStatus(any()))
                    .thenReturn(List.of(deliveries(WebhookDeliveryStatus.PENDING, 3)));
            when(deliveryRepository.findOldestCreatedAt(eq(COMPANY), eq(WebhookDeliveryStatus.PENDING)))
                    .thenReturn(Optional.of(tuesday));

            IntegrationHealthView health = service.health(scope());

            assertThat(health.deliveriesPending()).isEqualTo(3);
            assertThat(health.oldestPendingAt()).isEqualTo(tuesday);
        }

        /**
         * Exhausted retries are a work queue and not a statistic: nothing will send these again
         * unless a person asks, which is why they are counted apart from pending.
         */
        @Test
        @DisplayName("exhausted deliveries are counted apart from ones still waiting")
        void failedIsNotPending() {
            when(deliveryRepository.countByStatus(any())).thenReturn(List.of(
                    deliveries(WebhookDeliveryStatus.PENDING, 2),
                    deliveries(WebhookDeliveryStatus.FAILED, 17)));

            IntegrationHealthView health = service.health(scope());

            assertThat(health.deliveriesPending()).isEqualTo(2);
            assertThat(health.deliveriesFailed()).isEqualTo(17);
        }
    }

    @Nested
    @DisplayName("the failure that looks like silence")
    class Silence {

        /**
         * Deactivating a subscription stops deliveries and discards nothing - events keep queueing,
         * exactly as the deactivate endpoint documents. So a partner switched off "for an hour"
         * during an incident and never switched back on produces <b>no errors at all</b>, and
         * nothing else on this screen would show it.
         */
        @Test
        @DisplayName("subscriptions switched off with deliveries queued behind them are counted")
        void countsInactiveSubscriptionsHoldingABacklog() {
            when(deliveryRepository.countInactiveSubscriptionsWithBacklog(
                    eq(COMPANY), eq(WebhookDeliveryStatus.PENDING))).thenReturn(2L);

            assertThat(service.health(scope()).inactiveSubscriptionsWithBacklog()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("the inbound side")
    class Inbound {

        /**
         * Windowed, not lifetime. A partner that failed a hundred times last month and has worked
         * all week is working, and a lifetime count would go on saying otherwise forever.
         */
        @Test
        @DisplayName("counts the last 24 hours, and says which 24 hours those were")
        void windowIsTwentyFourHoursAndIsStated() {
            service.health(scope());

            ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
            org.mockito.Mockito.verify(requestRepository).countByStatusSince(eq(COMPANY), since.capture());
            assertThat(Duration.between(since.getValue().toInstant(), NOW)).isEqualTo(Duration.ofHours(24));
            assertThat(service.health(scope()).requestsSince()).isEqualTo(since.getValue());
        }

        /**
         * A rejection is the partner's payload being wrong; a failure is TMS not coping. They are
         * different phone calls, so they are different numbers.
         */
        @Test
        @DisplayName("keeps rejected and failed apart - one is theirs, one is ours")
        void rejectedIsNotFailed() {
            when(requestRepository.countByStatusSince(any(), any())).thenReturn(List.of(
                    requests(IntegrationRequestStatus.SUCCEEDED, 400),
                    requests(IntegrationRequestStatus.PARTIAL, 3),
                    requests(IntegrationRequestStatus.REJECTED, 12),
                    requests(IntegrationRequestStatus.FAILED, 1)));

            IntegrationHealthView health = service.health(scope());

            assertThat(health.requestsSucceeded()).isEqualTo(400);
            assertThat(health.requestsPartial()).isEqualTo(3);
            assertThat(health.requestsRejected()).isEqualTo(12);
            assertThat(health.requestsFailed()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("tenancy")
    class Tenancy {

        /** Every query carries the company. Health is tenant data like everything else. */
        @Test
        @DisplayName("every repository call is scoped to the caller's company")
        void everyQueryIsScoped() {
            service.health(scope());

            org.mockito.Mockito.verify(deliveryRepository).countByStatus(COMPANY);
            org.mockito.Mockito.verify(deliveryRepository).findOldestCreatedAt(eq(COMPANY), any());
            org.mockito.Mockito.verify(deliveryRepository).countInactiveSubscriptionsWithBacklog(eq(COMPANY), any());
            org.mockito.Mockito.verify(requestRepository).countByStatusSince(eq(COMPANY), any());
        }
    }
}
