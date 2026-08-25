package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.IntegrationExecution;
import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.integration.application.IntegrationRequestExecutor;
import com.ebim.tms.integration.application.IntegrationTenderService;
import com.ebim.tms.integration.application.TenderOfferV1;
import com.ebim.tms.integration.application.TenderResponseEnvelope;
import com.ebim.tms.integration.application.TenderResponseV1;
import com.ebim.tms.integration.domain.IntegrationOperation;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A carrier's own tenders, API version 1 (migration V31). The answer to "how does the carrier reply
 * without a portal": with the credential they already hold, against two endpoints.
 *
 * <p><b>This is the only place in TMS where the authenticated party is not the tenant.</b> Every
 * other integration endpoint is a system acting <em>for</em> the company that issued the key; here
 * the key belongs to a counterparty, and everything about the shape follows from that:
 *
 * <ul>
 *   <li>the carrier comes from {@code integration_client.carrier_id}, never from the payload or a
 *       header, so a credential can only ever reach its own offers;</li>
 *   <li>{@code integration.tender:respond} is deliberately not {@code integration.shipment:read},
 *       which would expose every confirmed shipment of the company. A carrier learns about the
 *       shipments it was offered and no others;</li>
 *   <li>a shipment this carrier has no tender on answers 404 with the same sentence a shipment that
 *       does not exist gets, so the endpoint cannot be used to enumerate the shipper's business;</li>
 *   <li>{@link TenderOfferV1} carries no vehicle, driver, order or customer - see
 *       {@code CarrierTenderOffer} for the field-by-field reasoning.</li>
 * </ul>
 *
 * <p>The read is not recorded in the integration inbox, for the reason
 * {@link IntegrationShipmentController} gives: there is no payload to fingerprint and no idempotency
 * key to honour. The write is, because what a carrier accepted and when is exactly the delivery a
 * support engineer will be asked to reconstruct.
 */
@RestController
@RequestMapping(IntegrationApiPaths.V1 + "/tenders")
@Tag(name = "Integration v1 - Tenders",
        description = "A carrier's own offers and their answer to them. Authenticated with an "
                + "integration credential bound to that carrier, never a user token.")
@SecurityRequirement(name = OpenApiConfig.INTEGRATION_SCHEME)
public class IntegrationTenderController {

    private final IntegrationTenderService tenderService;
    private final IntegrationRequestExecutor executor;

    public IntegrationTenderController(IntegrationTenderService tenderService,
            IntegrationRequestExecutor executor) {
        this.tenderService = tenderService;
        this.executor = executor;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('integration.tender:respond')")
    @Operation(summary = "The offers this carrier is holding",
            description = "Only the ones still answerable, oldest first. An offer whose deadline has "
                    + "passed is absent rather than listed as expired: this is a work queue, and an "
                    + "offer that can no longer be accepted is not work. Not paginated - the result "
                    + "is bounded by what one carrier has outstanding right now.")
    public List<TenderOfferV1> openOffers(
            IntegrationPrincipal principal,
            @Parameter(description = "Optional. Fetch the offer on one shipment instead of the whole queue.")
            @RequestParam(required = false) String shipmentNumber) {
        return tenderService.openOffers(principal, shipmentNumber);
    }

    @PostMapping("/{shipmentNumber:SH-\\d+}/response")
    @PreAuthorize("hasAuthority('integration.tender:respond')")
    @Operation(summary = "Accept or reject the offer on one shipment",
            description = "reason is required to reject. Re-sending the same decision returns the "
                    + "answer already recorded, so an at-least-once sender is safe without a key; "
                    + "sending the opposite decision is refused with 409, because reversing a "
                    + "commitment is not a retry. Answering after the deadline is 409 as well.")
    @Parameter(name = ApiHeaders.IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
            description = "Optional. Repeating it with the same payload replays the first response; "
                    + "repeating it with a different payload is refused with 409. Not needed for "
                    + "ordinary retries - re-sending the same decision is already a no-op.")
    public ResponseEntity<TenderOfferV1> respond(
            IntegrationPrincipal principal,
            @PathVariable String shipmentNumber,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            // No @Valid, for the reason IntegrationOrderController documents: validation runs inside
            // IntegrationRequestExecutor so a constraint failure is recorded in the inbox like every
            // other outcome, instead of being the one delivery that leaves no trace.
            @RequestBody TenderResponseV1 request) {

        // The shipment travels in the path but has to be inside the fingerprinted payload, or an
        // Idempotency-Key reused across two shipments with the same decision would replay the first
        // shipment's answer for the second. TenderResponseEnvelope says why at length.
        TenderResponseEnvelope delivery = new TenderResponseEnvelope(shipmentNumber, request);
        IntegrationExecution<TenderOfferV1> execution = executor.execute(principal,
                IntegrationOperation.TENDER_RESPONSE, idempotencyKey, delivery, TenderOfferV1.class,
                () -> tenderService.respond(principal, delivery));
        return IntegrationResponses.of(execution);
    }
}
