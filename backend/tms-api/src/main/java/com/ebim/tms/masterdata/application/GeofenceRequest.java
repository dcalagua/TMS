package com.ebim.tms.masterdata.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * The circle around a place (migration V43, ADR-011).
 *
 * <p>{@code radiusMetres} is nullable and null <b>clears</b> the geofence - that is how a site stops
 * using one, and it is why this is a request object rather than a bare query parameter that could
 * not tell "clear it" from "not supplied".
 *
 * <p>Bounds match {@code ck_location_geofence_radius}: consumer GPS is not accurate below 25m, so a
 * tighter circle would be a feature that never fires, and a circle over 20km stops distinguishing
 * this site from the next town.
 */
public record GeofenceRequest(
        @Min(value = 25, message = "must be at least 25 metres")
        @Max(value = 20_000, message = "must be at most 20000 metres")
        Integer radiusMetres) {
}
