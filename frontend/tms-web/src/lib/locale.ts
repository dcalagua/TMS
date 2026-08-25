import { getLang } from "./i18n";

/**
 * Formato regional, construido sobre `Intl` para que el navegador sea el dueño de las reglas
 * en vez de que el código arrastre sus propios separadores y patrones de fecha.
 *
 * Aquí solo pasa la *presentación*. Los valores que van al backend conservan su forma
 * canónica: una fecha ISO sigue siendo ISO y un decimal sigue siendo punto decimal.
 */

const EMPTY = "-";

const LOCALES: Record<string, string> = { es: "es-ES", en: "en-GB" };

/** El locale BCP-47 del idioma activo de la interfaz. */
export const localeFor = (lang = getLang()): string => LOCALES[lang] ?? "es-ES";

/** Acepta tanto un instante ISO como un `yyyy-mm-dd` pelado, que es lo que el backend usa
 * para las fechas de plan. Una fecha pelada se lee en hora local para que no salte de día
 * al cruzar zonas horarias. */
function parseDate(value: string): Date | null {
  const bare = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  const date = bare
    ? new Date(Number(bare[1]), Number(bare[2]) - 1, Number(bare[3]))
    : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

function asDate(value: string | Date | null | undefined): Date | null {
  if (!value) return null;
  return value instanceof Date ? value : parseDate(value);
}

export function fmtDate(value: string | Date | null | undefined): string {
  const date = asDate(value);
  return date ? new Intl.DateTimeFormat(localeFor(), { dateStyle: "medium" }).format(date) : EMPTY;
}

export function fmtDateTime(value: string | Date | null | undefined): string {
  const date = asDate(value);
  return date
    ? new Intl.DateTimeFormat(localeFor(), { dateStyle: "medium", timeStyle: "short" }).format(date)
    : EMPTY;
}

/**
 * Solo la hora, para una pantalla cuya fecha ya se dice una vez arriba: una fila de la torre
 * de control que pone "14:00" en lugar de "21 ago 2026, 14:00" once veces en la misma columna.
 *
 * Se pinta en la zona horaria del *navegador*, como cualquier otro instante del producto. Eso
 * es lo correcto para un operador sentado en la zona del propio depósito, y conviene saberlo
 * cuando no lo está: el valor que viaja lleva su offset, y el backend ya hizo la única
 * conversión que decide un hecho de negocio (a qué día pertenece una ventana de servicio).
 */
export function fmtTime(value: string | Date | null | undefined): string {
  const date = asDate(value);
  return date ? new Intl.DateTimeFormat(localeFor(), { timeStyle: "short" }).format(date) : EMPTY;
}

export function fmtNumber(
  value: number | null | undefined,
  options: Intl.NumberFormatOptions = {},
): string {
  if (value === null || value === undefined || Number.isNaN(value)) return EMPTY;
  return new Intl.NumberFormat(localeFor(), options).format(value);
}

/** Un conteo simple: pallets, pedidos, paradas. Sin decimales. */
export const fmtQuantity = (value: number | null | undefined): string =>
  fmtNumber(value, { maximumFractionDigits: 0 });

export const fmtDecimal = (value: number | null | undefined, fractionDigits = 2): string =>
  fmtNumber(value, { minimumFractionDigits: 0, maximumFractionDigits: fractionDigits });

export const fmtWeightKg = (value: number | null | undefined): string =>
  fmtNumber(value, { style: "unit", unit: "kilogram", unitDisplay: "short", maximumFractionDigits: 2 });

/** `Intl` no tiene una unidad `cubic-meter` sancionada (ECMA-402 lista un set fijo), así que
 * se localiza el número y se le añade el símbolo SI, idéntico en los dos idiomas del producto. */
export function fmtVolumeM3(value: number | null | undefined): string {
  const amount = fmtNumber(value, { maximumFractionDigits: 2 });
  return amount === EMPTY ? EMPTY : `${amount} m³`;
}

/** `value` es un porcentaje ya expresado 0-100, como reporta el backend el uso de capacidad. */
export function fmtPercent(value: number | null | undefined, fractionDigits = 0): string {
  if (value === null || value === undefined || Number.isNaN(value)) return EMPTY;
  return new Intl.NumberFormat(localeFor(), { style: "percent", maximumFractionDigits: fractionDigits })
    .format(value / 100);
}

/** Importe con la moneda que indique el dato (los tarifarios llevan la suya). */
export function fmtMoney(value: number | null | undefined, currency = "PEN"): string {
  if (value === null || value === undefined || Number.isNaN(value)) return EMPTY;
  try {
    return new Intl.NumberFormat(localeFor(), { style: "currency", currency }).format(value);
  } catch {
    return `${currency} ${fmtDecimal(value)}`;
  }
}

/** Distancia en kilómetros, con el símbolo pegado al número localizado. */
export function fmtKm(value: number | null | undefined): string {
  const amount = fmtNumber(value, { maximumFractionDigits: 1 });
  return amount === EMPTY ? EMPTY : `${amount} km`;
}

/** Duración en minutos → "2 h 15 min" / "45 min". */
export function fmtMinutes(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return EMPTY;
  const total = Math.round(value);
  const h = Math.floor(total / 60);
  const m = total % 60;
  if (h === 0) return `${m} min`;
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}

/** El `yyyy-mm-dd` que esperan los endpoints con fecha de servicio o de plan. */
export function toIsoDate(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** Hoy en el formato que espera un `<input type="date">` y los filtros de fecha. */
export const today = (): string => toIsoDate(new Date());
