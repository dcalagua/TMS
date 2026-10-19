package com.ebim.tms.integration.application;

import java.util.List;

/**
 * A batch of material profiles, with the optional fields each item named, at the item's index.
 *
 * @param sourceSystem the producer, applied to every item that does not name its own
 */
public record MaterialProfileBatchDelivery(String sourceSystem, List<MaterialProfileV1> profiles,
        List<List<String>> fieldsSent) {

    public MaterialProfileBatchDelivery {
        profiles = List.copyOf(profiles);
        fieldsSent = fieldsSent.stream().map(List::copyOf).toList();
    }
}
