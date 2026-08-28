package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The accepted-tender invariant (migration V42) - open debt D2, closed here.
 *
 * <p><b>The problem.</b> A shipment is offered to carriers that do not own the vehicle on it; that
 * is what subcontracting means. Until V42 an acceptance could only be recorded on the tender,
 * because writing it to {@code trip.carrier_id} would have produced a shipment whose carrier and
 * whose vehicle's owner disagreed - and the aggregate had nowhere else to put it. So the shipment
 * itself said nothing about who had agreed to run it.
 *
 * <p><b>The rule.</b> {@code carrierId} goes on meaning the owner of the assigned vehicle.
 * {@code acceptedCarrierId} says who agreed. The two may legitimately disagree for a while, and a
 * shipment in that state <em>may not depart</em>. That is the one thing being asserted here, and it
 * is asserted three times over in the codebase: this aggregate, {@code TripExecutionService}'s
 * readable refusal, and {@code ck_trip_departed_carrier_matches_vehicle} in the database.
 *
 * <p>No database needed for any of it.
 */
class TripAcceptedCarrierTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID OWN_VEHICLE = UUID.randomUUID();
    private static final UUID OWN_CARRIER = UUID.randomUUID();
    private static final UUID OTHER_CARRIER = UUID.randomUUID();
    private static final UUID OTHER_CARRIERS_VEHICLE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID DISPATCHER = UUID.randomUUID();
    private static final LocalDate PLANNING_DATE = LocalDate.of(2026, 8, 20);
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-08-20T08:00:00Z");

    private static Trip ready() {
        Trip trip = new Trip(COMPANY, RUN, PLANNING_DATE, 1, "SH-00000001", OWN_VEHICLE, OWN_CARRIER,
                DEPARTURE, ACTOR);
        trip.confirm(BigDecimal.valueOf(8000), BigDecimal.valueOf(32), 18, ACTOR);
        trip.markReadyForDispatch(OffsetDateTime.parse("2026-08-20T07:30:00Z"), DISPATCHER);
        return trip;
    }

    @Nested
    @DisplayName("when the accepting carrier owns the vehicle")
    class TheOrdinaryCase {

        @Test
        @DisplayName("nothing is pending: a carrier accepting its own truck's work resolves on the spot")
        void ownCarrierAcceptanceResolvesImmediately() {
            Trip trip = ready();

            trip.recordCarrierAcceptance(OWN_CARRIER, ACTOR);

            assertThat(trip.acceptedCarrierId()).isEqualTo(OWN_CARRIER);
            assertThat(trip.awaitsCarrierVehicle()).isFalse();
        }

        @Test
        @DisplayName("and the shipment departs")
        void itDeparts() {
            Trip trip = ready();
            trip.recordCarrierAcceptance(OWN_CARRIER, ACTOR);

            trip.dispatch(OffsetDateTime.parse("2026-08-20T08:12:00Z"), DISPATCHER);

            assertThat(trip.status()).isEqualTo(TripStatus.IN_TRANSIT);
        }

        @Test
        @DisplayName("a shipment nobody tendered elsewhere is not waiting for anything")
        void noAcceptanceIsNotPending() {
            assertThat(ready().awaitsCarrierVehicle()).isFalse();
        }
    }

    @Nested
    @DisplayName("when another carrier accepts")
    class SubcontractedCase {

        @Test
        @DisplayName("the acceptance is recorded and the vehicle is left alone")
        void acceptanceDoesNotTouchTheVehicle() {
            Trip trip = ready();

            trip.recordCarrierAcceptance(OTHER_CARRIER, ACTOR);

            assertThat(trip.acceptedCarrierId()).isEqualTo(OTHER_CARRIER);
            // Neither invented nor cleared. Clearing is impossible - ck_trip_confirmed_is_complete
            // requires a vehicle on every confirmed trip - and choosing one of the accepting
            // carrier's automatically would mean picking among another company's fleet by rules
            // nobody has stated.
            assertThat(trip.vehicleId()).isEqualTo(OWN_VEHICLE);
            assertThat(trip.carrierId()).isEqualTo(OWN_CARRIER);
        }

        @Test
        @DisplayName("the shipment is agreed and not resourced")
        void itIsPending() {
            Trip trip = ready();

            trip.recordCarrierAcceptance(OTHER_CARRIER, ACTOR);

            assertThat(trip.awaitsCarrierVehicle()).isTrue();
        }

        /** The invariant. The whole of D2 comes down to this assertion. */
        @Test
        @DisplayName("and it cannot depart")
        void itCannotDepart() {
            Trip trip = ready();
            trip.recordCarrierAcceptance(OTHER_CARRIER, ACTOR);

            assertThatIllegalStateException()
                    .isThrownBy(() -> trip.dispatch(OffsetDateTime.parse("2026-08-20T08:12:00Z"), DISPATCHER))
                    .withMessageContaining("does not own the vehicle");

            assertThat(trip.status()).isEqualTo(TripStatus.READY_FOR_DISPATCH);
        }

        @Test
        @DisplayName("assigning one of the accepting carrier's vehicles resolves it")
        void assigningTheRightVehicleResolvesIt() {
            Trip trip = ready();
            trip.recordCarrierAcceptance(OTHER_CARRIER, ACTOR);

            trip.assignVehicle(OTHER_CARRIERS_VEHICLE, OTHER_CARRIER, DEPARTURE, ACTOR);

            assertThat(trip.awaitsCarrierVehicle()).isFalse();
            trip.dispatch(OffsetDateTime.parse("2026-08-20T08:12:00Z"), DISPATCHER);
            assertThat(trip.status()).isEqualTo(TripStatus.IN_TRANSIT);
        }

        /**
         * A third carrier's vehicle is no better than the first's. The check is "does the accepting
         * carrier own this truck", not "has the vehicle changed since the acceptance".
         */
        @Test
        @DisplayName("a third carrier's vehicle does not resolve it either")
        void anyOtherVehicleStillBlocks() {
            Trip trip = ready();
            trip.recordCarrierAcceptance(OTHER_CARRIER, ACTOR);

            trip.assignVehicle(UUID.randomUUID(), UUID.randomUUID(), DEPARTURE, ACTOR);

            assertThat(trip.awaitsCarrierVehicle()).isTrue();
        }

        /**
         * Planning is not blocked - only departure is. A subcontracted shipment is edited, costed
         * and re-planned like any other while the truck is being sorted out; refusing those would
         * make the state unusable rather than safe.
         */
        @Test
        @DisplayName("the shipment can still be cancelled while it waits")
        void planningIsNotBlocked() {
            Trip trip = ready();
            trip.recordCarrierAcceptance(OTHER_CARRIER, ACTOR);

            trip.cancel("customer pulled the load", ACTOR);

            assertThat(trip.status()).isEqualTo(TripStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("who is recorded as having touched it")
    class Attribution {

        /**
         * A carrier answering over the integration API is not a person, and {@code requireAppUserId}
         * rejects machines by design (JOB 07). Recording "nobody" over the last human who touched
         * the shipment would lose a fact in order to record the absence of one.
         */
        @Test
        @DisplayName("an integration acceptance does not erase the last person who touched the shipment")
        void machineAcceptanceLeavesUpdatedByAlone() {
            Trip trip = ready();

            trip.recordCarrierAcceptance(OTHER_CARRIER, null);

            assertThat(trip.acceptedCarrierId()).isEqualTo(OTHER_CARRIER);
            assertThat(trip.updatedBy()).isEqualTo(DISPATCHER);
        }
    }
}
