package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.VehicleBodyType;

/** The optional list filters for {@code GET /fleet/vehicle-types}, bound alongside {@code PageQuery}. */
public record VehicleTypeFilter(String code, String name, VehicleBodyType bodyType, Boolean active) {
}
