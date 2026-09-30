package com.ebim.tms.integration.application;

/**
 * The driver on a published shipment (WAREHOUSE_EXECUTION_V1 §3.3), additive in v1. The licence is
 * deliberately not published: a warehouse needs to know who will collect the load, not their
 * permit.
 */
public record ShipmentPlanDriverV1(String code, String name, String documentNumber) {

    static ShipmentPlanDriverV1 of(String code, String name, String documentNumber) {
        return code == null && name == null && documentNumber == null
                ? null
                : new ShipmentPlanDriverV1(code, name, documentNumber);
    }
}
