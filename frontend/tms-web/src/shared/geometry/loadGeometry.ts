import { t } from "../../lib/i18n";
import { fmtAreaM2, fmtDecimal, fmtMetres, fmtNumber, fmtQuantity, fmtVolumeM3, fmtWeightKg } from "../../lib/locale";

/**
 * Geometría de carga (V62, ADR-022) tal como la presenta la interfaz: apilables, anidables, pilas,
 * piso y alto. **Aquí no se calcula ninguna geometría**: las pilas, el volumen efectivo, el piso y
 * la altura los calcula el backend (`LoadGeometryCalculator`) y viajan en los DTO. Este módulo solo
 * redacta y clasifica lo que llegó, en funciones puras para poder probarlas sin pintar nada.
 */

// ── Capacidad: el tono de una barra ─────────────────────────────────────────────────────────

/** A partir de este % una dimensión avisa en ámbar. Solo cambia la presentación, nunca el veredicto. */
export const CAPACITY_NEAR_THRESHOLD = 85;

/** Los campos de `CapacityDimension` que deciden cómo se pinta una barra. */
export interface CapacityReading {
  percentUsed: number | null;
  exceeded: boolean;
  unlimited: boolean;
}

/**
 * - `unlimited`: no hay límite (sin vehículo, o el tipo no declara esa medida): barra apagada.
 * - `noPercent`: hay un límite pero no un porcentaje (límite cero): se dice con palabras.
 * - `over`: el backend dijo `exceeded`. Es su veredicto, no un cálculo de aquí.
 * - `near`: ≥ {@link CAPACITY_NEAR_THRESHOLD} % sin exceder.
 * - `ok`: el resto.
 */
export type CapacityTone = "unlimited" | "noPercent" | "ok" | "near" | "over";

export function capacityTone(dimension: CapacityReading): CapacityTone {
  if (dimension.exceeded) return "over";
  if (dimension.unlimited) return "unlimited";
  if (dimension.percentUsed === null) return "noPercent";
  return dimension.percentUsed >= CAPACITY_NEAR_THRESHOLD ? "near" : "ok";
}

/** El color MUI de cada tono, para las barras grandes y las miniatura por igual. */
export const CAPACITY_TONE_COLOR: Record<CapacityTone, "primary" | "warning" | "error" | "inherit"> = {
  unlimited: "inherit",
  noPercent: "inherit",
  ok: "primary",
  near: "warning",
  over: "error",
};

/**
 * Las cinco dimensiones de la capacidad (V62, ADR-022 §5): las tres de siempre más el piso (m²,
 * se suma) y el alto (la pila más alta contra el alto útil del vehículo: un máximo, nunca una suma).
 */
export type CapacityUnit = "weight" | "volume" | "pallets" | "floor" | "height";

/** La cantidad de una dimensión en su unidad. */
export function capacityAmount(kind: CapacityUnit, value: number | null | undefined): string {
  switch (kind) {
    case "weight": return fmtWeightKg(value);
    case "volume": return fmtVolumeM3(value);
    case "floor": return fmtAreaM2(value);
    case "height": return fmtMetres(value);
    default: return `${fmtDecimal(value)} ${t("pallets")}`;
  }
}

/**
 * El piso útil de un tipo de vehículo: largo × ancho × factor de llenado (ADR-022 §5). Null cuando
 * falta el largo o el ancho — para la planificación eso es piso **sin límite**, no cero.
 */
export function usableFloorAreaM2(
  lengthM: number | null | undefined, widthM: number | null | undefined, fillFactor: number | null | undefined,
): number | null {
  if (lengthM == null || widthM == null || fillFactor == null) return null;
  const l = Number(lengthM), w = Number(widthM), f = Number(fillFactor);
  if (!(l > 0) || !(w > 0) || !(f > 0)) return null;
  return l * w * f;
}

// ── Volumen efectivo frente a declarado ──────────────────────────────────────────────────────

/** El ahorro de volumen que la geometría reconoce. Null cuando no hay nada que comparar. */
export interface VolumeSaving {
  savedM3: number;
  /** 0-100, sobre el declarado. */
  percent: number;
}

/**
 * Cuánto menos ocupa por geometría que por la cifra declarada. Solo existe cuando las dos cifras se
 * conocen y la efectiva es menor: una efectiva mayor (cajas que no se apilan y declaran poco) no es
 * un "ahorro negativo", es la verdad del piso, y se muestra sin esa etiqueta.
 */
export function volumeSaving(
  effectiveM3: number | null | undefined, declaredM3: number | null | undefined,
): VolumeSaving | null {
  if (effectiveM3 == null || declaredM3 == null) return null;
  const effective = Number(effectiveM3);
  const declared = Number(declaredM3);
  if (!(declared > 0) || !(effective < declared)) return null;
  const savedM3 = declared - effective;
  return { savedM3, percent: Math.round((savedM3 / declared) * 100) };
}

/**
 * "0,15 m³ efectivos · 1,93 m³ declarados". Si las dos cifras se redondean igual, una sola:
 * repetir el mismo número dos veces sugiere una diferencia que no existe.
 */
export function volumeComparisonText(
  effectiveM3: number | null | undefined, declaredM3: number | null | undefined,
): string {
  const effective = fmtVolumeM3(effectiveM3);
  const declared = fmtVolumeM3(declaredM3);
  if (effectiveM3 == null) return t("{{declared}} declarados", { declared });
  if (declaredM3 == null || effective === declared) return t("{{effective}} efectivos", { effective });
  return t("{{effective}} efectivos · {{declared}} declarados", { effective, declared });
}

/** "Ahorra 1,78 m³ (92 %)" o null. */
export function volumeSavingText(
  effectiveM3: number | null | undefined, declaredM3: number | null | undefined,
): string | null {
  const saving = volumeSaving(effectiveM3, declaredM3);
  if (!saving) return null;
  return t("Ahorra {{saved}} ({{percent}} %)", { saved: fmtVolumeM3(saving.savedM3), percent: saving.percent });
}

// ── Lo que ocupa un pedido o una línea ──────────────────────────────────────────────────────

/** Las cifras de "Ocupa", del pedido entero o de una línea. Todas opcionales: llegan como lleguen. */
export interface OccupancyFigures {
  stacks?: number | null;
  floorAreaM2?: number | null;
  stackHeightM?: number | null;
}

/** "1 pila" / "10 pilas", con el número localizado. */
export function stacksText(stacks: number): string {
  return t(stacks === 1 ? "{{count}} pila" : "{{count}} pilas", { count: fmtQuantity(stacks) });
}

/** "10 pilas · 0,11 m² de piso · alto 1,36 m", omitiendo lo que no se conoce. */
export function occupancyText(figures: OccupancyFigures): string {
  const parts: string[] = [];
  if (figures.stacks != null && figures.stacks > 0) {
    parts.push(stacksText(figures.stacks));
  }
  if (figures.floorAreaM2 != null && Number(figures.floorAreaM2) > 0) {
    parts.push(t("{{area}} de piso", { area: fmtAreaM2(figures.floorAreaM2) }));
  }
  if (figures.stackHeightM != null) {
    parts.push(t("alto {{height}}", { height: fmtMetres(figures.stackHeightM) }));
  }
  return parts.join(" · ");
}

/**
 * En qué estado está la geometría de un pedido, leído de su cabecera:
 *
 * - `none`: ninguna línea trae bloque físico; se planifica por lo declarado, como siempre.
 * - `measured`: todas las líneas con bloque se midieron.
 * - `partial`: unas se midieron y otras no (las que no, cuentan con su volumen declarado).
 * - `unmeasured`: había bloque y no se pudo medir nada.
 *
 * Se mira la pila más alta para saber si algo se midió: el backend la deja null exactamente cuando
 * ninguna línea se midió (`max_stack_height_m`), y es el único campo sin cero por defecto.
 */
export type OrderGeometryState = "none" | "measured" | "partial" | "unmeasured";

export function orderGeometryState(geometry: {
  maxStackHeightM?: number | null;
  unmeasuredLines?: number | null;
} | null | undefined): OrderGeometryState {
  if (!geometry) return "none";
  const measured = geometry.maxStackHeightM != null;
  const unmeasured = (geometry.unmeasuredLines ?? 0) > 0;
  if (measured && unmeasured) return "partial";
  if (measured) return "measured";
  return unmeasured ? "unmeasured" : "none";
}

// ── Motivos ──────────────────────────────────────────────────────────────────────────────────

/**
 * Por qué una línea no tiene geometría (`geometry_reason`, `LOAD_GEOMETRY_V1.md`). Redactado como lo
 * que falta, para que quien lo lea sepa qué pedirle al almacén. Un código nuevo se muestra tal cual:
 * el catálogo es del backend y crece.
 */
const GEOMETRY_REASON_COPY: Record<string, string> = {
  MISSING_LENGTH: "Falta el largo de la unidad",
  MISSING_WIDTH: "Falta el ancho de la unidad",
  MISSING_HEIGHT: "Falta el alto de la unidad",
  MISSING_STACK_MODE: "No se declaró si se apila, se anida o ninguna",
  MISSING_NEST_INCREMENT: "Falta el incremento de anidado",
  MISSING_UNIT_WEIGHT: "Falta el peso unitario (hace falta para la carga máxima encima)",
  UOM_NOT_COUNTABLE: "La unidad de la línea no es un conteo (kg, m³…): no se convierte",
  QUANTITY_NOT_WHOLE: "La cantidad no es entera",
  UNBOUNDED_STACK: "Sin pila máxima ni alto de referencia: la pila no tiene tope",
  ITEM_TALLER_THAN_VEHICLE: "Una unidad es más alta que el alto de referencia",
};

export function geometryReasonText(reason: string | null | undefined): string | null {
  if (!reason) return null;
  const copy = GEOMETRY_REASON_COPY[reason];
  return copy ? t(copy) : reason;
}

/** Lo que se dice de una línea sin bloque físico: ni perfil del almacén ni medidas del ERP. */
export const NO_PHYSICAL_BLOCK_TEXT = "Sin perfil físico: cuenta su volumen declarado";

// ── Rechazos de capacidad ────────────────────────────────────────────────────────────────────

const DIMENSION_COPY: Record<string, string> = {
  weight: "El peso ({{used}}) supera la capacidad del vehículo ({{limit}}).",
  volume: "El volumen de planificación ({{used}}) supera la capacidad del vehículo ({{limit}}).",
  pallets: "Los pallets ({{used}}) superan la capacidad del vehículo ({{limit}}).",
  "floor area": "El piso ocupado ({{used}}) supera el piso útil del vehículo ({{limit}}).",
  "stack height": "La pila más alta ({{used}}) supera el alto útil del vehículo ({{limit}}).",
};

function amountWithUnit(value: string, unit: string): string {
  const n = Number(value);
  switch (unit) {
    case "kg": return fmtNumber(n, { style: "unit", unit: "kilogram", unitDisplay: "short", maximumFractionDigits: 2 });
    case "m3": return fmtVolumeM3(n);
    case "m2": return fmtAreaM2(n);
    case "m": return fmtMetres(n);
    case "pallets": return `${fmtNumber(n, { maximumFractionDigits: 2 })} ${t("pallets")}`;
    default: return `${value} ${unit}`;
  }
}

const SUBJECTS: [RegExp, (m: RegExpMatchArray) => string][] = [
  [/^Order (\S+) does not fit trip (\S+)$/, (m) => t("El pedido {{order}} no cabe en el viaje {{trip}}.", { order: m[1], trip: m[2] })],
  [/^Vehicle (\S+) cannot take what is already planned on trip (\S+)$/,
    (m) => t("El vehículo {{vehicle}} no puede llevar lo ya planificado en el viaje {{trip}}.", { vehicle: m[1], trip: m[2] })],
  [/^Trip (\S+) of planning run (\S+) cannot be confirmed$/,
    (m) => t("El viaje {{trip}} del plan {{plan}} no se puede confirmar.", { trip: m[1], plan: m[2] })],
  [/^(.+) cannot be confirmed$/, (m) => t("{{what}} no se puede confirmar.", { what: m[1] })],
];

const FAILURE = /^(weight|volume|pallets|floor area|stack height) (-?[\d.]+) (kg|m3|pallets|m2|m) exceeds the capacity of (-?[\d.]+) (kg|m3|pallets|m2|m)$/;
const TALLER = /ITEM_TALLER_THAN_VEHICLE - a unit (-?[\d.]+) m tall exceeds the vehicle height of (-?[\d.]+) m/;

/**
 * Traduce a castellano el rechazo de capacidad que escribe el backend
 * (`PlanningCapacityService.requireWithinCapacity` y `TripService.requireUnitsFit`): una frase por
 * dimensión que no cupo, con sus cifras, y la de una unidad más alta que el vehículo.
 *
 * Devuelve null si el texto no tiene esa forma — quien llama muestra entonces el `detail` tal cual
 * (`describePlanningError`), que es el contrato de las pantallas de planificación. Se lee el
 * `detail` y no un `code` porque el rechazo llega como `conflict` genérico; si el backend un día
 * cambia la redacción, esto cae al texto original en vez de inventar uno.
 */
export function describeCapacityRefusal(detail: string | null | undefined): string | null {
  if (!detail) return null;

  const taller = TALLER.exec(detail);
  if (taller) {
    const subject = describeSubject(detail.slice(0, detail.indexOf(":")));
    const sentence = t(
      "Una unidad mide {{unit}} de alto y el vehículo admite {{vehicle}}: no entra aunque se reparta o se reapile. Hace falta un vehículo más alto.",
      { unit: fmtMetres(Number(taller[1])), vehicle: fmtMetres(Number(taller[2])) },
    );
    return [subject, sentence].filter(Boolean).join(" ");
  }

  const colon = detail.indexOf(": ");
  if (colon < 0) return null;
  const failures = detail.slice(colon + 2).replace(/\.\s*$/, "").split("; ");
  const sentences: string[] = [];
  for (const failure of failures) {
    const m = FAILURE.exec(failure.trim());
    if (!m) return null;
    sentences.push(t(DIMENSION_COPY[m[1]], { used: amountWithUnit(m[2], m[3]), limit: amountWithUnit(m[4], m[5]) }));
  }
  if (sentences.length === 0) return null;
  const subject = describeSubject(detail.slice(0, colon));
  return [subject, ...sentences].filter(Boolean).join(" ");
}

function describeSubject(subject: string): string | null {
  for (const [pattern, render] of SUBJECTS) {
    const m = pattern.exec(subject.trim());
    if (m) return render(m);
  }
  return null;
}
