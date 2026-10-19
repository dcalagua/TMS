import { fmtNumber } from "../../lib/locale";
import { t } from "../../lib/i18n";

/**
 * El margen entre lo que el operador declara y lo que suman las líneas.
 *
 * Vive aparte del formulario para poder probarse sin montarlo, y porque es una regla del dominio
 * que el formulario sólo muestra: la decisión está en `OrderTotals` (backend), y esto es su
 * reflejo.
 *
 * <b>La regla, literal:</b>
 *
 *     |declarado - calculado| > calculado.abs() * 0.01   ->  rechazo
 *
 * Es un `>`, así que los dos extremos del intervalo son válidos.
 */

/** El mismo 1% que `OrderTotals.TOLERANCE_PERCENT`. */
export const TOLERANCE = 0.01;

/**
 * El intervalo que el backend acepta para una medida, o `null` si las líneas no la dicen.
 *
 * `null` no es "cero": significa que ninguna línea nombró esa medida y que, por tanto, el backend
 * no va a contrastar nada. Anunciar un intervalo en ese caso sería inventarse una regla.
 */
export function allowedRange(calculated: number | null): { min: number; max: number } | null {
  if (calculated === null) return null;
  const margin = Math.abs(calculated) * TOLERANCE;
  return { min: calculated - margin, max: calculated + margin };
}

/**
 * El margen escrito para que se lea sin hacer cuentas: «±90 · entre 8.910 y 9.090».
 *
 * Se muestran el margen y los dos extremos, no sólo el porcentaje: «1%» obliga a quien teclea a
 * multiplicar, y es justo el momento en que no lo va a hacer.
 */
export function marginHint(calculated: number | null, digits: number): string | undefined {
  const range = allowedRange(calculated);
  if (range === null) return undefined;
  const n = (value: number) => fmtNumber(value, { maximumFractionDigits: digits });
  const margin = (range.max - range.min) / 2;
  // Un total de cero no admite desvío, y decir "entre 0 y 0" se lee como un error de la pantalla.
  if (margin === 0) {
    return t("Las líneas suman {{value}}: hay que declarar exactamente eso.", { value: n(range.min) });
  }
  return t("±{{margin}} · entre {{min}} y {{max}}", { margin: n(margin), min: n(range.min), max: n(range.max) });
}
