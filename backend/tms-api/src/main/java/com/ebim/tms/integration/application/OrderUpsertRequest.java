package com.ebim.tms.integration.application;

import com.ebim.tms.shared.reference.OrderIntakeCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * One transport order as the inbound API v1 receives it.
 *
 * <p>{@code externalSource} and {@code externalReference} are mandatory, unlike on the UI's
 * {@code OrderRequest} where they are optional. They are the pair that makes a redelivery
 * recognisable, and an integration that cannot be replayed safely is not an integration - so the
 * API refuses to accept an order it could not identify a second time, rather than accepting it and
 * duplicating it on the first retry.
 *
 * <p>Origin and destination are {@code code}s. A sending system knows its own store and warehouse
 * codes; it has no reason to hold TMS uuids, and requiring them would force every partner to run a
 * synchronisation step before they could send a single order.
 *
 * <p>There is no {@code totals} field and there never will be: the figures planning reads are
 * derived by {@code OrderTotals} from the declarations and the lines. A payload that could state
 * the effective totals directly would make "totals are never trusted from outside" a comment.
 */
public record OrderUpsertRequest(
        @NotBlank @Size(max = 64) String externalSource,
        @NotBlank @Size(max = 128) String externalReference,
        @NotBlank @Size(max = 32) String originCode,
        @NotBlank @Size(max = 32) String destinationCode,
        @Size(max = 200) String customerName,
        @Size(max = 100) String customerReference,
        @NotNull LocalDate serviceDate,
        @NotBlank @Size(max = 20) String priority,
        LocalTime requestedWindowStart,
        LocalTime requestedWindowEnd,
        BigDecimal declaredWeightKg,
        BigDecimal declaredVolumeM3,
        BigDecimal declaredPallets,
        List<@Valid @NotNull LineRequest> lines,
        Boolean markReadyForPlanning) {

    public OrderUpsertRequest {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One requested line. Optional in full: a header-only order with declared totals is valid. */
    public record LineRequest(
            @NotBlank @Size(max = 64) String materialCode,
            @NotBlank @Size(max = 200) String materialDescription,
            @NotNull BigDecimal quantity,
            @NotBlank @Size(max = 16) String uom,
            BigDecimal unitWeightKg,
            BigDecimal unitVolumeM3,
            BigDecimal palletQuantity) {

        OrderIntakeCommand.Line toCommandLine() {
            return new OrderIntakeCommand.Line(materialCode, materialDescription, quantity, uom, unitWeightKg,
                    unitVolumeM3, palletQuantity);
        }
    }

    public OrderIntakeCommand toCommand() {
        return new OrderIntakeCommand(externalSource, externalReference, originCode, destinationCode, customerName,
                customerReference, serviceDate, priority, requestedWindowStart, requestedWindowEnd, declaredWeightKg,
                declaredVolumeM3, declaredPallets, lines.stream().map(LineRequest::toCommandLine).toList(),
                markReadyForPlanning != null && markReadyForPlanning);
    }
}
