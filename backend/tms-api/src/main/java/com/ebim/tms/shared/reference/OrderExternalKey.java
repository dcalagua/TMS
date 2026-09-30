package com.ebim.tms.shared.reference;

/**
 * How an ERP names an order: its namespace and its reference in it (WAREHOUSE_EXECUTION_V1 §2.2).
 * Compared byte for byte - {@code SAPB1_PE} and {@code sapb1_pe} are two namespaces, and nothing in
 * TMS normalises one into the other.
 */
public record OrderExternalKey(String externalSource, String externalReference) {
}
