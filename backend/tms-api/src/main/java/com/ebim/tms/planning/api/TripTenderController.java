package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.TenderRequest;
import com.ebim.tms.planning.application.TenderResponseRequest;
import com.ebim.tms.planning.application.TenderWithdrawRequest;
import com.ebim.tms.planning.application.TripTenderService;
import com.ebim.tms.planning.application.TripTenderView;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Offering one shipment to its carrier, and recording what they said (migration V31).
 *
 * <p>A controller of its own rather than six more methods on {@link TripController}, for the reason
 * {@code TripCostController} is separate: the URL space follows the authority that gates it, so a
 * reader can tell from the path that these need {@code planning.tender:*} and not
 * {@code planning.trip:manage}. It stays under {@code /planning} because tendering <em>is</em> owned
 * by {@code planning} - unlike costing, which is a module of its own.
 *
 * <p>Every mutation returns the shipment's whole tender history, newest attempt first, rather than
 * the one attempt it touched. That is what the card renders, it is one round trip, and it means a
 * planner who withdraws attempt 2 immediately sees attempt 1's rejection above it - which is
 * usually why they are looking.
 *
 * <p><b>No {@code version} anywhere.</b> A tender is a short-lived child of a trip, serialised by
 * the trip's row lock, and its state machine already refuses everything a stale client could try:
 * editing a sent offer, answering a withdrawn one, opening a second live one. The same reasoning
 * the assignment endpoints use - see {@code PlanningActionRequest}.
 *
 * <p>The carrier's own way in is not here at all. It is
 * {@code POST /integration/v1/tenders/{shipmentNumber}/response}, authenticated with a credential
 * bound to that carrier, and it shares this module's service so the two paths cannot diverge on a
 * single rule.
 */
@RestController
@RequestMapping("${tms.api.base-path}/planning/trips/{tripId}/tenders")
@Tag(name = "Carrier tendering", description = "Offering a shipment to its carrier and recording the answer")
public class TripTenderController {

    private final TripTenderService tenderService;

    public TripTenderController(TripTenderService tenderService) {
        this.tenderService = tenderService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('planning.tender:read')")
    @Operation(summary = "Every attempt to place this shipment with a carrier, newest first",
            description = "An empty list for a shipment nobody has tendered, never a 404 - that is a "
                    + "state, not a missing resource. A sent offer past its deadline reports EXPIRED "
                    + "even before TMS has written the lapse down.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<TripTenderView> list(CompanyScope scope, @PathVariable UUID tripId) {
        return tenderService.list(scope, tripId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('planning.tender:manage')")
    @Operation(summary = "Prepare an offer for this shipment's carrier",
            description = "Creates it as a DRAFT: nothing is published and the carrier is told "
                    + "nothing until it is sent. Refused when the shipment is not confirmed, when it "
                    + "has already been accepted, or when an offer is already open on it.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<TripTenderView> create(
            CompanyScope scope, @PathVariable UUID tripId, @Valid @RequestBody TenderRequest request) {
        return tenderService.create(scope, tripId, request);
    }

    @PutMapping("/{tenderId}")
    @PreAuthorize("hasAuthority('planning.tender:manage')")
    @Operation(summary = "Change the terms of an offer that has not been sent",
            description = "Only while it is a DRAFT. Once a carrier has been shown an amount and a "
                    + "deadline, correcting them means withdrawing the offer and sending another - "
                    + "which is a second attempt, and is recorded as one.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<TripTenderView> updateTerms(CompanyScope scope, @PathVariable UUID tripId,
            @PathVariable UUID tenderId, @Valid @RequestBody TenderRequest request) {
        return tenderService.updateTerms(scope, tripId, tenderId, request);
    }

    @PostMapping("/{tenderId}/send")
    @PreAuthorize("hasAuthority('planning.tender:manage')")
    @Operation(summary = "Send the offer to the carrier",
            description = "Freezes the terms and publishes TENDER_SENT to the outbox, which is how an "
                    + "integrated carrier learns there is something to answer. Sending an offer that "
                    + "is already sent returns it unchanged.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<TripTenderView> send(CompanyScope scope, @PathVariable UUID tripId, @PathVariable UUID tenderId) {
        return tenderService.send(scope, tripId, tenderId);
    }

    @PostMapping("/{tenderId}/accept")
    @PreAuthorize("hasAuthority('planning.tender:manage')")
    @Operation(summary = "Record that the carrier accepted",
            description = "For the answer that arrived by phone or mail; the response is stamped "
                    + "OPERATOR so it is never mistaken for the carrier's own signature. At most one "
                    + "accepted tender exists per shipment, ever.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<TripTenderView> accept(CompanyScope scope, @PathVariable UUID tripId, @PathVariable UUID tenderId,
            @Valid @RequestBody TenderResponseRequest request) {
        return tenderService.accept(scope, tripId, tenderId, request);
    }

    @PostMapping("/{tenderId}/reject")
    @PreAuthorize("hasAuthority('planning.tender:manage')")
    @Operation(summary = "Record that the carrier declined, and why",
            description = "notes are required: the reason is what the planner needs in order to decide "
                    + "what to do next. The rejection is kept - a later attempt does not erase it.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<TripTenderView> reject(CompanyScope scope, @PathVariable UUID tripId, @PathVariable UUID tenderId,
            @Valid @RequestBody TenderResponseRequest request) {
        return tenderService.reject(scope, tripId, tenderId, request);
    }

    @PostMapping("/{tenderId}/withdraw")
    @PreAuthorize("hasAuthority('planning.tender:manage')")
    @Operation(summary = "Pull the offer back, with a reason",
            description = "Publishes TENDER_CANCELLED when the offer had actually gone out. Withdrawing "
                    + "an offer that was still a draft tells nobody anything, because nobody was told "
                    + "about it in the first place.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<TripTenderView> withdraw(CompanyScope scope, @PathVariable UUID tripId, @PathVariable UUID tenderId,
            @Valid @RequestBody TenderWithdrawRequest request) {
        return tenderService.withdraw(scope, tripId, tenderId, request);
    }
}
