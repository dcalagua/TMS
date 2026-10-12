package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.security.CompanyScope;
import java.util.Set;
import java.util.UUID;

/**
 * Told when a logistics document's orders are (re)linked (ADR-015, ADR-018).
 *
 * <p>Declared here and implemented by planning, so that a document arriving for an order already on
 * a dispatched trip starts being tracked on that trip without {@code orders} depending on
 * {@code planning} ({@code ModuleBoundaryTest}). Called inside the ingestion's transaction: if the
 * listener fails, the document is not stored either, and the sender retries.
 */
public interface LogisticsDocumentLinkListener {

    /**
     * @param orderIds the orders the document covers now, in this company
     */
    void documentLinked(CompanyScope scope, UUID documentId, String documentType, String documentNumber,
            Set<UUID> orderIds);
}
