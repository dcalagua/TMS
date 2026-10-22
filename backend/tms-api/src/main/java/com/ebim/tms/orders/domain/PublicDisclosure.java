package com.ebim.tms.orders.domain;

import com.ebim.tms.shared.settings.TrackingPlateDisplay;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * The few transformations that decide how much of a fact an anonymous tracking link may publish
 * (ADR-029 T5). Pure and static: each is a sentence of the ADR and a unit test.
 */
public final class PublicDisclosure {

    /** Characters of a plate left readable when it is masked. */
    static final int PLATE_VISIBLE = 3;

    /** The character that replaces a hidden one. */
    static final char MASK = '•';

    /** Width of the arrival window drawn around a point estimate (ADR-011 gives a point). */
    static final Duration ESTIMATE_WINDOW = Duration.ofHours(1);

    private PublicDisclosure() {
    }

    /**
     * The plate as the company chose to publish it. {@code MASKED} keeps the last three letters or
     * digits and the separators ({@code ABC-123} becomes {@code •••-123}); a plate with three or fewer
     * of them is masked whole, because "the last three" would then be all of it.
     */
    public static String plate(String licensePlate, TrackingPlateDisplay display) {
        if (licensePlate == null || licensePlate.isBlank() || display != TrackingPlateDisplay.MASKED) {
            return null;
        }
        String plate = licensePlate.trim();
        long significant = plate.chars().filter(Character::isLetterOrDigit).count();
        long keepFrom = significant <= PLATE_VISIBLE ? Long.MAX_VALUE : significant - PLATE_VISIBLE;
        StringBuilder out = new StringBuilder(plate.length());
        long seen = 0;
        for (char c : plate.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(seen >= keepFrom ? c : MASK);
                seen++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Who received the goods, as a first name and an initial: {@code "Juan Pérez García"} becomes
     * {@code "Juan P."}. Enough for the customer to recognise their own person at the dock, not a full
     * name to hand to anyone holding the message. The identity document is never published.
     */
    public static String receiver(String receiverName) {
        if (receiverName == null || receiverName.isBlank()) {
            return null;
        }
        String[] parts = receiverName.trim().split("\\s+");
        String first = truncate(parts[0], 40);
        if (parts.length == 1) {
            return first;
        }
        return first + " " + parts[1].codePoints().limit(1)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString().toUpperCase(java.util.Locale.ROOT) + ".";
    }

    /** The arrival window a customer is shown, or null. */
    public record ArrivalWindow(OffsetDateTime from, OffsetDateTime to, String basis) {
        public static final String ESTIMATE = "ESTIMATE";
        public static final String SERVICE_WINDOW = "SERVICE_WINDOW";
    }

    /**
     * The window around an estimated arrival: one hour, starting half an hour before the estimate and
     * rounded down to the quarter. A point estimate read as a promise is exactly what a customer would
     * hold the carrier to; a window is what it is. Without an estimate, the stop's own service window on
     * its planning day, in the company's zone. Without either, nothing - never an invented time.
     */
    public static ArrivalWindow arrivalWindow(OffsetDateTime estimate, LocalDate planningDate, LocalTime windowStart,
            LocalTime windowEnd, ZoneId zone) {
        if (estimate != null) {
            OffsetDateTime centred = estimate.minus(ESTIMATE_WINDOW.dividedBy(2));
            OffsetDateTime from = centred.truncatedTo(ChronoUnit.HOURS)
                    .plusMinutes((centred.getMinute() / 15) * 15L);
            return new ArrivalWindow(from, from.plus(ESTIMATE_WINDOW), ArrivalWindow.ESTIMATE);
        }
        if (planningDate != null && windowStart != null && windowEnd != null && windowEnd.isAfter(windowStart)) {
            return new ArrivalWindow(planningDate.atTime(windowStart).atZone(zone).toOffsetDateTime(),
                    planningDate.atTime(windowEnd).atZone(zone).toOffsetDateTime(), ArrivalWindow.SERVICE_WINDOW);
        }
        return null;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
