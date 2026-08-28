package com.ebim.tms.shared.reference;

import java.time.OffsetDateTime;

/**
 * Why a vehicle or a driver cannot work, and until when (migration V42).
 *
 * @param resource what is blocked, in the words a dispatcher uses - "vehicle" or "driver" - because
 *                 this string is read out in a refusal and not switched on
 * @param reason   the block's reason, as stored
 * @param endsAt   when the block lifts. Exclusive, matching {@code tstzrange(starts_at, ends_at)}
 */
public record ResourceBlock(String resource, String reason, OffsetDateTime endsAt) {
}
