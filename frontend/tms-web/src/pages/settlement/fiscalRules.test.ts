import { describe, expect, it } from "vitest";
import type { FiscalSettingsView } from "../../shared/api/settlementApi";
import { breakdownAddsUp, igvOf, isValidRuc, previewTaxes, roundHalfUp } from "./fiscalRules";

/** Las mismas reglas que `RucValidatorTest` y `TaxCalculatorTest` del servidor (V66). */

const SETTINGS: FiscalSettingsView = {
  currency: "PEN", igvRate: 18, detractionEnabled: true, detractionCode: "027", detractionRate: 4,
  detractionThresholdAmount: 400, detractionStrictlyAboveThreshold: true, retentionAgent: false,
  retentionRate: 3, breakdownTolerance: 0.01,
};

describe("RUC", () => {
  it.each(["20100070970", "20131312955", "10000000014", "20000000010", "20000000061"])("acepta %s", (ruc) => {
    expect(isValidRuc(ruc)).toBe(true);
  });
  it.each(["20100070971", "2010007097", "30100070970", "2010007097A", ""])("rechaza %s", (ruc) => {
    expect(isValidRuc(ruc)).toBe(false);
  });
});

describe("redondeo y desglose", () => {
  it("redondea en el medio lejos del cero", () => {
    expect(roundHalfUp(15.255, 2)).toBe(15.26);
    expect(roundHalfUp(47.5, 0)).toBe(48);
    expect(roundHalfUp(46.5, 0)).toBe(47);
  });
  it("calcula el IGV al céntimo", () => {
    expect(igvOf(84.75, 18)).toBe(15.26);
    expect(igvOf(1450, 18)).toBe(261);
  });
  it("admite un céntimo de diferencia y no dos", () => {
    expect(breakdownAddsUp(1450, 261, 1711.01, 0.01)).toBe(true);
    expect(breakdownAddsUp(1450, 261, 1711.02, 0.01)).toBe(false);
  });
});

describe("detracción y retención", () => {
  it.each([[399.99, null], [400, null], [400.01, 16], [1711, 68]])("total %s → detracción %s", (total, amount) => {
    const preview = previewTaxes(SETTINGS, "01", "PEN", total as number);
    if (amount === null) expect(preview.kind).toBe("none");
    else expect(preview).toEqual({ kind: "detraction", code: "027", rate: 4, amount });
  });
  it("no se calcula en otra moneda ni en una nota", () => {
    expect(previewTaxes(SETTINGS, "01", "USD", 5000)).toEqual({ kind: "none", reason: "currency" });
    expect(previewTaxes(SETTINGS, "07", "PEN", 5000)).toEqual({ kind: "none", reason: "note" });
  });
  it("la retención sólo donde no hay detracción", () => {
    const agent = { ...SETTINGS, retentionAgent: true };
    expect(previewTaxes(agent, "01", "PEN", 350.55)).toEqual({ kind: "retention", rate: 3, amount: 10.52 });
    expect(previewTaxes(agent, "01", "PEN", 1711).kind).toBe("detraction");
  });
});
