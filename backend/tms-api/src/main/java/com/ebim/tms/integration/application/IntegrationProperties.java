package com.ebim.tms.integration.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed settings for the inbound integration API, under {@code tms.integration}.
 *
 * @param retainPayloads whether the raw request body is written to
 *     {@code integration_request.payload_snapshot}. <b>False by default</b>, and that default is
 *     the security decision: an order payload carries customer names, addresses and references,
 *     and copying them into an append-only audit table is a data-protection commitment, not a
 *     debugging convenience. A deployment that turns it on takes on the retention obligation
 *     documented in {@code docs/integrations/INBOUND_API_V1.md}
 * @param rotationGrace how long a superseded secret keeps working after a rotation, so a partner
 *     can redeploy without a coordinated cutover. Seven days by default; a rotation that responds
 *     to a suspected leak asks for zero explicitly
 * @param maxBatchSize the largest number of objects one batch request may carry. A bound, not a
 *     tuning knob: without it a single request could hold a million orders and there would be no
 *     answer to "how long may one request take"
 */
@ConfigurationProperties(prefix = "tms.integration")
public record IntegrationProperties(Boolean retainPayloads, Duration rotationGrace, Integer maxBatchSize) {

    public static final Duration DEFAULT_ROTATION_GRACE = Duration.ofDays(7);
    public static final int DEFAULT_MAX_BATCH_SIZE = 200;

    /** Above this, one request stops being a delivery and becomes a migration. */
    public static final int ABSOLUTE_MAX_BATCH_SIZE = 1000;

    public IntegrationProperties {
        retainPayloads = retainPayloads != null && retainPayloads;
        rotationGrace = rotationGrace != null && !rotationGrace.isNegative() ? rotationGrace : DEFAULT_ROTATION_GRACE;
        int requested = maxBatchSize == null ? DEFAULT_MAX_BATCH_SIZE : maxBatchSize;
        maxBatchSize = Math.max(1, Math.min(requested, ABSOLUTE_MAX_BATCH_SIZE));
    }
}
