import type { FiscalSettingsView } from "../../shared/api/settlementApi";

/**
 * Las reglas fiscales peruanas que el formulario adelanta (V66, ADR-024 parte 2). Son las mismas
 * que aplica el servidor (`RucValidator`, `TaxCalculator`) y **no traen ningún porcentaje propio**:
 * la tasa de IGV, la detracción y la retención llegan resueltas de `/settlement/fiscal-settings`.
 * El servidor decide; esto sólo evita que alguien se entere al guardar.
 */

const RUC_WEIGHTS = [5, 4, 3, 2, 7, 6, 5, 4, 3, 2];

/** Dígito verificador del RUC, módulo 11 (10 → 0, 11 → 1). */
export function isValidRuc(ruc: string): boolean {
  if (!/^(10|15|17|20)\d{9}$/.test(ruc)) return false;
  const sum = RUC_WEIGHTS.reduce((acc, weight, index) => acc + Number(ruc[index]) * weight, 0);
  const digit = 11 - (sum % 11);
  const expected = digit === 10 ? 0 : digit === 11 ? 1 : digit;
  return expected === Number(ruc[10]);
}

/** Redondeo a `decimals` lejos del cero en el medio, como `HALF_UP` en Java, sin errores de coma flotante. */
export function roundHalfUp(value: number, decimals: number): number {
  const factor = 10 ** decimals;
  const scaled = Math.abs(value) * factor;
  const rounded = Math.round(Number(scaled.toFixed(6)));
  return (Math.sign(value) * rounded) / factor;
}

/** El IGV que pide un subtotal a una tasa, al céntimo. */
export function igvOf(subtotal: number, rate: number): number {
  return roundHalfUp((subtotal * rate) / 100, 2);
}

/** Si subtotal + IGV coinciden con el total dentro de la tolerancia de redondeo. */
export function breakdownAddsUp(subtotal: number, igv: number, total: number, tolerance: number): boolean {
  return Math.abs(roundHalfUp(subtotal + igv - total, 2)) <= tolerance + 1e-9;
}

export type TaxPreview =
  | { kind: "detraction"; code: string; rate: number; amount: number }
  | { kind: "retention"; rate: number; amount: number }
  | { kind: "none"; reason: "currency" | "note" | "below-threshold" | "disabled" };

/**
 * Lo que el servidor calculará: detracción (sólo PEN, sólo factura, estrictamente por encima del
 * umbral, en soles enteros) o, si no aplica y la empresa es agente, retención al céntimo.
 */
export function previewTaxes(
  settings: FiscalSettingsView, documentType: string, currency: string, total: number,
): TaxPreview {
  if (currency.toUpperCase() !== settings.currency) return { kind: "none", reason: "currency" };
  if (documentType !== "01") return { kind: "none", reason: "note" };
  const above = settings.detractionStrictlyAboveThreshold
    ? total > settings.detractionThresholdAmount
    : total >= settings.detractionThresholdAmount;
  if (settings.detractionEnabled && above) {
    return {
      kind: "detraction", code: settings.detractionCode, rate: settings.detractionRate,
      amount: roundHalfUp((total * settings.detractionRate) / 100, 0),
    };
  }
  if (settings.retentionAgent) {
    return { kind: "retention", rate: settings.retentionRate, amount: roundHalfUp((total * settings.retentionRate) / 100, 2) };
  }
  return { kind: "none", reason: settings.detractionEnabled ? "below-threshold" : "disabled" };
}
