package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * The one way the integration module writes a material profile without depending on
 * {@code masterdata} (ADR-022 section 1, {@code WAREHOUSE_EXECUTION_V1.md} section 7.1). Implemented
 * by {@code masterdata.application.MaterialProfileIntakeService}, which opens its own transaction
 * per call so a batch stays independent per item.
 */
public interface MaterialProfileIntakePort {

    /**
     * The fields whose absence keeps the stored value and whose explicit {@code null} clears it,
     * by their API name. {@code sourceSystem} and {@code sourceUpdatedAt} are required on every
     * write and are not among them.
     */
    Set<String> OPTIONAL_FIELDS = Set.of("description", "lengthM", "widthM", "heightM", "unitWeightKg",
            "unitVolumeM3", "stackMode", "stackMaxQuantity", "nestIncrementM", "maxTopLoadKg", "orientationLocked");

    Result upsert(CompanyScope scope, Command command);

    /** What one write did to the stored copy. */
    enum Outcome {
        CREATED,
        UPDATED,
        /** The stored copy already says exactly this. */
        UNCHANGED,
        /** {@code sourceUpdatedAt} is older than the stored one: nothing applied. */
        STALE
    }

    /**
     * One profile as the warehouse sent it.
     *
     * @param fieldsSent the optional fields the payload named, by their API name. An absent field
     *     keeps the stored value; a field named with {@code null} clears it. On a first write the
     *     distinction does not exist and absent is empty
     * @param stackMode the code as sent; validated by the implementation
     */
    record Command(
            UUID integrationClientId,
            String materialCode,
            String sourceSystem,
            OffsetDateTime sourceUpdatedAt,
            Set<String> fieldsSent,
            String description,
            BigDecimal lengthM,
            BigDecimal widthM,
            BigDecimal heightM,
            BigDecimal unitWeightKg,
            BigDecimal unitVolumeM3,
            String stackMode,
            Integer stackMaxQuantity,
            BigDecimal nestIncrementM,
            BigDecimal maxTopLoadKg,
            Boolean orientationLocked) {

        public Command {
            fieldsSent = fieldsSent == null ? Set.of() : Set.copyOf(fieldsSent);
        }

        public boolean sent(String field) {
            return fieldsSent.contains(field);
        }
    }

    /**
     * @param version         the stored version after the write (the untouched one when STALE)
     * @param sourceUpdatedAt the stored owner timestamp after the write
     */
    record Result(UUID id, String materialCode, Outcome outcome, long version, OffsetDateTime sourceUpdatedAt) {
    }
}
