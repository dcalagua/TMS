package com.ebim.tms.integration.application;

import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.OrderIntakePort;
import com.ebim.tms.shared.reference.OrderIntakeResult;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The order half of the inbound API. Mirrors {@link IntegrationLocationService} exactly, including
 * the deliberate absence of {@code @Transactional} - see that class for why per-item independence
 * depends on it.
 */
@Service
public class IntegrationOrderService {

    private final OrderIntakePort orderIntakePort;
    private final IntegrationProperties properties;

    public IntegrationOrderService(OrderIntakePort orderIntakePort, IntegrationProperties properties) {
        this.orderIntakePort = orderIntakePort;
        this.properties = properties;
    }

    public IntegrationOutcome<OrderUpsertResult> upsert(IntegrationPrincipal principal, OrderUpsertRequest request) {
        OrderIntakeResult result = orderIntakePort.upsert(principal.companyScope(), request.toCommand());
        OrderUpsertResult body = OrderUpsertResult.from(result);
        return IntegrationOutcome.single(body, httpStatusFor(result), result.id(),
                request.externalSource(), request.externalReference());
    }

    public IntegrationOutcome<OrderBatchResult> batch(IntegrationPrincipal principal, OrderBatchRequest request) {
        requireAcceptableSize(request.orders().size());

        List<OrderBatchResult.Item> items = new ArrayList<>(request.orders().size());
        for (int index = 0; index < request.orders().size(); index++) {
            OrderUpsertRequest item = request.orders().get(index);
            try {
                OrderIntakeResult result = orderIntakePort.upsert(principal.companyScope(), item.toCommand());
                items.add(OrderBatchResult.Item.succeeded(index, item.externalReference(),
                        OrderUpsertResult.from(result)));
            } catch (RuntimeException failure) {
                items.add(OrderBatchResult.Item.failed(index, item.externalReference(),
                        IntegrationItemError.from(failure)));
            }
        }

        OrderBatchResult body = OrderBatchResult.of(items);
        return IntegrationOutcome.batch(body, body.submitted(), body.succeeded(), body.failed(),
                firstExternalSource(request));
    }

    private void requireAcceptableSize(int size) {
        if (size > properties.maxBatchSize()) {
            throw new InvalidRequestException("A batch may carry at most " + properties.maxBatchSize()
                    + " orders; this one carried " + size + ".");
        }
    }

    private static int httpStatusFor(OrderIntakeResult result) {
        return switch (result.outcome()) {
            case CREATED -> 201;
            case UPDATED, UNCHANGED -> 200;
        };
    }

    private static String firstExternalSource(OrderBatchRequest request) {
        return request.orders().stream()
                .map(OrderUpsertRequest::externalSource)
                .filter(source -> source != null && !source.isBlank())
                .findFirst()
                .orElse(null);
    }
}
