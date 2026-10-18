import type {
  DocumentKind, TaxIdRule, TaxRegimeView, WithholdingBase, WithholdingKind, WithholdingRuleView,
} from "../../shared/api/settlementApi";

/**
 * Las reglas fiscales que el formulario adelanta (ADR-027). Son las mismas que aplica el servidor
 * (los validadores de identificación fiscal y el cálculo de retenciones del régimen) y **no traen
 * ninguna tasa, código, umbral ni formato propio**: todo llega resuelto en el `TaxRegimeView` de
 * `/settlement/fiscal-settings`. El servidor decide; esto sólo evita que alguien se entere al guardar.
 */

// ---------------------------------------------------------------- identificación fiscal

const PE_RUC_WEIGHTS = [5, 4, 3, 2, 7, 6, 5, 4, 3, 2];

/** RUC peruano: 11 dígitos, prefijo 10/15/17/20, dígito verificador módulo 11 (10 → 0, 11 → 1). */
export function isValidPeRuc(ruc: string): boolean {
  if (!/^(10|15|17|20)\d{9}$/.test(ruc)) return false;
  const sum = PE_RUC_WEIGHTS.reduce((acc, weight, index) => acc + Number(ruc[index]) * weight, 0);
  const digit = 11 - (sum % 11);
  const expected = digit === 10 ? 0 : digit === 11 ? 1 : digit;
  return expected === Number(ruc[10]);
}

const EC_NATURAL_COEFFICIENTS = [2, 1, 2, 1, 2, 1, 2, 1, 2];
const EC_PUBLIC_WEIGHTS = [3, 2, 7, 6, 5, 4, 3, 2];
const EC_PRIVATE_WEIGHTS = [4, 3, 2, 7, 6, 5, 4, 3, 2];

/** Módulo 11 del SRI: 11 → 0; 10 no es un dígito posible (null). */
function ecMod11(digits: string, weights: number[]): number | null {
  const sum = weights.reduce((acc, weight, index) => acc + Number(digits[index]) * weight, 0);
  const digit = 11 - (sum % 11);
  if (digit === 11) return 0;
  return digit === 10 ? null : digit;
}

/**
 * RUC ecuatoriano (SRI): 13 dígitos, provincia 01–24 o 30, y según el tercer dígito:
 * 0–5 persona natural (cédula de 10 con módulo 10 + establecimiento de 3, no 000),
 * 6 entidad pública (módulo 11 sobre 8, verificador en el 9.º + establecimiento de 4, no 0000),
 * 9 sociedad privada (módulo 11 sobre 9, verificador en el 10.º + establecimiento de 3, no 000).
 * Rechaza un error de tipeo; si el contribuyente existe sólo lo sabe el SRI.
 */
export function isValidEcRuc(ruc: string): boolean {
  if (!/^\d{13}$/.test(ruc)) return false;
  const province = Number(ruc.slice(0, 2));
  if (!((province >= 1 && province <= 24) || province === 30)) return false;
  const third = Number(ruc[2]);
  if (third <= 5) {
    const sum = EC_NATURAL_COEFFICIENTS.reduce((acc, coefficient, index) => {
      const product = Number(ruc[index]) * coefficient;
      return acc + (product > 9 ? product - 9 : product);
    }, 0);
    return (10 - (sum % 10)) % 10 === Number(ruc[9]) && ruc.slice(10) !== "000";
  }
  if (third === 6) {
    return ecMod11(ruc, EC_PUBLIC_WEIGHTS) === Number(ruc[8]) && ruc.slice(9) !== "0000";
  }
  if (third === 9) {
    return ecMod11(ruc, EC_PRIVATE_WEIGHTS) === Number(ruc[9]) && ruc.slice(10) !== "000";
  }
  return false;
}

/** La identificación del emisor según la regla del régimen. `FREE_TEXT`: no vacía. */
export function isValidTaxId(rule: TaxIdRule, value: string): boolean {
  const trimmed = value.trim();
  switch (rule) {
    case "PE_RUC":
      return isValidPeRuc(trimmed);
    case "EC_RUC":
      return isValidEcRuc(trimmed);
    default:
      return trimmed !== "" && trimmed.length <= 20;
  }
}

// ---------------------------------------------------------------- número del comprobante

/** El número fiscal en el formato del régimen; sin patrón (genérico), cualquier texto no vacío. */
export function isValidDocumentNumber(regime: TaxRegimeView, value: string): boolean {
  const trimmed = value.trim().toUpperCase();
  if (trimmed === "" || trimmed.length > 60) return false;
  const pattern = regime.documentNumber.pattern;
  if (!pattern) return true;
  try {
    return new RegExp(pattern).test(trimmed);
  } catch {
    // Un patrón que el navegador no entiende no frena el formulario: el servidor valida igual.
    return true;
  }
}

/** El código del país para una clase de comprobante; null en el régimen genérico. */
export function documentCodeOf(regime: TaxRegimeView | undefined, kind: DocumentKind): string | null {
  return regime?.documentKinds.find((option) => option.kind === kind)?.code ?? null;
}

// ---------------------------------------------------------------- importes

/** Redondeo a `decimals` lejos del cero en el medio, como `HALF_UP` en Java, sin errores de coma flotante. */
export function roundHalfUp(value: number, decimals: number): number {
  const factor = 10 ** decimals;
  const scaled = Math.abs(value) * factor;
  const rounded = Math.round(Number(scaled.toFixed(6)));
  return (Math.sign(value) * rounded) / factor;
}

/** El impuesto que pide un subtotal a una tasa, al céntimo. */
export function taxOf(subtotal: number, rate: number): number {
  return roundHalfUp((subtotal * rate) / 100, 2);
}

/** Si subtotal + impuesto coinciden con el total dentro de la tolerancia de redondeo. */
export function breakdownAddsUp(subtotal: number, tax: number, total: number, tolerance: number): boolean {
  return Math.abs(roundHalfUp(subtotal + tax - total, 2)) <= tolerance + 1e-9;
}

/** Si la tasa declarada es una de las del régimen; sin lista, cualquiera entre 0 y 100. */
export function isAllowedVatRate(regime: TaxRegimeView, rate: number): boolean {
  if (!Number.isFinite(rate) || rate < 0 || rate >= 100) return false;
  if (regime.allowedVatRates.length === 0) return true;
  return regime.allowedVatRates.some((allowed) => Math.abs(allowed - rate) < 0.001);
}

/** La tasa con que se adelanta un flete: la de flete del régimen, si no la general. */
export function previewVatRate(regime: TaxRegimeView): number | null {
  return regime.freightVatRate ?? regime.vatRate;
}

/** Si un documento en esta moneda tiene que traer su desglose. */
export function requiresBreakdown(regime: TaxRegimeView | undefined, currency: string): boolean {
  return Boolean(regime?.breakdownRequired && regime.fiscalCurrency
    && currency.trim().toUpperCase() === regime.fiscalCurrency);
}

// ---------------------------------------------------------------- retenciones

export interface WithholdingPreviewItem {
  kind: WithholdingKind;
  label: string;
  code: string | null;
  rate: number;
  base: WithholdingBase;
  baseAmount: number;
  amount: number;
}

export type WithholdingSkipReason = "disabled" | "below-threshold" | "no-base" | "excluded";

export type WithholdingPreview =
  | { status: "none"; reason: "currency" | "note" | "no-rules" }
  | {
    status: "computed";
    items: WithholdingPreviewItem[];
    skipped: { rule: WithholdingRuleView; reason: WithholdingSkipReason }[];
  };

export interface DocumentAmounts {
  subtotal: number | null;
  tax: number | null;
  total: number | null;
}

function baseAmountOf(base: WithholdingBase, amounts: DocumentAmounts): number | null {
  switch (base) {
    case "TOTAL":
      return amounts.total;
    case "SUBTOTAL":
      return amounts.subtotal;
    case "VAT":
      return amounts.tax;
  }
}

/**
 * Lo que el servidor calculará (la regla de `API_CONTRACT`/ADR-027): sólo una factura en la moneda
 * fiscal del régimen (el genérico, nunca); cada retención habilitada, en el orden del régimen,
 * sobre su base; se salta si la base falta o es cero, si el TOTAL no supera el umbral, o si ya
 * aplicó alguna de las que la excluyen. Importe = base × tasa / 100, HALF_UP a la escala de la regla.
 */
export function previewWithholdings(
  regime: TaxRegimeView, kind: DocumentKind, currency: string, amounts: DocumentAmounts,
): WithholdingPreview {
  if (!regime.fiscalCurrency || currency.trim().toUpperCase() !== regime.fiscalCurrency) {
    return { status: "none", reason: "currency" };
  }
  if (kind !== "INVOICE") return { status: "none", reason: "note" };
  if (regime.withholdings.length === 0) return { status: "none", reason: "no-rules" };

  const items: WithholdingPreviewItem[] = [];
  const skipped: { rule: WithholdingRuleView; reason: WithholdingSkipReason }[] = [];
  for (const rule of regime.withholdings) {
    if (!rule.enabled || rule.rate === null) {
      skipped.push({ rule, reason: "disabled" });
      continue;
    }
    if (rule.requiresNoOther.some((other) => items.some((item) => item.kind === other))) {
      skipped.push({ rule, reason: "excluded" });
      continue;
    }
    if (rule.thresholdAmount !== null) {
      const total = amounts.total ?? 0;
      const above = rule.strictlyAboveThreshold ? total > rule.thresholdAmount : total >= rule.thresholdAmount;
      if (!above) {
        skipped.push({ rule, reason: "below-threshold" });
        continue;
      }
    }
    const baseAmount = baseAmountOf(rule.base, amounts);
    if (baseAmount === null || !Number.isFinite(baseAmount) || baseAmount === 0) {
      skipped.push({ rule, reason: "no-base" });
      continue;
    }
    items.push({
      kind: rule.kind, label: rule.label, code: rule.code, rate: rule.rate, base: rule.base, baseAmount,
      amount: roundHalfUp((baseAmount * rule.rate) / 100, rule.scale),
    });
  }
  return { status: "computed", items, skipped };
}
