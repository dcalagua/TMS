package com.ebim.tms.integration.application;

import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.TrackingIntakePort;
import com.ebim.tms.shared.reference.TrackingIntakeResult;
import com.ebim.tms.shared.reference.TrackingReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * The tracking half of the inbound API: translate the wire contract into the internal one, hand the
 * whole run to {@link TrackingIntakePort}, and translate the answers back.
 *
 * <p><b>Why this one hands over a list where {@link IntegrationOrderService} loops.</b> An order
 * upsert is an independent business decision per object, so that service calls its port once per
 * item and catches per item. Two of the three rules that decide a position's fate - the sampling
 * interval and the staleness cut-off - are statements about the <em>sequence</em>, so the run is
 * the unit of work and the port is batch-shaped. The per-item independence a partner sees is
 * unchanged: every report still gets its own outcome at its own index, and one bad shipment number
 * costs the other 199 nothing.
 *
 * <p><b>What a refusal is allowed to say.</b> {@code UNKNOWN_SHIPMENT} tells the sender that this
 * company has no such shipment, and nothing else - not whether it exists elsewhere, not whether it
 * once did. A telematics credential is often held by a third party who should learn nothing about
 * the tenant's shipments beyond whether the numbers it was handed are usable.
 */
@Service
public class IntegrationTrackingService {

    private final TrackingIntakePort trackingIntakePort;
    private final IntegrationProperties properties;

    public IntegrationTrackingService(TrackingIntakePort trackingIntakePort, IntegrationProperties properties) {
        this.trackingIntakePort = trackingIntakePort;
        this.properties = properties;
    }

    public IntegrationOutcome<TrackingBatchResult> batch(IntegrationPrincipal principal,
            TrackingBatchRequest request) {
        requireAcceptableSize(request.positions().size());

        // Lower-cased here rather than left to intake so that what the sender is told and what is
        // stored are the same string: a provider that calls itself "AcmeTelematics" is recorded as
        // "acmetelematics" (ck_tracking_position_provider_shape), and echoing back the original
        // spelling would make the two look like different feeds in a support conversation.
        String provider = request.provider().trim().toLowerCase(Locale.ROOT);

        List<TrackingReport> reports = request.positions().stream()
                .map(position -> position.toReport(provider))
                .toList();

        List<TrackingIntakeResult> outcomes = trackingIntakePort.record(principal.companyScope(), reports);

        List<TrackingBatchResult.Item> items = new ArrayList<>(outcomes.size());
        for (TrackingIntakeResult outcome : outcomes) {
            items.add(TrackingBatchResult.Item.from(outcome,
                    request.positions().get(outcome.index()).shipmentNumber()));
        }

        TrackingBatchResult body = TrackingBatchResult.of(items);
        // The inbox counts a refused position as a failed item, which is what makes a 207 mean the
        // same thing here as everywhere else. Accepted-but-not-stored is a success: nothing went
        // wrong, and a partner reading `failed` should see only what they can act on.
        return IntegrationOutcome.batch(body, body.submitted(), body.accepted(), body.refused(), provider);
    }

    private void requireAcceptableSize(int size) {
        if (size > properties.maxBatchSize()) {
            throw new InvalidRequestException("A batch may carry at most " + properties.maxBatchSize()
                    + " positions; this one carried " + size + ".");
        }
    }
}
