package com.ebim.tms.fleet.domain;

/**
 * The physical body category of a {@link VehicleType}, mirroring the
 * {@code ck_vehicle_type_body_type} check constraint in migration V9. Optional: a vehicle type
 * may leave this unset rather than force-fit a category.
 */
public enum VehicleBodyType {
    DRY_VAN,
    REFRIGERATED,
    FLATBED,
    TANKER,
    CONTAINER,
    CURTAIN_SIDER,
    OTHER
}
