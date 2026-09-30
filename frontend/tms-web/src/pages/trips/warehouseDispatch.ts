import type { TripView } from "../../shared/api/planningApi";
import type {
  DispatchDocumentView, WarehouseMilestoneView,
} from "../../shared/api/warehouseApi";
import type { StatusTone } from "../../theme";

/**
 * Lo que la tarjeta "Despacho de almacén" deriva de la respuesta, fuera del componente para
 * poder probarlo y para que la tabla de seguimiento pinte el mismo color que el espacio de
 * trabajo. Nada de aquí decide una verificación: la decide el servidor (ADR-013 §5); aquí solo
 * se colorea y se ordena.
 */

/** Verde cuando coincide, rojo cuando hay diferencias, ámbar cuando alguien pasó por encima del
 * almacén, gris cuando todavía no hay nada que comparar. Un valor nuevo cae a gris. */
const VERIFICATION_TONE: Record<string, StatusTone> = {
  UNVERIFIED: "neutral",
  MATCHED: "done",
  MISMATCH: "overdue",
  OVERRIDDEN: "inProgress",
};

export function verificationTone(status: string | null | undefined): StatusTone {
  return (status && VERIFICATION_TONE[status]) || "neutral";
}

const SEVERITY_TONE: Record<string, StatusTone> = {
  ERROR: "overdue",
  WARNING: "inProgress",
  INFO: "open",
};

export function severityTone(severity: string | null | undefined): StatusTone {
  return (severity && SEVERITY_TONE[severity]) || "neutral";
}

const ORDER_MATCH_TONE: Record<string, StatusTone> = {
  MATCHED: "done",
  VARIANCE: "overdue",
  UNCOMPARABLE: "inProgress",
  EXTRA: "overdue",
  UNKNOWN: "neutral",
};

export function orderMatchTone(match: string | null | undefined): StatusTone {
  return (match && ORDER_MATCH_TONE[match]) || "neutral";
}

const OUTCOME_TONE: Record<string, StatusTone> = {
  APPLIED: "done",
  RECONCILED: "open",
  UNAPPLIED: "overdue",
  RECORDED_UNMATCHED: "cancelled",
};

export function outcomeTone(outcome: string | null | undefined): StatusTone {
  return (outcome && OUTCOME_TONE[outcome]) || "neutral";
}

/** Primero la revisión vigente de cada referencia, luego lo más reciente: la vigente es la que
 * cuenta para la verificación del envío y la que un despachador busca. */
export function sortDocuments(documents: DispatchDocumentView[]): DispatchDocumentView[] {
  return [...documents].sort((a, b) => {
    if (a.current !== b.current) return a.current ? -1 : 1;
    return b.receivedAt.localeCompare(a.receivedAt);
  });
}

/** Los hitos en el orden en que ocurrieron, que es como se leen en una línea de tiempo. */
export function sortMilestones(milestones: WarehouseMilestoneView[]): WarehouseMilestoneView[] {
  return [...milestones].sort((a, b) => a.occurredAt.localeCompare(b.occurredAt));
}

/** Cuenta las diferencias por severidad, para el resumen sobre la tabla. */
export function countBySeverity(document: DispatchDocumentView): Record<"ERROR" | "WARNING" | "INFO", number> {
  const counts = { ERROR: 0, WARNING: 0, INFO: 0 };
  for (const discrepancy of document.discrepancies) {
    if (discrepancy.severity === "ERROR" || discrepancy.severity === "WARNING" || discrepancy.severity === "INFO") {
      counts[discrepancy.severity] += 1;
    }
  }
  return counts;
}

export type PlanVsRealKey = "carrier" | "plate" | "driver" | "warehouse" | "time";

export interface PlanVsRealRow {
  key: PlanVsRealKey;
  label: string;
  planned: string | null;
  dispatched: string | null;
  /** Lo marca la presencia del código de discrepancia que corresponde, nunca una comparación
   * hecha aquí: el servidor normaliza placas y cruza códigos de transportista que el navegador
   * no conoce. */
  differs: boolean;
}

const DIFF_CODE: Record<PlanVsRealKey, string> = {
  carrier: "CARRIER_MISMATCH",
  plate: "VEHICLE_MISMATCH",
  driver: "DRIVER_MISMATCH",
  warehouse: "WAREHOUSE_MISMATCH",
  time: "DISPATCH_TIME",
};

/**
 * Plan y realidad lado a lado para los cinco datos que el almacén informa de la salida.
 * `formatTime` llega desde fuera para que el formato de fecha siga siendo el de `lib/locale`.
 */
export function planVsReal(
  trip: Pick<TripView, "carrierName" | "vehicleLicensePlate" | "driverName" | "originCode" | "originName" | "actualDepartureAt">,
  document: DispatchDocumentView,
  formatTime: (value: string) => string,
): PlanVsRealRow[] {
  const codes = new Set(document.discrepancies.map((discrepancy) => discrepancy.code));
  const row = (key: PlanVsRealKey, label: string, planned: string | null, dispatched: string | null): PlanVsRealRow => ({
    key, label, planned, dispatched, differs: codes.has(DIFF_CODE[key]),
  });
  const dispatchedCarrier = [document.carrierCode, document.carrierName].filter(Boolean).join(" · ") || null;
  return [
    row("carrier", "Transportista", trip.carrierName, dispatchedCarrier),
    row("plate", "Placa", trip.vehicleLicensePlate, document.vehicleLicensePlate),
    row("driver", "Conductor", trip.driverName, document.driverName),
    row("warehouse", "Almacén", trip.originCode ?? trip.originName, document.warehouseCode),
    row(
      "time", "Hora de salida",
      trip.actualDepartureAt ? formatTime(trip.actualDepartureAt) : null,
      document.actualDispatchAt ? formatTime(document.actualDispatchAt) : null,
    ),
  ];
}
