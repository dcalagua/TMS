import { STATUS } from "../../theme";

/**
 * El color de una parada en los mapas de despachos, por el estado de sus documentos (ADR-018).
 *
 *  - `NONE`      gris:  la parada no lleva documentos que seguir;
 *  - `PROBLEM`   rojo:  algún documento rechazado o no entregado - alguien tiene que llamar;
 *  - `PENDING`   ámbar: queda alguno sin reportar;
 *  - `DELIVERED` verde: todos entregados.
 *
 * El rojo gana al ámbar a propósito: una parada con un rechazo y otro documento pendiente sigue
 * siendo la que merece la llamada.
 */
export type StopDocumentState = "NONE" | "PROBLEM" | "PENDING" | "DELIVERED";

export interface StopDocumentCounts {
  documentCount: number;
  pendingCount: number;
  deliveredCount?: number;
  rejectedCount?: number;
  notDeliveredCount?: number;
}

/**
 * Los rechazos y no entregas de una parada, o `null` si no se pueden saber.
 *
 * Con el desglose explícito se suman; con solo `deliveredCount`, lo que no está pendiente ni
 * entregado es un rechazo o una no entrega; sin ninguno de los dos, no se sabe.
 */
function problemCount(counts: StopDocumentCounts): number | null {
  if (counts.rejectedCount !== undefined || counts.notDeliveredCount !== undefined) {
    return (counts.rejectedCount ?? 0) + (counts.notDeliveredCount ?? 0);
  }
  if (counts.deliveredCount !== undefined) {
    return Math.max(0, counts.documentCount - counts.pendingCount - counts.deliveredCount);
  }
  return null;
}

/**
 * La regla de color. Pura, para probarla sin mapa.
 *
 * El contrato V1 trae por parada solo `documentCount` y `pendingCount`. Cuando falta el desglose,
 * `tripHasProblems` (el envío tiene algún rechazo o no entrega) decide: si el envío no tiene
 * ninguno, lo reportado en la parada está entregado y el verde es exacto; si lo tiene, una parada
 * con documentos ya reportados *puede* ser la del problema y se pinta en rojo. Mejor una llamada de
 * más que un verde falso: el panel del envío, que tiene cada documento, enseña el detalle exacto.
 */
export function stopDocumentState(
  counts: StopDocumentCounts, options: { tripHasProblems?: boolean } = {},
): StopDocumentState {
  if (counts.documentCount <= 0) return "NONE";
  const problems = problemCount(counts);
  const reported = counts.documentCount - counts.pendingCount;
  if (problems !== null ? problems > 0 : options.tripHasProblems === true && reported > 0) return "PROBLEM";
  if (counts.pendingCount > 0) return "PENDING";
  return "DELIVERED";
}

/** Los contadores de una parada desde los estados de sus documentos, cuando se tienen uno a uno. */
export function countsFromStatuses(statuses: string[]): Required<StopDocumentCounts> {
  const counts = { documentCount: statuses.length, pendingCount: 0, deliveredCount: 0, rejectedCount: 0, notDeliveredCount: 0 };
  for (const status of statuses) {
    if (status === "PENDING") counts.pendingCount += 1;
    else if (status === "DELIVERED") counts.deliveredCount += 1;
    else if (status === "REJECTED") counts.rejectedCount += 1;
    else if (status === "NOT_DELIVERED") counts.notDeliveredCount += 1;
  }
  return counts;
}

/**
 * Los colores de marcador: los tokens de estado del tema claro, porque el mapa base es claro en
 * los dos modos y sobre él son los que pasan contraste.
 */
export const STOP_STATE_COLOR: Record<StopDocumentState, string> = {
  NONE: STATUS.light.cancelled.bg,
  PROBLEM: STATUS.light.overdue.bg,
  PENDING: STATUS.light.inProgress.bg,
  DELIVERED: STATUS.light.done.bg,
};

/** Etiquetas en español; quien las pinta las pasa por `t()`. */
export const STOP_STATE_LABEL: Record<StopDocumentState, string> = {
  DELIVERED: "Documentos entregados",
  PENDING: "Documentos pendientes",
  PROBLEM: "Con rechazo o no entrega",
  NONE: "Sin documentos",
};

export const STOP_STATE_ORDER: StopDocumentState[] = ["DELIVERED", "PENDING", "PROBLEM", "NONE"];
