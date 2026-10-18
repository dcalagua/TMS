import { describe, expect, it } from "vitest";
import {
  CAPACITY_NEAR_THRESHOLD, capacityAmount, capacityTone, describeCapacityRefusal, geometryReasonText,
  occupancyText, orderGeometryState, stacksText, usableFloorAreaM2, volumeComparisonText, volumeSaving,
  volumeSavingText,
} from "./loadGeometry";

/**
 * La presentación de la geometría de carga (V62, ADR-022). Nada aquí calcula pilas: se fija cómo se
 * dice lo que calculó el backend. El caso de referencia es el de PLAPASA (`LOAD_GEOMETRY_V1.md`):
 * 1.000 vasos PLP-571 = 10 pilas de 1,363 m, 0,1503 m³ efectivos frente a 1,93 m³ declarados,
 * 0,1103 m² de piso.
 */

const dim = (percentUsed: number | null, exceeded = false, unlimited = false) => ({ percentUsed, exceeded, unlimited });

describe("capacityTone", () => {
  it("avisa en ámbar desde el umbral y no antes", () => {
    expect(CAPACITY_NEAR_THRESHOLD).toBe(85);
    expect(capacityTone(dim(84.9))).toBe("ok");
    expect(capacityTone(dim(85))).toBe("near");
    expect(capacityTone(dim(99.9))).toBe("near");
  });

  it("el exceso es el veredicto del backend, no un porcentaje de aquí", () => {
    expect(capacityTone(dim(120, true))).toBe("over");
    // Un 100 % justo no está excedido: los límites son inclusivos.
    expect(capacityTone(dim(100, false))).toBe("near");
  });

  it("separa sin límite de un límite sin porcentaje", () => {
    expect(capacityTone(dim(null, false, true))).toBe("unlimited");
    expect(capacityTone(dim(null, false, false))).toBe("noPercent");
  });
});

describe("volumen efectivo frente a declarado", () => {
  it("dice las dos cifras del caso PLAPASA", () => {
    expect(volumeComparisonText(0.1503, 1.93)).toBe("0,15 m³ efectivos · 1,93 m³ declarados");
  });

  it("nombra el ahorro con su porcentaje", () => {
    expect(volumeSaving(0.1503, 1.93)).toEqual({ savedM3: expect.closeTo(1.7797, 4), percent: 92 });
    expect(volumeSavingText(0.1503, 1.93)).toBe("Ahorra 1,78 m³ (92 %)");
  });

  it("no inventa un ahorro cuando la geometría ocupa lo mismo o más", () => {
    expect(volumeSavingText(2, 1.93)).toBeNull();
    expect(volumeSavingText(1.93, 1.93)).toBeNull();
    expect(volumeSavingText(null, 1.93)).toBeNull();
    expect(volumeSavingText(0.5, 0)).toBeNull();
  });

  it("no repite el mismo número cuando las dos cifras se redondean igual", () => {
    expect(volumeComparisonText(1.931, 1.93)).toBe("1,93 m³ efectivos");
    expect(volumeComparisonText(null, 1.93)).toBe("1,93 m³ declarados");
  });
});

describe("lo que ocupa", () => {
  it("cuenta pilas en singular y plural", () => {
    expect(stacksText(1)).toBe("1 pila");
    expect(stacksText(10)).toBe("10 pilas");
  });

  it("dice pilas, piso y alto, y omite lo que no se conoce", () => {
    expect(occupancyText({ stacks: 10, floorAreaM2: 0.1103, stackHeightM: 1.363 }))
      .toBe("10 pilas · 0,11 m² de piso · alto 1,36 m");
    expect(occupancyText({ stacks: null, floorAreaM2: 0, stackHeightM: null })).toBe("");
  });

  it("clasifica el estado del pedido por la pila más alta y las líneas sin medir", () => {
    expect(orderGeometryState(null)).toBe("none");
    expect(orderGeometryState({ maxStackHeightM: null, unmeasuredLines: 0 })).toBe("none");
    expect(orderGeometryState({ maxStackHeightM: 1.363, unmeasuredLines: 0 })).toBe("measured");
    expect(orderGeometryState({ maxStackHeightM: 1.363, unmeasuredLines: 2 })).toBe("partial");
    expect(orderGeometryState({ maxStackHeightM: null, unmeasuredLines: 1 })).toBe("unmeasured");
  });
});

describe("motivos de una línea sin geometría", () => {
  it("redacta cada motivo conocido como lo que falta", () => {
    expect(geometryReasonText("MISSING_NEST_INCREMENT")).toBe("Falta el incremento de anidado");
    expect(geometryReasonText("MISSING_UNIT_WEIGHT")).toBe("Falta el peso unitario (hace falta para la carga máxima encima)");
    expect(geometryReasonText("UOM_NOT_COUNTABLE")).toContain("no es un conteo");
    expect(geometryReasonText("ITEM_TALLER_THAN_VEHICLE")).toBe("Una unidad es más alta que el alto de referencia");
  });

  it("deja tal cual un código nuevo y nada si no hay motivo", () => {
    expect(geometryReasonText("SOMETHING_NEW")).toBe("SOMETHING_NEW");
    expect(geometryReasonText(null)).toBeNull();
  });
});

describe("describeCapacityRefusal", () => {
  it("traduce cada dimensión que no cupo, con sus cifras", () => {
    const text = describeCapacityRefusal(
      "Order TO-00000007 does not fit trip 2: weight 1200 kg exceeds the capacity of 1000 kg; "
        + "floor area 3.5 m2 exceeds the capacity of 3.06 m2; stack height 2.73 m exceeds the capacity of 2.5 m.",
    );
    expect(text).toBe(
      "El pedido TO-00000007 no cabe en el viaje 2. "
        + "El peso (1200 kg) supera la capacidad del vehículo (1000 kg). "
        + "El piso ocupado (3,5 m²) supera el piso útil del vehículo (3,06 m²). "
        + "La pila más alta (2,73 m) supera el alto útil del vehículo (2,5 m).",
    );
  });

  it("dice que una unidad más alta que el vehículo no se arregla repartiendo", () => {
    const text = describeCapacityRefusal(
      "Order TO-00000009 does not fit trip 1: ITEM_TALLER_THAN_VEHICLE - a unit 2.9 m tall exceeds the vehicle height of 2.5 m.",
    );
    expect(text).toBe(
      "El pedido TO-00000009 no cabe en el viaje 1. Una unidad mide 2,9 m de alto y el vehículo admite 2,5 m: "
        + "no entra aunque se reparta o se reapile. Hace falta un vehículo más alto.",
    );
  });

  it("nombra el cambio de vehículo y la confirmación del plan", () => {
    expect(describeCapacityRefusal(
      "Vehicle VAN-01 cannot take what is already planned on trip 3: volume 12.5 m3 exceeds the capacity of 10 m3.",
    )).toBe(
      "El vehículo VAN-01 no puede llevar lo ya planificado en el viaje 3. "
        + "El volumen de planificación (12,5 m³) supera la capacidad del vehículo (10 m³).",
    );
    expect(describeCapacityRefusal(
      "Trip 2 of planning run PL-00000004 cannot be confirmed: pallets 14 pallets exceeds the capacity of 12 pallets.",
    )).toBe(
      "El viaje 2 del plan PL-00000004 no se puede confirmar. Los pallets (14 pallets) superan la capacidad del vehículo (12 pallets).",
    );
  });

  it("devuelve null ante una frase que no reconoce, para mostrar la original", () => {
    expect(describeCapacityRefusal("This trip was changed by someone else since it was loaded. Reload and try again.")).toBeNull();
    expect(describeCapacityRefusal("Order X does not fit trip 1: something new happened.")).toBeNull();
    expect(describeCapacityRefusal(undefined)).toBeNull();
  });
});

describe("medidas del vehículo", () => {
  it("el piso útil es largo × ancho × factor, y sin largo o ancho no hay límite", () => {
    expect(usableFloorAreaM2(6, 2.4, 0.85)).toBeCloseTo(12.24, 6);
    expect(usableFloorAreaM2(null, 2.4, 0.85)).toBeNull();
    expect(usableFloorAreaM2(6, 2.4, undefined)).toBeNull();
  });

  it("formatea cada dimensión en su unidad", () => {
    expect(capacityAmount("floor", 0.1103)).toBe("0,11 m²");
    expect(capacityAmount("height", 1.363)).toBe("1,36 m");
    expect(capacityAmount("volume", 0.1503)).toBe("0,15 m³");
    expect(capacityAmount("pallets", 12)).toBe("12 pallets");
  });
});
