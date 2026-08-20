package com.ebim.tms.shared.reference;

import java.util.UUID;

/**
 * What an inbound location upsert produced: the TMS identity of the row, so the sending system
 * can store it, and what happened to it.
 */
public record LocationIntakeResult(UUID id, String code, IntakeOutcome outcome) {
}
