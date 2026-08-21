package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.OrderDelivery;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripException;
import com.ebim.tms.planning.domain.TripExceptionType;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.domain.TripTender;
import com.ebim.tms.shared.notification.NotificationEntityType;
import com.ebim.tms.shared.notification.NotificationPublisher;
import com.ebim.tms.shared.notification.NotificationRequest;
import com.ebim.tms.shared.notification.NotificationSeverity;
import com.ebim.tms.shared.notification.NotificationType;
import com.ebim.tms.shared.reference.DriverReference;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The alert composition rules (migration V32): which facts ring the bell, what makes each of them
 * <em>one</em> fact, and what the sentence is given to say.
 *
 * <p>The dedupe keys get most of the attention here, and deliberately. A key that is too coarse
 * loses alerts silently - three problems on one shipment showing as one - and a key that is too fine
 * turns a re-read of the same lapsed tender into a bell that never stops. Neither failure produces
 * an exception anywhere, so a test is the only place either is visible.
 *
 * <p>Mocked domain objects rather than real ones, for one reason: half these rules are about the
 * <em>id</em> a key is built from, and a {@link Trip} or a {@link TripException} built without a
 * database has none.
 */
class TripAlertPublisherTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP_ID = UUID.randomUUID();
    private static final UUID DRIVER_ID = UUID.randomUUID();
    private static final String SHIPMENT = "SH-00000042";
    private static final LocalDate PLANNING_DATE = LocalDate.of(2026, 8, 20);
    private static final OffsetDateTime PLANNED_DEPARTURE = OffsetDateTime.parse("2026-08-20T08:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-20T09:35:00Z");

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private NotificationPublisher notifications;
    private TripAlertPublisher alerts;
    private Trip trip;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationPublisher.class);
        alerts = new TripAlertPublisher(notifications);

        trip = mock(Trip.class);
        when(trip.id()).thenReturn(TRIP_ID);
        when(trip.shipmentNumber()).thenReturn(SHIPMENT);
        when(trip.planningDate()).thenReturn(PLANNING_DATE);
        when(trip.plannedDepartureAt()).thenReturn(PLANNED_DEPARTURE);
        when(trip.status()).thenReturn(TripStatus.IN_TRANSIT);
    }

    private NotificationRequest raised() {
        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).raise(eq(SCOPE), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("departure")
    class Departure {

        @Test
        @DisplayName("rings once, keyed on the trip, when the shipment left after its planned time")
        void lateDepartureIsAnAlert() {
            OffsetDateTime left = PLANNED_DEPARTURE.plusMinutes(95);
            when(trip.actualDepartureAt()).thenReturn(left);

            alerts.departed(SCOPE, trip, left);

            NotificationRequest request = raised();
            assertThat(request.type()).isEqualTo(NotificationType.TRIP_DELAYED);
            assertThat(request.severity()).isEqualTo(NotificationSeverity.WARNING);
            assertThat(request.entityType()).isEqualTo(NotificationEntityType.TRIP);
            assertThat(request.entityId()).isEqualTo(TRIP_ID);
            assertThat(request.entityLabel()).isEqualTo(SHIPMENT);
            assertThat(request.occurredAt()).isEqualTo(left);
            assertThat(request.dedupeKey()).isEqualTo("TRIP_DELAYED:" + TRIP_ID);
            assertThat(request.messageArgs())
                    .containsEntry("shipmentNumber", SHIPMENT)
                    .containsEntry("minutes", 95L);
        }

        @Test
        @DisplayName("says nothing about a shipment that left on time")
        void punctualDepartureIsNotNews() {
            OffsetDateTime left = PLANNED_DEPARTURE.minusMinutes(3);
            when(trip.actualDepartureAt()).thenReturn(left);

            alerts.departed(SCOPE, trip, left);

            verifyNoInteractions(notifications);
        }

        @Test
        @DisplayName("says nothing when no departure was ever planned - there is nothing to be late against")
        void unplannedDepartureIsNotNews() {
            when(trip.plannedDepartureAt()).thenReturn(null);
            when(trip.actualDepartureAt()).thenReturn(NOW);

            alerts.departed(SCOPE, trip, NOW);

            verifyNoInteractions(notifications);
        }
    }

    @Nested
    @DisplayName("completion")
    class Completion {

        @Test
        @DisplayName("is informational and keyed on the trip, so a retried completion rings once")
        void completionIsInformational() {
            alerts.completed(SCOPE, trip, NOW);

            NotificationRequest request = raised();
            assertThat(request.type()).isEqualTo(NotificationType.TRIP_COMPLETED);
            assertThat(request.severity()).isEqualTo(NotificationSeverity.INFO);
            assertThat(request.dedupeKey()).isEqualTo("TRIP_COMPLETED:" + TRIP_ID);
        }
    }

    @Nested
    @DisplayName("trip exceptions")
    class Exceptions {

        @Test
        @DisplayName("are keyed on the exception, so three problems on one shipment are three alerts")
        void keyedOnTheException() {
            TripException first = exception(TripExceptionType.VEHICLE_BREAKDOWN);
            TripException second = exception(TripExceptionType.TRAFFIC_DELAY);

            alerts.exceptionOpened(SCOPE, trip, first, null);
            alerts.exceptionOpened(SCOPE, trip, second, null);

            ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
            verify(notifications, times(2)).raise(eq(SCOPE), captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(NotificationRequest::dedupeKey)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("point at the trip and name the stop when there is one")
        void namesTheStop() {
            TripStop stop = mock(TripStop.class);
            when(stop.sequence()).thenReturn(3);

            alerts.exceptionOpened(SCOPE, trip, exception(TripExceptionType.DELIVERY_REJECTED), stop);

            NotificationRequest request = raised();
            assertThat(request.type()).isEqualTo(NotificationType.EXCEPTION_OPENED);
            assertThat(request.entityId()).isEqualTo(TRIP_ID);
            assertThat(request.messageArgs())
                    .containsEntry("shipmentNumber", SHIPMENT)
                    .containsEntry("exceptionType", "DELIVERY_REJECTED")
                    .containsEntry("stopSequence", 3);
        }

        /**
         * The pairing this class exists to protect: resolving must compute the same key raising did,
         * or the bell keeps an entry nobody can clear.
         */
        @Test
        @DisplayName("resolve the exact alert they raised")
        void resolutionMatchesTheRaise() {
            TripException problem = exception(TripExceptionType.VEHICLE_BREAKDOWN);

            alerts.exceptionOpened(SCOPE, trip, problem, null);
            String raisedKey = raised().dedupeKey();

            alerts.exceptionResolved(SCOPE, problem, NOW);

            verify(notifications).resolve(SCOPE, raisedKey, NOW);
        }

        private TripException exception(TripExceptionType type) {
            TripException exception = mock(TripException.class);
            when(exception.id()).thenReturn(UUID.randomUUID());
            when(exception.exceptionType()).thenReturn(type);
            when(exception.reportedAt()).thenReturn(NOW);
            return exception;
        }
    }

    @Nested
    @DisplayName("tenders")
    class Tenders {

        @Test
        @DisplayName("are keyed on the attempt, so a shipment refused twice rings twice")
        void keyedOnTheAttempt() {
            UUID tenderId = UUID.randomUUID();
            TripTender tender = mock(TripTender.class);
            when(tender.id()).thenReturn(tenderId);
            when(tender.attempt()).thenReturn(2);

            alerts.tenderRefused(SCOPE, trip, tender, NotificationType.TENDER_REJECTED, NOW);

            NotificationRequest request = raised();
            assertThat(request.type()).isEqualTo(NotificationType.TENDER_REJECTED);
            assertThat(request.dedupeKey()).isEqualTo("TENDER_REJECTED:" + tenderId);
            assertThat(request.messageArgs()).containsEntry("attempt", 2);
        }

        @Test
        @DisplayName("keep a lapse and a refusal apart even on the same offer")
        void expiryAndRejectionAreDifferentFacts() {
            UUID tenderId = UUID.randomUUID();
            TripTender tender = mock(TripTender.class);
            when(tender.id()).thenReturn(tenderId);
            when(tender.attempt()).thenReturn(1);

            alerts.tenderRefused(SCOPE, trip, tender, NotificationType.TENDER_EXPIRED, NOW);

            assertThat(raised().dedupeKey()).isEqualTo("TENDER_EXPIRED:" + tenderId);
        }
    }

    @Nested
    @DisplayName("deliveries")
    class Deliveries {

        private final UUID deliveryId = UUID.randomUUID();

        @Test
        @DisplayName("raise a critical alert when the customer was left short")
        void shortfallIsCritical() {
            alerts.deliveryRecorded(SCOPE, trip, stop(), delivery(DeliveryResult.REJECTED), "ORD-77", NOW);

            NotificationRequest request = raised();
            assertThat(request.type()).isEqualTo(NotificationType.DELIVERY_FAILED);
            assertThat(request.severity()).isEqualTo(NotificationSeverity.CRITICAL);
            assertThat(request.dedupeKey()).isEqualTo("DELIVERY_FAILED:" + deliveryId);
            assertThat(request.messageArgs())
                    .containsEntry("orderNumber", "ORD-77")
                    .containsEntry("result", "REJECTED")
                    .containsEntry("stopSequence", 2);
        }

        /**
         * A delivery record is corrected in place (V28), so the alert has to be able to go away
         * again - otherwise the bell reports a failure somebody has already fixed.
         */
        @Test
        @DisplayName("resolve the alert when a result is corrected to a full delivery")
        void correctionResolvesTheAlert() {
            alerts.deliveryRecorded(SCOPE, trip, stop(), delivery(DeliveryResult.DELIVERED), "ORD-77", NOW);

            verify(notifications).resolve(SCOPE, "DELIVERY_FAILED:" + deliveryId, NOW);
            verify(notifications, never()).raise(any(), any());
        }

        @Test
        @DisplayName("stay quiet for goods never taken off the vehicle - the stop already raised one")
        void notAttemptedIsTheStopsAlert() {
            alerts.deliveryRecorded(SCOPE, trip, stop(), delivery(DeliveryResult.NOT_ATTEMPTED), "ORD-77", NOW);

            verify(notifications, never()).raise(any(), any());
        }

        private TripStop stop() {
            TripStop stop = mock(TripStop.class);
            when(stop.sequence()).thenReturn(2);
            return stop;
        }

        private OrderDelivery delivery(DeliveryResult result) {
            OrderDelivery delivery = mock(OrderDelivery.class);
            when(delivery.id()).thenReturn(deliveryId);
            when(delivery.result()).thenReturn(result);
            return delivery;
        }
    }

    @Nested
    @DisplayName("driver licences")
    class Licences {

        @Test
        @DisplayName("warn once per driver per expiry date, and point at the driver rather than the trip")
        void warnsOncePerExpiry() {
            LocalDate expiry = PLANNING_DATE.plusDays(12);

            alerts.driverAssigned(SCOPE, trip, driver(expiry), NOW);

            NotificationRequest request = raised();
            assertThat(request.type()).isEqualTo(NotificationType.DRIVER_LICENSE_EXPIRING);
            assertThat(request.entityType()).isEqualTo(NotificationEntityType.DRIVER);
            assertThat(request.entityId()).isEqualTo(DRIVER_ID);
            assertThat(request.entityLabel()).isEqualTo("DR-1");
            assertThat(request.dedupeKey())
                    .isEqualTo("DRIVER_LICENSE_EXPIRING:" + DRIVER_ID + ":" + expiry);
            assertThat(request.messageArgs())
                    .containsEntry("driverName", "Quispe, Ana")
                    .containsEntry("expiresOn", expiry.toString())
                    .containsEntry("shipmentNumber", SHIPMENT);
        }

        @Test
        @DisplayName("say nothing about a licence that is nowhere near running out")
        void validLicenceIsNotNews() {
            alerts.driverAssigned(SCOPE, trip, driver(PLANNING_DATE.plusYears(2)), NOW);

            verifyNoInteractions(notifications);
        }

        @Test
        @DisplayName("say nothing when no expiry is on file - not knowing one is not evidence of one")
        void unrecordedLicenceIsNotNews() {
            alerts.driverAssigned(SCOPE, trip, driver(null), NOW);

            verifyNoInteractions(notifications);
        }

        /**
         * An expired licence never reaches here: {@code TripService} refuses the assignment. Asserted
         * anyway, because the day that refusal is relaxed the bell must not start reporting an
         * assignment that did not happen.
         */
        @Test
        @DisplayName("say nothing about a licence that has already expired - that assignment was refused")
        void expiredLicenceIsNotAnAlert() {
            alerts.driverAssigned(SCOPE, trip, driver(PLANNING_DATE.minusDays(1)), NOW);

            verifyNoInteractions(notifications);
        }

        @Test
        @DisplayName("do not resolve anything - a licence stops expiring by being renewed, not by being seen")
        void nothingIsResolved() {
            alerts.driverAssigned(SCOPE, trip, driver(PLANNING_DATE.plusDays(1)), NOW);

            verify(notifications, never()).resolve(any(), anyString(), any());
        }

        private DriverReference driver(LocalDate licenseExpiresOn) {
            return new DriverReference(DRIVER_ID, "DR-1", "Quispe, Ana", "DNI", "12345678", "+51 999 111 222",
                    "Q-987654", "A-IIB", licenseExpiresOn, UUID.randomUUID(), true);
        }
    }
}
