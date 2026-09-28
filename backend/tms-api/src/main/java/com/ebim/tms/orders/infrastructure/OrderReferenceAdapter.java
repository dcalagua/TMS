package com.ebim.tms.orders.infrastructure;

import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.orders.domain.TransportOrderLine;
import com.ebim.tms.shared.reference.OrderExternalKey;
import com.ebim.tms.shared.reference.OrderLineSnapshot;
import com.ebim.tms.shared.reference.OrderReferencePort;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The orders module's answer to {@link OrderReferencePort}. */
@Component
class OrderReferenceAdapter implements OrderReferencePort {

    private final TransportOrderRepository orders;

    OrderReferenceAdapter(TransportOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<OrderExternalKey, UUID> findIdsByExternalKeys(Collection<OrderExternalKey> keys, UUID companyId) {
        Map<OrderExternalKey, UUID> byKey = new HashMap<>();
        Set<String> references = keys.stream().map(OrderExternalKey::externalReference)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (references.isEmpty()) {
            return byKey;
        }
        // One query on the reference, then the namespace compared exactly in memory: a handful of
        // rows per reference at most, and no second index needed for a pair already unique.
        Set<OrderExternalKey> wanted = Set.copyOf(keys);
        for (TransportOrder order : orders.findByCompanyIdAndExternalReferenceIn(companyId, references)) {
            OrderExternalKey key = new OrderExternalKey(order.externalSource(), order.externalReference());
            if (wanted.contains(key)) {
                byKey.put(key, order.id());
            }
        }
        return byKey;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<OrderLineSnapshot>> linesOf(Set<UUID> orderIds, UUID companyId) {
        Map<UUID, List<OrderLineSnapshot>> byOrder = new HashMap<>();
        if (orderIds.isEmpty()) {
            return byOrder;
        }
        for (TransportOrder order : orders.findByIdInAndCompanyId(orderIds, companyId)) {
            byOrder.put(order.id(), order.lines().stream()
                    .sorted(Comparator.comparingInt(TransportOrderLine::lineNumber))
                    .map(line -> new OrderLineSnapshot(line.lineNumber(), line.materialCode(), line.quantity(),
                            line.uom()))
                    .toList());
        }
        return byOrder;
    }
}
