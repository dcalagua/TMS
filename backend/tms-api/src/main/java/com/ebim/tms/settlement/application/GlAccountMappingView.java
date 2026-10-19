package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.GlAccountMapping;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A GL account mapping (V65), with the name of the origin or route it is scoped to. */
public record GlAccountMappingView(
        UUID id,
        String concept,
        /** ADR-027: the withholding a WITHHOLDING_PAYABLE mapping books; null = every kind. */
        String withholdingKind,
        String scopeType,
        UUID scopeId,
        String scopeName,
        String accountCode,
        String costCenterCode,
        boolean active,
        OffsetDateTime updatedAt) {

    public static GlAccountMappingView of(GlAccountMapping mapping, String scopeName) {
        return new GlAccountMappingView(mapping.id(), mapping.concept().name(),
                mapping.withholdingKind() == null ? null : mapping.withholdingKind().name(), mapping.scopeType().name(),
                mapping.scopeId(), scopeName, mapping.accountCode(), mapping.costCenterCode(), mapping.active(),
                mapping.updatedAt());
    }
}
