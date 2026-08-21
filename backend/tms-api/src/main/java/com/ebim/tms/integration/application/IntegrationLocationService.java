package com.ebim.tms.integration.application;

import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.LocationIntakePort;
import com.ebim.tms.shared.reference.LocationIntakeResult;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The location half of the inbound API: one object or a batch, each item going through
 * {@link LocationIntakePort} and therefore through {@code LocationService}.
 *
 * <p>Nothing here is {@code @Transactional}. That is the point of the batch contract: each item's
 * transaction is opened by the port implementation, so item 3 failing leaves items 1 and 2
 * committed. Making this class transactional would silently turn the documented per-item
 * independence into all-or-nothing.
 */
@Service
public class IntegrationLocationService {

    private final LocationIntakePort locationIntakePort;
    private final IntegrationProperties properties;

    public IntegrationLocationService(LocationIntakePort locationIntakePort, IntegrationProperties properties) {
        this.locationIntakePort = locationIntakePort;
        this.properties = properties;
    }

    /**
     * One location. Failures propagate, so the executor records them and the caller gets an RFC
     * 9457 document - a single-object endpoint has no partial outcome to describe.
     */
    public IntegrationOutcome<LocationUpsertResult> upsert(IntegrationPrincipal principal,
            LocationUpsertRequest request) {
        LocationIntakeResult result = locationIntakePort.upsert(principal.companyScope(), request.toCommand());
        LocationUpsertResult body = LocationUpsertResult.from(result);
        return IntegrationOutcome.single(body, httpStatusFor(result), result.id(),
                request.externalSystem(), request.externalReference());
    }

    /**
     * A batch. Every item is attempted; a business failure becomes that item's error and the rest
     * continue. An unexpected fault is <em>not</em> caught here - {@link IntegrationItemError#from}
     * rethrows it - so a bug in TMS surfaces as a 500 with a correlation id instead of being
     * reported to the partner as if their data were at fault.
     */
    public IntegrationOutcome<LocationBatchResult> batch(IntegrationPrincipal principal,
            LocationBatchRequest request) {
        requireAcceptableSize(request.locations().size());

        List<LocationBatchResult.Item> items = new ArrayList<>(request.locations().size());
        for (int index = 0; index < request.locations().size(); index++) {
            LocationUpsertRequest item = request.locations().get(index);
            try {
                LocationIntakeResult result = locationIntakePort.upsert(principal.companyScope(), item.toCommand());
                items.add(LocationBatchResult.Item.succeeded(index, item.code(), LocationUpsertResult.from(result)));
            } catch (RuntimeException failure) {
                items.add(LocationBatchResult.Item.failed(index, item.code(), IntegrationItemError.from(failure)));
            }
        }

        LocationBatchResult body = LocationBatchResult.of(items);
        return IntegrationOutcome.batch(body, body.submitted(), body.succeeded(), body.failed(),
                firstExternalSystem(request));
    }

    private void requireAcceptableSize(int size) {
        if (size > properties.maxBatchSize()) {
            throw new InvalidRequestException("A batch may carry at most " + properties.maxBatchSize()
                    + " locations; this one carried " + size + ".");
        }
    }

    /**
     * 201 for a location that did not exist, 200 for one that did. A partner that keys their own
     * bookkeeping on the status code gets the honest answer, and it costs nothing to give.
     */
    private static int httpStatusFor(LocationIntakeResult result) {
        return switch (result.outcome()) {
            case CREATED -> 201;
            case UPDATED, UNCHANGED -> 200;
        };
    }

    /**
     * The external system recorded on the inbox row for a batch. Batches come from one sending
     * system in practice, so the first item's value describes the delivery; it is a label for
     * searching the inbox, not an identity.
     */
    private static String firstExternalSystem(LocationBatchRequest request) {
        return request.locations().stream()
                .map(LocationUpsertRequest::externalSystem)
                .filter(system -> system != null && !system.isBlank())
                .findFirst()
                .orElse(null);
    }
}
