package com.ebim.tms.shared.imports;

/**
 * Which master-data entity an {@link ImportBatch} row applied. Mirrors
 * {@code ck_import_batch_entity_type} (migration V21).
 */
public enum ImportEntityType {
    LOCATION,
    CARRIER,
    VEHICLE_TYPE,
    VEHICLE
}
