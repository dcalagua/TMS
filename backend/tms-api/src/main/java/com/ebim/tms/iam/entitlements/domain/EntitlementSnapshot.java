package com.ebim.tms.iam.entitlements.domain;

import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.node.ObjectNode;

/**
 * An {@code ebim.entitlements/v1} snapshot whose shape has been validated.
 *
 * @param raw the document exactly as received; it is what is stored and what the checksum covers
 */
public record EntitlementSnapshot(
        java.util.UUID controlPlaneTenantId,
        String productCode,
        String environment,
        long snapshotVersion,
        String checksum,
        boolean appActive,
        List<Capability> capabilities,
        List<Limit> limits,
        List<String> allowanceCodes,
        ObjectNode raw) {

    /**
     * A FEATURE/AI_FEATURE line. TMS registers none today, so none is ever granted.
     *
     * @param explicitCompanies the grant names MasterAdmin company ids. TMS cannot translate them
     *                          into its own companies, so such a grant reaches nobody (fail closed)
     */
    public record Capability(String code, boolean enabled, boolean explicitCompanies) {}

    /**
     * A granted LIMIT. Absent means "no commercial limit granted", never "unlimited".
     *
     * @param explicitCompanies as for {@link Capability}: such a limit is not applied to anyone
     */
    public record Limit(String code, double value, String unit, String enforcement, boolean explicitCompanies) {}

    /** Every code the snapshot carries: capabilities, limits and allowances. */
    public List<String> allCodes() {
        return Stream.of(capabilities.stream().map(Capability::code), limits.stream().map(Limit::code),
                        allowanceCodes.stream())
                .flatMap(codes -> codes)
                .toList();
    }
}
