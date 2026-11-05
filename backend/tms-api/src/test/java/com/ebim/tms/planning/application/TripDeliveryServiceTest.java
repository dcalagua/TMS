package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.OrderDelivery;
import com.ebim.tms.planning.domain.ShipmentEventType;
import com.ebim.tms.planning.domain.StopExecutionStatus;
import com.ebim.tms.planning.domain.TransportEventSource;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.OrderDeliveryLineQueries;
import com.ebim.tms.planning.infrastructure.OrderDeliveryLineQueries.DeliveryLineWrite;
import com.ebim.tms.planning.infrastructure.OrderDeliveryRepository;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActor;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.reference.OrderLineReference;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.OrderReferencePort;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The rules {@link TripDeliveryService} adds on top of {@link DeliveryResult}'s own: the shipment
 * must have run, the stop must have been reached, the order must be going to that stop, and the
 * delivery time must fit inside the day it claims to be part of.
 *
 * <p><b>The trip and its stop are mocked, not built</b> - the service finds its stop by id and an
 * unflushed {@code TripStop} has none. The rules those ids protect are asserted against real
 * entities in {@code planning.domain.DeliveryResultTest}; the two meet in
 * {@code PlanningApiIntegrationTest}, which needs Docker.
 */
class TripDeliveryServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP_ID = UUID.randomUUID();
    private static final UUID STOP_ID = UUID.randomUUID();
    private static final UUID DESTINATION_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    /** Relative to the wall clock, never a literal instant - see {@code TripStopExecutionServiceTest}. */
    private static final OffsetDateTime DEPARTED_AT = OffsetDateTime.now().minusHours(4);
    private static final OffsetDateTime ARRIVED_AT = DEPARTED_AT.plusHours(1);

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private TripRepository tripRepository;
    private OrderDeliveryRepository deliveryRepository;
    private TripOrderAssignmentRepository assignmentRepository;
    private OrderPlanningPort orderPlanningPort;
    private ShipmentEventPublisher eventPublisher;
    private OrderExecutionPropagator orderExecution;
    private OrderReferencePort orderReferencePort;
    private OrderDeliveryLineQueries deliveryLines;
    private TripDeliveryService service;
    private Trip trip;
    private TripStop stop;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        deliveryRepository = mock(OrderDeliveryRepository.class);
        assignmentRepository = mock(TripOrderAssignmentRepository.class);
        orderPlanningPort = mock(OrderPlanningPort.class);
        eventPublisher = mock(ShipmentEventPublisher.class);
        TripViewAssembler assembler = mock(TripViewAssembler.class);
        AuditActorProvider actors = mock(AuditActorProvider.class);
        when(actors.current()).thenReturn(Optional.of(
                AuditActor.person(ACTOR, "dispatcher@example.com", COMPANY, UUID.randomUUID(), "corr")));

        orderExecution = mock(OrderExecutionPropagator.class);
        orderReferencePort = mock(OrderReferencePort.class);
        deliveryLines = mock(OrderDeliveryLineQueries.class);
        service = new TripDeliveryService(tripRepository, deliveryRepository, assignmentRepository,
                orderPlanningPort, eventPublisher, mock(TripAlertPublisher.class), assembler, orderExecution,
                actors, orderReferencePort, deliveryLines);

        stop = mock(TripStop.class);
        when(stop.id()).thenReturn(STOP_ID);
        when(stop.sequence()).thenReturn(2);
        when(stop.destinationId()).thenReturn(DESTINATION_ID);
        when(stop.executionStatus()).thenReturn(StopExecutionStatus.COMPLETED);
        when(stop.actualArrivalAt()).thenReturn(ARRIVED_AT);

        trip = mock(Trip.class);
        when(trip.id()).thenReturn(TRIP_ID);
        when(trip.shipmentNumber()).thenReturn("SHP-000123");
        when(trip.status()).thenReturn(TripStatus.IN_TRANSIT);
        when(trip.stops()).thenReturn(List.of(stop));
        when(trip.actualDepartureAt()).thenReturn(DEPARTED_AT);

        when(tripRepository.findByIdAndCompanyIdForUpdate(TRIP_ID, COMPANY)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripIdAndOrderIdAndStatus(TRIP_ID, ORDER_ID, AssignmentStatus.ACTIVE))
                .thenReturn(Optional.of(mock(TripOrderAssignment.class)));
        when(orderPlanningPort.findAllInCompany(Set.of(ORDER_ID), COMPANY))
                .thenReturn(Map.of(ORDER_ID, order(DESTINATION_ID)));
        when(deliveryRepository.findByCompanyIdAndTripStopIdAndOrderId(COMPANY, STOP_ID, ORDER_ID))
                .thenReturn(Optional.empty());
        when(deliveryRepository.saveAndFlush(any(OrderDelivery.class))).thenAnswer(call -> call.getArgument(0));
        when(assembler.toDetail(any(Trip.class), eq(COMPANY)))
                .thenAnswer(call -> new TripDetailView(null, List.of(), List.of(), List.of(), List.of(), TripRouteMetrics.NONE));
    }

    private static PlannableOrder order(UUID destinationId) {
        return new PlannableOrder(ORDER_ID, "ORD-0001", UUID.randomUUID(), destinationId, "Customer", null,
                LocalDate.now(), "NORMAL", null, null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, null,
                OrderAmounts.NONE);
    }

    private static DeliveryResultRequest delivered(OffsetDateTime at) {
        return new DeliveryResultRequest(DeliveryResult.DELIVERED, at, "R. Diaz", null, null);
    }

    private TripDetailView record(DeliveryResultRequest request) {
        return service.record(SCOPE, TRIP_ID, STOP_ID, ORDER_ID, request);
    }

    @Nested
    @DisplayName("before it records anything")
    class Preconditions {

        @Test
        @DisplayName("refuses a shipment that has not left")
        void refusesAShipmentStillOnTheDock() {
            when(trip.status()).thenReturn(TripStatus.READY_FOR_DISPATCH);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> record(delivered(ARRIVED_AT)))
                    .withMessageContaining("READY_FOR_DISPATCH");
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("accepts a shipment already closed out - that is when the paperwork comes back")
        void acceptsACompletedShipment() {
            when(trip.status()).thenReturn(TripStatus.COMPLETED);

            assertThat(record(delivered(ARRIVED_AT))).isNotNull();

            verify(deliveryRepository).saveAndFlush(any(OrderDelivery.class));
        }

        @Test
        @DisplayName("refuses a stop the vehicle has not reached")
        void refusesAPendingStop() {
            when(stop.executionStatus()).thenReturn(StopExecutionStatus.PENDING);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> record(delivered(ARRIVED_AT)))
                    .withMessageContaining("has not been reached");
        }

        @Test
        @DisplayName("accepts a stop that failed - which is where NOT_ATTEMPTED rows come from")
        void acceptsAFailedStop() {
            when(stop.executionStatus()).thenReturn(StopExecutionStatus.FAILED);

            assertThat(record(new DeliveryResultRequest(DeliveryResult.NOT_ATTEMPTED, null, null, null, null)))
                    .isNotNull();
        }

        @Test
        @DisplayName("refuses a stop id that belongs to another trip")
        void refusesAStopItDoesNotHave() {
            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> service.record(SCOPE, TRIP_ID, UUID.randomUUID(), ORDER_ID,
                            delivered(ARRIVED_AT)));
        }

        @Test
        @DisplayName("refuses an order that is not on this trip")
        void refusesAnUnassignedOrder() {
            when(assignmentRepository.findByTripIdAndOrderIdAndStatus(TRIP_ID, ORDER_ID, AssignmentStatus.ACTIVE))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> record(delivered(ARRIVED_AT)))
                    .withMessageContaining("not currently assigned");
        }

        @Test
        @DisplayName("refuses an order that is going to a different stop of the same trip")
        void refusesAnOrderForAnotherStop() {
            when(orderPlanningPort.findAllInCompany(Set.of(ORDER_ID), COMPANY))
                    .thenReturn(Map.of(ORDER_ID, order(UUID.randomUUID())));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> record(delivered(ARRIVED_AT)))
                    .withMessageContaining("not delivered at stop");
            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("the result it accepts")
    class Results {

        @Test
        @DisplayName("demands a delivery time for a handover")
        void deliveredNeedsATime() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> record(new DeliveryResultRequest(DeliveryResult.DELIVERED, null, null, null,
                            null)))
                    .withMessageContaining("deliveredAt");
        }

        @Test
        @DisplayName("refuses a delivery time on something that was never attempted")
        void notAttemptedRefusesATime() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> record(new DeliveryResultRequest(DeliveryResult.NOT_ATTEMPTED, ARRIVED_AT,
                            null, null, null)))
                    .withMessageContaining("deliveredAt");
        }

        @Test
        @DisplayName("refuses a receiver on a result where nobody received anything")
        void failedRefusesAReceiver() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> record(new DeliveryResultRequest(DeliveryResult.FAILED, null, "R. Diaz",
                            null, "nobody there")))
                    .withMessageContaining("receiver");
        }

        @Test
        @DisplayName("demands an explanation for anything short of a clean delivery")
        void shortfallNeedsNotes() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> record(new DeliveryResultRequest(DeliveryResult.PARTIAL, ARRIVED_AT, null,
                            null, "  ")))
                    .withMessageContaining("notes");
        }
    }

    @Nested
    @DisplayName("the time it accepts")
    class Times {

        @Test
        @DisplayName("refuses one in the future beyond the clock-skew tolerance")
        void refusesAFutureTime() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> record(delivered(OffsetDateTime.now().plusHours(1))))
                    .withMessageContaining("future");
        }

        @Test
        @DisplayName("refuses one earlier than the arrival at the stop it happened at")
        void refusesATimeBeforeTheVehicleArrived() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> record(delivered(ARRIVED_AT.minusMinutes(10))))
                    .withMessageContaining("cannot be before");
        }

        @Test
        @DisplayName("falls back to the trip's departure when the stop recorded no arrival")
        void boundedByTheDepartureWhenNoArrivalWasRecorded() {
            when(stop.actualArrivalAt()).thenReturn(null);

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> record(delivered(DEPARTED_AT.minusMinutes(1))))
                    .withMessageContaining("cannot be before");
            assertThat(record(delivered(DEPARTED_AT.plusMinutes(30)))).isNotNull();
        }
    }

    @Nested
    @DisplayName("what it writes")
    class Writes {

        @Test
        @DisplayName("records the delivery with the actor resolved server-side")
        void recordsTheDelivery() {
            record(delivered(ARRIVED_AT));

            ArgumentCaptor<OrderDelivery> captor = ArgumentCaptor.forClass(OrderDelivery.class);
            verify(deliveryRepository).saveAndFlush(captor.capture());
            OrderDelivery saved = captor.getValue();
            assertThat(saved.result()).isEqualTo(DeliveryResult.DELIVERED);
            assertThat(saved.tripStopId()).isEqualTo(STOP_ID);
            assertThat(saved.orderId()).isEqualTo(ORDER_ID);
            assertThat(saved.receiverName()).isEqualTo("R. Diaz");
            assertThat(saved.source()).isEqualTo(TransportEventSource.OPERATOR);
            assertThat(saved.actorDisplayName()).isEqualTo("dispatcher@example.com");
        }

        @Test
        @DisplayName("publishes one event naming the stop, at the time of the handover")
        void publishesTheEvent() {
            record(delivered(ARRIVED_AT));

            verify(eventPublisher).publish(eq(SCOPE), eq(trip), eq(STOP_ID),
                    eq(ShipmentEventType.DELIVERY_RESULT_RECORDED), eq(ARRIVED_AT), anyMap());
        }

        @Test
        @DisplayName("corrects the existing row rather than adding a second statement")
        void correctsInPlace() {
            OrderDelivery existing = new OrderDelivery(COMPANY, TRIP_ID, STOP_ID, ORDER_ID,
                    DeliveryResult.DELIVERED, ARRIVED_AT, "R. Diaz", null, null, TransportEventSource.OPERATOR,
                    ACTOR, "dispatcher@example.com", null);
            when(deliveryRepository.findByCompanyIdAndTripStopIdAndOrderId(COMPANY, STOP_ID, ORDER_ID))
                    .thenReturn(Optional.of(existing));

            record(new DeliveryResultRequest(DeliveryResult.REJECTED, ARRIVED_AT, "R. Diaz", null,
                    "wrong reference on the note"));

            verify(deliveryRepository).saveAndFlush(existing);
            assertThat(existing.result()).isEqualTo(DeliveryResult.REJECTED);
            assertThat(existing.notes()).isEqualTo("wrong reference on the note");
        }

        @Test
        @DisplayName("writes nothing when a rule refuses the request")
        void writesNothingOnRefusal() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> record(new DeliveryResultRequest(DeliveryResult.DELIVERED, null, null, null,
                            null)));

            verify(deliveryRepository, never()).saveAndFlush(any(OrderDelivery.class));
            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("per product (V75)")
    class PerProduct {

        private final UUID crateLine = UUID.randomUUID();
        private final UUID boxLine = UUID.randomUUID();

        @BeforeEach
        void wholeOrderOnTheTrip() {
            TripOrderAssignment whole = mock(TripOrderAssignment.class);
            when(whole.wholeOrder()).thenReturn(true);
            when(whole.assigned()).thenReturn(new OrderAmounts(new BigDecimal("218"), BigDecimal.ZERO,
                    BigDecimal.ZERO));
            when(assignmentRepository.findByTripIdAndOrderIdAndStatus(TRIP_ID, ORDER_ID, AssignmentStatus.ACTIVE))
                    .thenReturn(Optional.of(whole));
            when(orderReferencePort.lineReferencesOf(Set.of(ORDER_ID), COMPANY)).thenReturn(Map.of(ORDER_ID, List.of(
                    new OrderLineReference(crateLine, ORDER_ID, 1, "PLP-1601", "Gaveta", new BigDecimal("60"), "UN",
                            new BigDecimal("1.9"), null),
                    new OrderLineReference(boxLine, ORDER_ID, 2, "PLP-1623", "Caja", new BigDecimal("40"), "UN",
                            new BigDecimal("2.6"), null))));
            // The demand ceiling: the whole order, so a partial delivery fits under it.
            when(orderPlanningPort.findAllInCompany(Set.of(ORDER_ID), COMPANY)).thenReturn(Map.of(ORDER_ID,
                    new PlannableOrder(ORDER_ID, "ORD-0001", UUID.randomUUID(), DESTINATION_ID, "Customer", null,
                            LocalDate.now(), "NORMAL", null, null, new BigDecimal("218"), BigDecimal.ZERO,
                            BigDecimal.ZERO, null, null, OrderAmounts.NONE)));
        }

        @SuppressWarnings("unchecked")
        private List<DeliveryLineWrite> written() {
            ArgumentCaptor<List<DeliveryLineWrite>> lines = ArgumentCaptor.forClass(List.class);
            verify(deliveryLines).upsert(eq(COMPANY), any(), eq(ORDER_ID), lines.capture(), eq(ACTOR));
            return lines.getValue();
        }

        @Test
        @DisplayName("a partial delivery records which products came back, and derives the refused kilos")
        void partialWritesTheRefusedProducts() {
            OrderDelivery[] saved = new OrderDelivery[1];
            when(deliveryRepository.saveAndFlush(any(OrderDelivery.class))).thenAnswer(call -> {
                saved[0] = call.getArgument(0);
                return saved[0];
            });

            record(new DeliveryResultRequest(DeliveryResult.PARTIAL, ARRIVED_AT, "Bodega", null, "10 rotas", null,
                    List.of(new DeliveryResultRequest.RefusedLineRequest(crateLine, new BigDecimal("10"),
                            "gavetas rotas"))));

            assertThat(written()).extracting(DeliveryLineWrite::orderLineId, DeliveryLineWrite::quantityAttempted,
                    DeliveryLineWrite::quantityDelivered, DeliveryLineWrite::quantityRefused)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(crateLine, new BigDecimal("60"), new BigDecimal("50"),
                                    new BigDecimal("10")),
                            org.assertj.core.groups.Tuple.tuple(boxLine, new BigDecimal("40"), new BigDecimal("40"),
                                    BigDecimal.ZERO));
            assertThat(written().get(0).notes()).isEqualTo("gavetas rotas");
            // 10 x 1.9 kg, without anybody weighing them.
            assertThat(saved[0].quantities().refused().weightKg()).isEqualByComparingTo("19");
        }

        @Test
        @DisplayName("a rejection refuses every product, without being told which")
        void rejectionRefusesEverything() {
            record(new DeliveryResultRequest(DeliveryResult.REJECTED, ARRIVED_AT, null, null, "anuló la compra"));

            assertThat(written()).extracting(DeliveryLineWrite::quantityRefused)
                    .containsExactly(new BigDecimal("60"), new BigDecimal("40"));
        }

        @Test
        @DisplayName("a partial result in kilos only leaves the products on file alone")
        void partialWithoutProductsWritesNoLines() {
            record(new DeliveryResultRequest(DeliveryResult.PARTIAL, ARRIVED_AT, null, null, "faltó",
                    new DeliveryResultRequest.DeliveryQuantitiesRequest(new BigDecimal("218"), BigDecimal.ZERO,
                            BigDecimal.ZERO, new BigDecimal("200"), BigDecimal.ZERO, BigDecimal.ZERO,
                            new BigDecimal("18"), BigDecimal.ZERO, BigDecimal.ZERO)));

            verifyNoInteractions(deliveryLines);
        }

        @Test
        @DisplayName("refuses more of a product than the order has")
        void refusesMoreThanOrdered() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> record(new DeliveryResultRequest(DeliveryResult.PARTIAL, ARRIVED_AT, null, null,
                            "x", null, List.of(new DeliveryResultRequest.RefusedLineRequest(crateLine,
                                    new BigDecimal("61"), null)))))
                    .withMessageContaining("PLP-1601");
            verify(deliveryRepository, never()).saveAndFlush(any(OrderDelivery.class));
        }

        @Test
        @DisplayName("refuses refused products on a share of a split order - its refusal is in kilos")
        void refusesProductsOnASplitShare() {
            TripOrderAssignment share = mock(TripOrderAssignment.class);
            when(share.wholeOrder()).thenReturn(false);
            when(assignmentRepository.findByTripIdAndOrderIdAndStatus(TRIP_ID, ORDER_ID, AssignmentStatus.ACTIVE))
                    .thenReturn(Optional.of(share));

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> record(new DeliveryResultRequest(DeliveryResult.PARTIAL, ARRIVED_AT, null, null,
                            "x", null, List.of(new DeliveryResultRequest.RefusedLineRequest(crateLine, BigDecimal.TEN,
                                    null)))))
                    .withMessageContaining("only part of the order");
        }
    }
}
