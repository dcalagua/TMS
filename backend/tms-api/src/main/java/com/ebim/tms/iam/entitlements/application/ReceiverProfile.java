package com.ebim.tms.iam.entitlements.application;

import com.ebim.tms.iam.entitlements.domain.TmsCapabilityRegistry;
import java.util.Set;

/**
 * Which product this receiver is and which codes it can enforce.
 *
 * <p>In production it is always {@link #tms()}. It is a value so that the FIX-ENT-v1 golden fixtures -
 * written for a synthetic product {@code fixture} - run against the SAME services, not a copy.
 */
public record ReceiverProfile(String productCode, Set<String> knownCodes) {

    public ReceiverProfile {
        knownCodes = Set.copyOf(knownCodes);
    }

    public static ReceiverProfile tms() {
        return new ReceiverProfile(TmsCapabilityRegistry.PRODUCT_CODE, TmsCapabilityRegistry.known());
    }
}
