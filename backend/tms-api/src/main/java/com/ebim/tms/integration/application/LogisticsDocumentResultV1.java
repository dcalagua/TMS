package com.ebim.tms.integration.application;

import java.util.List;
import java.util.UUID;

/** What TMS recorded: CREATED, UPDATED or UNCHANGED, and which order keys it could not find yet. */
public record LogisticsDocumentResultV1(UUID id, String outcome, int linkedOrders, List<String> unknownOrders) {
}
