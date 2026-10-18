import { describe, expect, it } from "vitest";
import {
  breakdownAddsUp, isAllowedVatRate, isValidDocumentNumber, isValidEcRuc, isValidPeRuc, isValidTaxId,
  previewVatRate, previewWithholdings, requiresBreakdown, roundHalfUp, taxOf,
} from "./fiscalRules";
import { EC_REGIME, GENERIC_REGIME, PE_REGIME } from "../../test/fixtures/taxRegimes";

/** Las mismas reglas que los validadores y el cálculo de retenciones del servidor (ADR-027). */

describe("RUC del Perú", () => {
  it.each(["20100070970", "20131312955", "10000000014", "20000000010", "20000000061"])("acepta %s", (ruc) => {
    expect(isValidPeRuc(ruc)).toBe(true);
    expect(isValidTaxId("PE_RUC", ruc)).toBe(true);
  });
  it.each(["20100070971", "2010007097", "30100070970", "2010007097A", ""])("rechaza %s", (ruc) => {
    expect(isValidPeRuc(ruc)).toBe(false);
  });
});

describe("RUC del Ecuador", () => {
  // Dígitos verificadores calculados a mano con la regla del SRI (no son contribuyentes reales,
  // salvo 1790016919001, que cumple el módulo 11 de sociedad privada).
  it.each([
    ["0990004196001", "sociedad privada, módulo 11 sobre 9"],
    ["1790016919001", "sociedad privada"],
    ["0912345675001", "persona natural, módulo 10 de la cédula"],
    ["1700034067001", "persona natural"],
    ["1760000150001", "entidad pública, módulo 11 sobre 8"],
  ])("acepta %s (%s)", (ruc) => {
    expect(isValidEcRuc(ruc)).toBe(true);
    expect(isValidTaxId("EC_RUC", ruc)).toBe(true);
  });
  it.each([
    ["0990004197001", "verificador equivocado"],
    ["0912345674001", "cédula con verificador equivocado"],
    ["0912345675000", "establecimiento 000"],
    ["1760000150000", "establecimiento público 0000"],
    ["2590004196001", "provincia 25"],
    ["0070004196001", "tercer dígito 7"],
    ["099000419600", "12 dígitos"],
    ["20100070970", "un RUC peruano"],
  ])("rechaza %s (%s)", (ruc) => {
    expect(isValidEcRuc(ruc)).toBe(false);
  });
  it("una identificación de texto libre sólo pide que no esté vacía", () => {
    expect(isValidTaxId("FREE_TEXT", "900123456-7")).toBe(true);
    expect(isValidTaxId("FREE_TEXT", "  ")).toBe(false);
  });
});

describe("número del comprobante", () => {
  it("sigue el patrón del régimen", () => {
    expect(isValidDocumentNumber(EC_REGIME, "001-001-000000123")).toBe(true);
    expect(isValidDocumentNumber(EC_REGIME, "001-001-123")).toBe(true);
    expect(isValidDocumentNumber(EC_REGIME, "F001-123")).toBe(false);
    expect(isValidDocumentNumber(PE_REGIME, "f001-123")).toBe(true);
    expect(isValidDocumentNumber(PE_REGIME, "001-001-000000123")).toBe(false);
  });
  it("sin patrón (genérico) admite cualquier texto no vacío", () => {
    expect(isValidDocumentNumber(GENERIC_REGIME, "FE-2026/0042")).toBe(true);
    expect(isValidDocumentNumber(GENERIC_REGIME, " ")).toBe(false);
  });
});

describe("redondeo, desglose y tasas", () => {
  it("redondea en el medio lejos del cero", () => {
    expect(roundHalfUp(15.255, 2)).toBe(15.26);
    expect(roundHalfUp(47.5, 0)).toBe(48);
    expect(roundHalfUp(46.5, 0)).toBe(47);
  });
  it("calcula el impuesto al céntimo", () => {
    expect(taxOf(84.75, 18)).toBe(15.26);
    expect(taxOf(1450, 18)).toBe(261);
    expect(taxOf(1000, 0)).toBe(0);
  });
  it("admite un céntimo de diferencia y no dos", () => {
    expect(breakdownAddsUp(1450, 261, 1711.01, 0.01)).toBe(true);
    expect(breakdownAddsUp(1450, 261, 1711.02, 0.01)).toBe(false);
  });
  it("la tasa declarada es una de las del régimen", () => {
    expect(isAllowedVatRate(EC_REGIME, 0)).toBe(true);
    expect(isAllowedVatRate(EC_REGIME, 15)).toBe(true);
    expect(isAllowedVatRate(EC_REGIME, 12)).toBe(false);
    expect(isAllowedVatRate(PE_REGIME, 18)).toBe(true);
    expect(isAllowedVatRate(GENERIC_REGIME, 19)).toBe(true);
    expect(isAllowedVatRate(GENERIC_REGIME, 100)).toBe(false);
  });
  it("el flete se adelanta con la tasa de flete: 0 % en Ecuador, 18 % en Perú", () => {
    expect(previewVatRate(EC_REGIME)).toBe(0);
    expect(previewVatRate(PE_REGIME)).toBe(18);
    expect(previewVatRate(GENERIC_REGIME)).toBeNull();
  });
  it("el desglose es obligatorio sólo en la moneda fiscal", () => {
    expect(requiresBreakdown(EC_REGIME, "usd")).toBe(true);
    expect(requiresBreakdown(EC_REGIME, "EUR")).toBe(false);
    expect(requiresBreakdown(GENERIC_REGIME, "COP")).toBe(false);
  });
});

describe("retenciones del Perú", () => {
  const amounts = (total: number) => ({ subtotal: null, tax: null, total });
  it.each([[399.99, null], [400, null], [400.01, 16], [1711, 68]])("total %s → detracción %s", (total, amount) => {
    const preview = previewWithholdings(PE_REGIME, "INVOICE", "PEN", amounts(total as number));
    if (preview.status !== "computed") throw new Error("expected computed");
    if (amount === null) expect(preview.items).toEqual([]);
    else expect(preview.items).toEqual([expect.objectContaining({ kind: "PE_DETRACTION", code: "027", rate: 4, amount })]);
  });
  it("no se calcula en otra moneda ni en una nota", () => {
    expect(previewWithholdings(PE_REGIME, "INVOICE", "USD", amounts(5000))).toEqual({ status: "none", reason: "currency" });
    expect(previewWithholdings(PE_REGIME, "CREDIT_NOTE", "PEN", amounts(5000))).toEqual({ status: "none", reason: "note" });
  });
  it("la retención sólo donde no hay detracción", () => {
    const agent = {
      ...PE_REGIME,
      withholdings: PE_REGIME.withholdings.map((rule) => rule.kind === "PE_RETENTION" ? { ...rule, enabled: true } : rule),
    };
    const small = previewWithholdings(agent, "INVOICE", "PEN", amounts(350.55));
    expect(small.status === "computed" && small.items).toEqual([expect.objectContaining({ kind: "PE_RETENTION", amount: 10.52 })]);
    const large = previewWithholdings(agent, "INVOICE", "PEN", amounts(1711));
    expect(large.status === "computed" && large.items.map((item) => item.kind)).toEqual(["PE_DETRACTION"]);
  });
});

describe("retenciones del Ecuador", () => {
  it("IR 1 % código 310 sobre el subtotal; el IVA del flete es 0 y no retiene IVA", () => {
    const preview = previewWithholdings(EC_REGIME, "INVOICE", "USD", { subtotal: 1250, tax: 0, total: 1250 });
    expect(preview.status === "computed" && preview.items).toEqual([
      expect.objectContaining({ kind: "EC_INCOME_TAX_WITHHOLDING", code: "310", rate: 1, baseAmount: 1250, amount: 12.5 }),
    ]);
  });
  it("con la retención de IVA habilitada, 70 % del IVA, junto con la del IR", () => {
    const agent = {
      ...EC_REGIME,
      withholdings: EC_REGIME.withholdings.map((rule) => rule.kind === "EC_VAT_WITHHOLDING" ? { ...rule, enabled: true } : rule),
    };
    const preview = previewWithholdings(agent, "INVOICE", "USD", { subtotal: 1000, tax: 150, total: 1150 });
    expect(preview.status === "computed" && preview.items).toEqual([
      expect.objectContaining({ kind: "EC_INCOME_TAX_WITHHOLDING", amount: 10 }),
      expect.objectContaining({ kind: "EC_VAT_WITHHOLDING", baseAmount: 150, amount: 105 }),
    ]);
    const zeroVat = previewWithholdings(agent, "INVOICE", "USD", { subtotal: 1000, tax: 0, total: 1000 });
    expect(zeroVat.status === "computed" && zeroVat.items.map((item) => item.kind)).toEqual(["EC_INCOME_TAX_WITHHOLDING"]);
  });
  it("deshabilitada por defecto, la de IVA se informa como saltada", () => {
    const preview = previewWithholdings(EC_REGIME, "INVOICE", "USD", { subtotal: 1000, tax: 150, total: 1150 });
    expect(preview.status === "computed" && preview.skipped.map((skip) => [skip.rule.kind, skip.reason]))
      .toEqual([["EC_VAT_WITHHOLDING", "disabled"]]);
  });
});

describe("régimen genérico", () => {
  it("no impone moneda y no calcula ninguna retención", () => {
    expect(previewWithholdings(GENERIC_REGIME, "INVOICE", "COP", { subtotal: 100, tax: 19, total: 119 }))
      .toEqual({ status: "none", reason: "currency" });
  });
});
