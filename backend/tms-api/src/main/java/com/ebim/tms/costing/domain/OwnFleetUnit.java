package com.ebim.tms.costing.domain;

/**
 * The units own-fleet rates are quoted in (V48, JOB 22).
 *
 * <p>Two values, not seven. {@code rates.CostUnit} carries KG, M3, PALLET, STOP and PERCENT because
 * a carrier's tariff charges on all of them; nothing a truck consumes is measured in pallets. Its
 * own enum rather than the rates one because ArchUnit is right that borrowing it couples two
 * modules that should stay extractable - and because a five-value gap nothing can ever emit is
 * worse vocabulary, not better.
 */
public enum OwnFleetUnit {
    KM,
    HOUR
}
