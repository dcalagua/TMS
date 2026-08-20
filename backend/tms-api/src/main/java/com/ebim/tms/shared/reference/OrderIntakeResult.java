package com.ebim.tms.shared.reference;

import java.util.UUID;

/**
 * What an inbound order upsert produced: the TMS identity and number of the order, so the
 * sending system can quote it in a support conversation, plus what happened to it and the
 * status it ended in.
 */
public record OrderIntakeResult(UUID id, String orderNumber, String status, IntakeOutcome outcome) {
}
