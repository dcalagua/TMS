package com.ebim.tms.iam.lifecycle.domain;

import com.ebim.tms.iam.lifecycle.domain.LifecycleRejection.Code;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The body of {@code PUT /tenants/{controlPlaneTenantId}/lifecycle} ({@code lifecycle.v1} section 3),
 * validated.
 *
 * <p>Strict on purpose: an unknown field, a wrong type, a reason with {@code ACTIVE} or none with
 * {@code SUSPENDED} is {@code 400 INVALID_LIFECYCLE}. The document carries no personal data and no
 * amount, and none is accepted.
 *
 * @param reason {@code null} exactly when {@code state} is {@code ACTIVE}
 */
public record LifecycleCommand(
        UUID controlPlaneTenantId,
        String productCode,
        String environment,
        long version,
        LifecycleState state,
        LifecycleState.Reason reason,
        OffsetDateTime effectiveAt,
        String idempotencyKey,
        String correlationId) {

    public static final String CONTRACT = "lifecycle.v1";
    /** Free-text audit fields: stored, never interpreted. */
    public static final int MAX_TEXT = 200;

    private static final Set<String> FIELDS = Set.of("contract", "controlPlaneTenantId", "productCode",
            "environment", "version", "state", "reason", "effectiveAt", "idempotencyKey", "correlationId");
    private static final Set<String> REQUIRED = Set.of("contract", "controlPlaneTenantId", "productCode",
            "environment", "version", "state");

    /** Shape and types only; product, environment and tenant are checked against the receiver by the caller. */
    public static LifecycleCommand parse(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw invalid("The body is a JSON object");
        }
        for (String name : document.propertyNames()) {
            if (!FIELDS.contains(name)) {
                throw invalid("Unknown field");
            }
        }
        for (String name : REQUIRED) {
            if (!document.has(name) || document.get(name).isNull()) {
                throw invalid("A required field is missing");
            }
        }
        if (!CONTRACT.equals(string(document, "contract"))) {
            throw invalid("contract must be " + CONTRACT);
        }

        UUID tenant = uuid(string(document, "controlPlaneTenantId"))
                .orElseThrow(() -> invalid("controlPlaneTenantId is a UUID"));
        String productCode = nonBlank(string(document, "productCode"), "productCode");
        String environment = nonBlank(string(document, "environment"), "environment");

        JsonNode versionNode = document.get("version");
        if (!versionNode.isIntegralNumber() || !versionNode.canConvertToLong() || versionNode.longValue() < 1) {
            throw invalid("version is an integer >= 1");
        }

        LifecycleState state = enumValue(LifecycleState.class, string(document, "state"), "state");
        JsonNode reasonNode = document.get("reason");
        LifecycleState.Reason reason = reasonNode == null || reasonNode.isNull()
                ? null
                : enumValue(LifecycleState.Reason.class, string(document, "reason"), "reason");
        if (state == LifecycleState.SUSPENDED && reason == null) {
            throw invalid("SUSPENDED needs a reason");
        }
        if (state == LifecycleState.ACTIVE && reason != null) {
            throw invalid("ACTIVE carries no reason");
        }

        OffsetDateTime effectiveAt = null;
        String effective = optionalString(document, "effectiveAt");
        if (effective != null) {
            try {
                effectiveAt = OffsetDateTime.parse(effective);
            } catch (DateTimeParseException notIso) {
                throw invalid("effectiveAt is ISO-8601 with an offset");
            }
        }

        return new LifecycleCommand(tenant, productCode, environment, versionNode.longValue(), state, reason,
                effectiveAt, optionalString(document, "idempotencyKey"), optionalString(document, "correlationId"));
    }

    private static String string(JsonNode document, String field) {
        JsonNode value = document.get(field);
        if (value == null || !value.isString()) {
            throw invalid(field + " is a string");
        }
        return value.stringValue();
    }

    /** Absent or null is null; anything else must be a string of at most {@link #MAX_TEXT} characters. */
    private static String optionalString(JsonNode document, String field) {
        JsonNode value = document.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString() || value.stringValue().length() > MAX_TEXT) {
            throw invalid(field + " is a string of at most " + MAX_TEXT + " characters");
        }
        return value.stringValue();
    }

    private static String nonBlank(String value, String field) {
        if (value.isBlank() || value.length() > MAX_TEXT) {
            throw invalid(field + " is a non-empty string");
        }
        return value;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unknown) {
            throw invalid(field + " has an unknown value");
        }
    }

    /** A canonical UUID: what MasterAdmin sends, and the only spelling that can match the path. */
    public static Optional<UUID> uuid(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(text);
            return id.toString().equalsIgnoreCase(text) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private static LifecycleRejection invalid(String message) {
        return new LifecycleRejection(Code.INVALID_LIFECYCLE, message);
    }
}
