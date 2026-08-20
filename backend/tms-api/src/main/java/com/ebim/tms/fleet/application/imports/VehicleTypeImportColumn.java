package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.shared.imports.ImportColumnSpec;
import java.util.Arrays;
import java.util.List;

/** The columns of the bulk Vehicle Type import file. See {@code LocationImportColumn} for the pattern this follows. */
public enum VehicleTypeImportColumn {

    CODE("code", true, "Required. Unique per company. Re-importing a code that already exists is skipped, not duplicated."),
    NAME("name", true, "Required."),
    MAX_WEIGHT_KG("maxWeightKg", true, "Required. Kilograms, never tons. Greater than zero."),
    MAX_VOLUME_M3("maxVolumeM3", true, "Required. Cubic meters, never cm3. Greater than zero."),
    MAX_PALLETS("maxPallets", true, "Required. Whole number, 0 or more."),
    LENGTH_M("lengthM", false, "Optional. Meters. Greater than zero."),
    WIDTH_M("widthM", false, "Optional. Meters. Greater than zero."),
    HEIGHT_M("heightM", false, "Optional. Meters. Greater than zero."),
    BODY_TYPE("bodyType", false, "Optional. One of DRY_VAN, REFRIGERATED, FLATBED, TANKER, CONTAINER, CURTAIN_SIDER, OTHER."),
    TEMPERATURE_CONTROLLED("temperatureControlled", false, "Optional. TRUE or FALSE. Defaults to FALSE."),
    MIN_TEMPERATURE_CELSIUS("minTemperatureCelsius", false, "Optional. Only when temperatureControlled is TRUE."),
    MAX_TEMPERATURE_CELSIUS("maxTemperatureCelsius", false, "Optional. Only when temperatureControlled is TRUE. Must be >= minTemperatureCelsius."),
    AXLES("axles", false, "Optional. Whole number, 1 or more.");

    private final String header;
    private final boolean required;
    private final String helpText;

    VehicleTypeImportColumn(String header, boolean required, String helpText) {
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
            List.of(Arrays.stream(values()).map(VehicleTypeImportColumn::spec).toArray(ImportColumnSpec[]::new));
}
