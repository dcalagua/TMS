package com.ebim.tms.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.integration.domain.IntegrationScope;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.reference.CarrierTenderOffer;
import com.ebim.tms.shared.reference.CarrierTenderPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wire contract of a carrier's answer, against a stub port - no Spring, no database, no Docker.
 *
 * <p>The case under test is the one that made {@code attempt} exist: a carrier is offered the same
 * shipment twice, and a late redelivery of the first answer must not be applied to the second offer.
 */
class IntegrationTenderServiceTest {

    private static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-0000000000c0");
    private static final UUID CARRIER = UUID.fromString("00000000-0000-0000-0000-0000000000ca");
    private static final String SHIPMENT = "SH-00000142";

    /**
     * A shipment offered to this carrier as attempt 1, refused, and offered again as attempt 3. The
     * redelivered attempt-1 rejection is refused rather than applied to the live attempt-3 offer.
     */
    @Test
    @DisplayName("a redelivered answer naming an older attempt is refused, not applied to the new offer")
    void staleAttemptIsRefused() {
        StubPort port = new StubPort(List.of(offer(3)));
        IntegrationTenderService service = new IntegrationTenderService(port);

        assertThatThrownBy(() -> service.respond(principal(),
                new TenderResponseEnvelope(SHIPMENT, new TenderResponseV1("REJECTED", "No 12t on the 24th", 1))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("attempt 1")
                .hasMessageContaining("attempt 3");

        // The refusal happened before planning was told anything: nothing was written.
        assertThat(port.responded).isEmpty();
    }

    @Test
    @DisplayName("an answer naming the outstanding attempt goes through")
    void matchingAttemptIsAccepted() {
        StubPort port = new StubPort(List.of(offer(3)));
        IntegrationTenderService service = new IntegrationTenderService(port);

        IntegrationOutcome<TenderOfferV1> outcome = service.respond(principal(),
                new TenderResponseEnvelope(SHIPMENT, new TenderResponseV1("REJECTED", "Still nothing", 3)));

        assertThat(outcome.httpStatus()).isEqualTo(200);
        assertThat(outcome.externalReference()).isEqualTo(SHIPMENT);
        assertThat(port.responded).containsExactly("REJECTED");
    }

    /**
     * The genuine retry this endpoint was built to absorb. The tender has already been answered, so
     * nothing is outstanding - and refusing here would break the at-least-once sender rather than
     * protect it. {@code planning} replays the recorded answer; this class must stay out of the way.
     */
    @Test
    @DisplayName("an answer with no offer outstanding is passed through, so a genuine retry still replays")
    void answeredOfferIsNotRefusedHere() {
        StubPort port = new StubPort(List.of());
        IntegrationTenderService service = new IntegrationTenderService(port);

        assertThatCode(() -> service.respond(principal(),
                new TenderResponseEnvelope(SHIPMENT, new TenderResponseV1("REJECTED", "As before", 1))))
                .doesNotThrowAnyException();
        assertThat(port.responded).containsExactly("REJECTED");
    }

    /**
     * An integration written against V31 sends no attempt. It keeps working, and it keeps the risk:
     * the check can only refuse what it was given the means to recognise.
     */
    @Test
    @DisplayName("an answer without an attempt is accepted unchanged, as V31 clients send it")
    void absentAttemptIsNotChecked() {
        StubPort port = new StubPort(List.of(offer(3)));
        IntegrationTenderService service = new IntegrationTenderService(port);

        service.respond(principal(), new TenderResponseEnvelope(SHIPMENT, new TenderResponseV1("ACCEPTED", null)));

        assertThat(port.openOffersCalls).isZero();
        assertThat(port.responded).containsExactly("ACCEPTED");
    }

    @Test
    @DisplayName("a credential with no carrier bound to it is refused before anything is read")
    void credentialWithoutCarrier() {
        StubPort port = new StubPort(List.of(offer(1)));
        IntegrationTenderService service = new IntegrationTenderService(port);
        IntegrationPrincipal noCarrier = new IntegrationPrincipal(UUID.randomUUID(), "acme", "Acme",
                scope(), null, Set.of(IntegrationScope.TENDER_RESPOND));

        assertThatThrownBy(() -> service.respond(noCarrier,
                new TenderResponseEnvelope(SHIPMENT, new TenderResponseV1("ACCEPTED", null, 1))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not bound to a carrier");
        assertThat(port.openOffersCalls).isZero();
    }

    // -----------------------------------------------------------------------------------------

    private static IntegrationPrincipal principal() {
        return new IntegrationPrincipal(UUID.randomUUID(), "acme", "Acme", scope(), CARRIER,
                Set.of(IntegrationScope.TENDER_RESPOND));
    }

    private static CompanyScope scope() {
        return new CompanyScope(COMPANY, "C1", "Company One", "UTC", UUID.randomUUID(), "O1", "Org One", Set.of());
    }

    private static CarrierTenderOffer offer(int attempt) {
        OffsetDateTime now = OffsetDateTime.of(2026, 3, 1, 8, 0, 0, 0, ZoneOffset.UTC);
        return new CarrierTenderOffer(SHIPMENT, attempt, "SENT", LocalDate.of(2026, 3, 24), now.plusDays(23),
                "DEP-1", "Depot One", 4, new BigDecimal("450.00"), "EUR", null, now, now.plusDays(1), null, null);
    }

    /** The whole of {@code planning}, for this test's purposes. */
    private static final class StubPort implements CarrierTenderPort {

        private final List<CarrierTenderOffer> open;
        private final List<String> responded = new ArrayList<>();
        private int openOffersCalls;

        private StubPort(List<CarrierTenderOffer> open) {
            this.open = open;
        }

        @Override
        public List<CarrierTenderOffer> findOpenOffers(CompanyScope scope, UUID carrierId, String shipmentNumber) {
            openOffersCalls++;
            assertThat(carrierId).isEqualTo(CARRIER);
            assertThat(scope.companyId()).isEqualTo(COMPANY);
            return open;
        }

        @Override
        public CarrierTenderOffer respond(CompanyScope scope, UUID carrierId, String shipmentNumber,
                boolean accepted, String notes, UUID integrationClientId) {
            responded.add(accepted ? "ACCEPTED" : "REJECTED");
            return offer(open.isEmpty() ? 1 : open.getFirst().attempt());
        }
    }
}
