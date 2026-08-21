package com.ebim.tms.shared.api;

import java.io.Serial;

/**
 * An optional capability was asked of a deployment that has not been configured for it.
 *
 * <p>Answered with 503 and {@link ProblemType#FEATURE_NOT_CONFIGURED}, never 500: nothing failed,
 * and no retry and no different payload will help until an administrator sets the missing setting.
 * The message is written for the caller and must therefore name the capability and never the
 * setting's value - "webhook delivery is not configured in this deployment", not the key it is
 * missing.
 *
 * <p>Today's single source is outbound webhooks (migration V35), which need
 * {@code tms.integration.webhooks.secret-key} to encrypt subscription secrets with. Proof-of-delivery
 * evidence storage keeps its own {@code EvidenceStorageUnavailableException} rather than being
 * migrated onto this one: its code is part of a published contract, and renaming an error a partner
 * may already branch on is not worth the tidiness.
 */
public class FeatureNotConfiguredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public FeatureNotConfiguredException(String message) {
        super(message);
    }
}
