package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The field rules of a delivery result, and the entity that asserts them (migration V28).
 *
 * <p>Tested here as domain facts rather than through the service: "a delivery that claims goods
 * changed hands says when" is true of the model, not of an endpoint, and the same three rules are
 * enforced in three places - the service with a readable message, {@link OrderDelivery} as a last
 * line of defense, and the database's CHECK constraints under both.
 */
class DeliveryResultTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP = UUID.randomUUID();
    private static final UUID STOP = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final OffsetDateTime AT = OffsetDateTime.now().minusHours(1);

    private static OrderDelivery delivery(DeliveryResult result, OffsetDateTime deliveredAt, String receiverName,
            String notes) {
        return new OrderDelivery(COMPANY, TRIP, STOP, ORDER, result, deliveredAt, receiverName, null, notes,
                TransportEventSource.OPERATOR, ACTOR, "dispatcher@example.com", null);
    }

    @Nested
    @DisplayName("the rules of each result")
    class Rules {

        @Test
        @DisplayName("a handover carries the moment it happened; a refusal need not")
        void handoverRequiresATime() {
            assertThat(DeliveryResult.DELIVERED.requiresDeliveredAt()).isTrue();
            assertThat(DeliveryResult.PARTIAL.requiresDeliveredAt()).isTrue();
            assertThat(DeliveryResult.REJECTED.requiresDeliveredAt()).isFalse();
            assertThat(DeliveryResult.FAILED.requiresDeliveredAt()).isFalse();
        }

        @Test
        @DisplayName("nothing attempted means no time at all, not merely an optional one")
        void notAttemptedForbidsATime() {
            assertThat(DeliveryResult.NOT_ATTEMPTED.forbidsDeliveredAt()).isTrue();
            assertThat(DeliveryResult.FAILED.forbidsDeliveredAt()).isFalse();
        }

        @Test
        @DisplayName("only a result reached with somebody present may name a receiver")
        void receiverNeedsSomebodyPresent() {
            assertThat(DeliveryResult.DELIVERED.allowsReceiver()).isTrue();
            assertThat(DeliveryResult.PARTIAL.allowsReceiver()).isTrue();
            assertThat(DeliveryResult.REJECTED.allowsReceiver()).isTrue();
            assertThat(DeliveryResult.FAILED.allowsReceiver()).isFalse();
            assertThat(DeliveryResult.NOT_ATTEMPTED.allowsReceiver()).isFalse();
        }

        @Test
        @DisplayName("anything short of a clean delivery is explained, except one already explained by its stop")
        void shortfallsAreExplained() {
            assertThat(DeliveryResult.PARTIAL.requiresNotes()).isTrue();
            assertThat(DeliveryResult.REJECTED.requiresNotes()).isTrue();
            assertThat(DeliveryResult.FAILED.requiresNotes()).isTrue();
            assertThat(DeliveryResult.DELIVERED.requiresNotes()).isFalse();
            // The stop it belongs to was skipped or failed, and V27 already requires a typed
            // exception for that - a second sentence here would be the same fact twice.
            assertThat(DeliveryResult.NOT_ATTEMPTED.requiresNotes()).isFalse();
        }

        @Test
        @DisplayName("only DELIVERED means the customer got everything")
        void onlyDeliveredIsComplete() {
            assertThat(DeliveryResult.DELIVERED.isComplete()).isTrue();
            assertThat(DeliveryResult.PARTIAL.isComplete()).isFalse();
        }
    }

    @Nested
    @DisplayName("the entity asserting them")
    class Entity {

        @Test
        @DisplayName("refuses a delivery that does not say when the goods changed hands")
        void refusesAHandoverWithNoTime() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> delivery(DeliveryResult.DELIVERED, null, "R. Diaz", null))
                    .withMessageContaining("when");
        }

        @Test
        @DisplayName("refuses a delivery time on something that was never attempted")
        void refusesATimeOnNotAttempted() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> delivery(DeliveryResult.NOT_ATTEMPTED, AT, null, null))
                    .withMessageContaining("cannot carry a delivery time");
        }

        @Test
        @DisplayName("refuses a receiver on a result where nobody received anything")
        void refusesAReceiverOnAFailure() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> delivery(DeliveryResult.FAILED, AT, "R. Diaz", "nobody at the address"))
                    .withMessageContaining("receiver");
        }

        @Test
        @DisplayName("refuses a rejection with nothing said about why")
        void refusesAnUnexplainedRejection() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> delivery(DeliveryResult.REJECTED, AT, "R. Diaz", "   "))
                    .withMessageContaining("explained");
        }

        @Test
        @DisplayName("accepts the ordinary delivery, and reports who recorded it")
        void acceptsAnOrdinaryDelivery() {
            OrderDelivery delivery = delivery(DeliveryResult.DELIVERED, AT, "R. Diaz", null);

            assertThat(delivery.result()).isEqualTo(DeliveryResult.DELIVERED);
            assertThat(delivery.deliveredAt()).isEqualTo(AT);
            assertThat(delivery.receiverName()).isEqualTo("R. Diaz");
            assertThat(delivery.actorDisplayName()).isEqualTo("dispatcher@example.com");
            assertThat(delivery.source()).isEqualTo(TransportEventSource.OPERATOR);
        }

        @Test
        @DisplayName("a correction replaces the whole statement, including who is making it")
        void correctionReplacesEverything() {
            OrderDelivery delivery = delivery(DeliveryResult.DELIVERED, AT, "R. Diaz", null);

            delivery.record(DeliveryResult.PARTIAL, AT, null, null, "one pallet short",
                    TransportEventSource.OPERATOR, ACTOR, "supervisor@example.com", null);

            assertThat(delivery.result()).isEqualTo(DeliveryResult.PARTIAL);
            // Cleared, not kept: a PUT carries the whole state, so an omitted receiver means there
            // is none - which is the only way a name typed by mistake can be removed.
            assertThat(delivery.receiverName()).isNull();
            assertThat(delivery.notes()).isEqualTo("one pallet short");
            assertThat(delivery.actorDisplayName()).isEqualTo("supervisor@example.com");
        }

        @Test
        @DisplayName("every result has a legal shape that the entity accepts")
        void everyResultIsRecordable() {
            for (DeliveryResult result : DeliveryResult.values()) {
                OffsetDateTime deliveredAt = result.forbidsDeliveredAt() ? null : AT;
                String notes = result.requiresNotes() ? "explained" : null;
                String receiver = result.allowsReceiver() ? "R. Diaz" : null;

                assertThat(delivery(result, deliveredAt, receiver, notes).result()).isEqualTo(result);
            }
        }
    }
}
