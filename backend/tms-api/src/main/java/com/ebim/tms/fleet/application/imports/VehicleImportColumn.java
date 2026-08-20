package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.shared.imports.ImportColumnSpec;
import java.util.Arrays;
import java.util.List;

/** The columns of the bulk Vehicle import file. See {@code LocationImportColumn} for the pattern this follows. */
public enum VehicleImportColumn {

    CODE("code", true, "Required. Unique per company. Re-importing a code that already exists is skipped, not duplicated."),
    LICENSE_PLATE("licensePlate", true, "Required. Unique per company."),
    CARRIER_CODE("carrierCode", false, "Optional. Code of an existing carrier in this company. Leave blank for an owned-fleet vehicle."),
    VEHICLE_TYPE_CODE("vehicleTypeCode", true, "Required. Code of an existing vehicle type in this company."),
    MAX_WEIGHT_OVERRIDE_KG("maxWeightOverrideKg", false, "Optional. Overrides the vehicle type's max weight for this vehicle only."),
    MAX_VOLUME_OVERRIDE_M3("maxVolumeOverrideM3", false, "Optional. Overrides the vehicle type's max volume for this vehicle only."),
    MAX_PALLETS_OVERRIDE("maxPalletsOverride", false, "Optional. Overrides the vehicle type's max pallets for this vehicle only."),
    AVAILABILITY_STATUS("availabilityStatus", false, "Optional. AVAILABLE, IN_MAINTENANCE or OUT_OF_SERVICE. Defaults to AVAILABLE."),
    EXTERNAL_REFERENCE("externalReference", false, "Optional. A pointer back to this vehicle in an external fleet/ERP system.");

    private final String header;
    private final boolean required;
    private final String helpText;

    VehicleImportColumn(String header, boolean required, String helpText) {
        this.header = header;
        this.required = required;
        this.helpText = helpText;
    }

    public String header() {
        return header;
    }

    public boolean required() {
        return required;
    }

    public ImportColumnSpec spec() {
        return new ImportColumnSpec(header, required, helpText);
    }

    public static final List<ImportColumnSpec> SPECS =
            List.of(Arrays.stream(values()).map(VehicleImportColumn::spec).toArray(ImportColumnSpec[]::new));
}
