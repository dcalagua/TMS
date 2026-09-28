package com.ebim.tms.shared.reference;

import java.util.List;
import java.util.UUID;

/**
 * What an inbound order upsert produced: the TMS identity and number of the order, so the
 * sending system can quote it in a support conversation, plus what happened to it and the
 * status it ended in.
 *
 * @param releaseRefusedBy when {@code markReadyForPlanning} was asked and the order stayed
 *     {@code NOT_READY}, the eligibility reason codes that kept it there (ADR-014 section 8: a
 *     blocking reason, or a warning that needs a person's override reason). Empty otherwise.
 */
public record OrderIntakeResult(UUID id, String orderNumber, String status, IntakeOutcome outcome,
        List<String> releaseRefusedBy) {

    public OrderIntakeResult {
        releaseRefusedBy = releaseRefusedBy == null ? List.of() : List.copyOf(releaseRefusedBy);
    }

    public OrderIntakeResult(UUID id, String orderNumber, String status, IntakeOutcome outcome) {
        this(id, orderNumber, status, outcome, List.of());
    }
}
