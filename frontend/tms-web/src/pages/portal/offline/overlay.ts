import type {
  PortalDocument, PortalIncident, PortalStop, PortalStopOrder, PortalTripDetail,
} from "../../../shared/api/portalApi";
import { STOP_TRANSITION_TARGET } from "../../../shared/api/portalApi";
import type { StopExecutionStatus } from "../../../shared/api/planningApi";
import {
  byCreatedAt, isDocumentEntry, type DocumentOutboxEntry, type ExecutionOutboxEntry, type OutboxEntry,
} from "./types";

/**
 * Lo que el transportista ve mientras su reporte espera en la cola: el documento ya con el
 * estado que eligió, marcado «Pendiente de sincronizar». Sin esto, quien reporta sin señal ve
 * su documento aún «Pendiente» y lo reporta otra vez.
 *
 * Lo mismo para las acciones de las paradas (V70): la parada ya «En el punto», el pedido ya con
 * su resultado y la incidencia ya en la lista, cada una marcada hasta que el servidor la tenga.
 */

/** Un documento tal y como se enseña: el del servidor, más lo que dice la cola. */
export interface ShownDocument extends PortalDocument {
  /** Hay un reporte suyo en cola (PENDING o SENDING); el estado enseñado es ese. */
  pendingSync: boolean;
  /** Su último reporte en cola fue rechazado por el servidor; el estado enseñado es el del servidor. */
  syncFailed: boolean;
}

/** Un pedido de una parada tal y como se enseña (V70). */
export interface ShownOrder extends PortalStopOrder {
  pendingSync: boolean;
  syncFailed: boolean;
}

/** Una parada tal y como se enseña: `pendingSync` cuando su estado viene de la cola (V70). */
export interface ShownStop extends Omit<PortalStop, "documents" | "orders"> {
  documents: ShownDocument[];
  orders: ShownOrder[];
  pendingSync: boolean;
  syncFailed: boolean;
}

export interface ShownIncident extends PortalIncident {
  pendingSync: boolean;
}

export interface ShownTripDetail extends Omit<PortalTripDetail, "stops" | "incidents"> {
  stops: ShownStop[];
  incidents: ShownIncident[];
}

/**
 * La tabla de transiciones de una parada, la del servidor (`StopExecutionStatus.java`). Solo se
 * usa para lo que todavía no llegó al servidor: lo que este devuelve manda siempre.
 */
const STOP_TRANSITIONS: Record<StopExecutionStatus, StopExecutionStatus[]> = {
  PENDING: ["ARRIVED", "SKIPPED", "FAILED"],
  ARRIVED: ["IN_SERVICE", "COMPLETED", "FAILED"],
  IN_SERVICE: ["COMPLETED", "FAILED"],
  COMPLETED: [],
  SKIPPED: [],
  FAILED: [],
};

/**
 * Aplica las entradas de la cola de este viaje sobre su detalle. Por documento cuenta la más
 * reciente: es la que el servidor acabará teniendo. Los contadores del viaje se recalculan con
 * lo enseñado para que la barra de progreso no contradiga la lista. Las acciones de las paradas
 * se aplican en el orden en que se hicieron, como saldrán.
 */
export function applyOutboxOverlay(detail: PortalTripDetail, entries: OutboxEntry[]): ShownTripDetail {
  const queued = new Map<string, DocumentOutboxEntry>();
  const failed = new Set<string>();
  const executions: ExecutionOutboxEntry[] = [];
  const failedExecutions: ExecutionOutboxEntry[] = [];
  for (const entry of [...entries].sort(byCreatedAt)) {
    if (entry.tripId !== detail.trip.tripId) continue;
    if (!isDocumentEntry(entry)) {
      (entry.state === "FAILED" ? failedExecutions : executions).push(entry);
      continue;
    }
    if (entry.state === "FAILED") failed.add(entry.documentId);
    else queued.set(entry.documentId, entry);
  }

  let changed = false;
  let stops: ShownStop[] = detail.stops.map((stop) => ({
    ...stop,
    orders: (stop.orders ?? []).map((order) => ({ ...order, pendingSync: false, syncFailed: false })),
    pendingSync: false,
    syncFailed: false,
    documents: stop.documents.map((doc): ShownDocument => {
      const entry = queued.get(doc.documentId);
      if (!entry || !doc.editable) {
        return { ...doc, pendingSync: false, syncFailed: failed.has(doc.documentId) && !entry };
      }
      changed = true;
      return {
        ...doc,
        status: entry.status,
        reasonCode: entry.reasonCode,
        remark: entry.remark,
        reportedAt: entry.reportedAt,
        pendingSync: true,
        syncFailed: false,
      };
    }),
  }));
  const incidents: ShownIncident[] = (detail.incidents ?? []).map((incident) => ({ ...incident, pendingSync: false }));

  const onTheRoad = detail.trip.status === "IN_TRANSIT";
  for (const entry of executions) {
    const action = entry.action;
    if (action.type === "STOP") {
      const target = STOP_TRANSITION_TARGET[action.transition];
      stops = stops.map((stop) => stop.stopId !== action.stopId ? stop : {
        ...stop,
        executionStatus: target,
        allowedExecutionTransitions: onTheRoad ? STOP_TRANSITIONS[target] : [],
        actualArrivalAt: action.transition === "arrive" ? action.occurredAt : stop.actualArrivalAt,
        serviceStartedAt: action.transition === "service" ? action.occurredAt : stop.serviceStartedAt,
        actualDepartureAt: action.transition === "complete" ? action.occurredAt : stop.actualDepartureAt,
        pendingSync: true,
      });
    } else if (action.type === "DELIVERY") {
      const input = action.input;
      stops = stops.map((stop) => stop.stopId !== action.stopId ? stop : {
        ...stop,
        orders: stop.orders.map((order) => order.orderId !== action.orderId ? order : {
          ...order,
          pendingSync: true,
          syncFailed: false,
          delivery: {
            id: order.delivery?.id ?? `pending-${entry.id}`,
            result: input.result,
            deliveredAt: input.deliveredAt ?? null,
            receiverName: input.receiverName ?? null,
            receiverDocument: input.receiverDocument ?? null,
            notes: input.notes ?? null,
            source: "CARRIER_PORTAL",
            recordedAt: entry.reportedAt,
            evidenceCount: (order.delivery?.evidenceCount ?? 0) + input.photos.length + (input.signature ? 1 : 0),
            refusedWeightKg: input.refusedWeightKg ?? null,
            refusedVolumeM3: input.refusedVolumeM3 ?? null,
            refusedPallets: input.refusedPallets ?? null,
          },
        }),
      });
    } else {
      const stop = stops.find((candidate) => candidate.stopId === action.tripStopId);
      incidents.push({
        id: `pending-${entry.id}`, tripStopId: action.tripStopId, stopSequence: stop?.sequence ?? null,
        exceptionType: action.exceptionType, status: "OPEN", reportedAt: action.occurredAt,
        notes: action.notes ?? null, resolvedAt: null, pendingSync: true,
      });
    }
  }
  for (const entry of failedExecutions) {
    const action = entry.action;
    if (action.type === "INCIDENT") continue;
    stops = stops.map((stop) => {
      if (stop.stopId !== action.stopId || stop.pendingSync) return stop;
      if (action.type === "STOP") return { ...stop, syncFailed: true };
      return {
        ...stop,
        orders: stop.orders.map((order) => order.orderId === action.orderId && !order.pendingSync
          ? { ...order, syncFailed: true } : order),
      };
    });
  }

  if (!changed) return { ...detail, stops, incidents };

  const documents = stops.flatMap((stop) => stop.documents);
  const count = (status: PortalDocument["status"]) => documents.filter((doc) => doc.status === status).length;
  return {
    ...detail,
    trip: {
      ...detail.trip,
      pendingCount: count("PENDING"),
      deliveredCount: count("DELIVERED"),
      rejectedCount: count("REJECTED"),
      notDeliveredCount: count("NOT_DELIVERED"),
    },
    stops,
    incidents,
  };
}

/** Cuántos reportes esperan en cola por viaje, para el chip de cada tarjeta de la lista. */
export function queuedByTrip(entries: OutboxEntry[], companyId: string): Map<string, number> {
  const counts = new Map<string, number>();
  for (const entry of entries) {
    if (entry.companyId !== companyId || entry.state === "FAILED") continue;
    counts.set(entry.tripId, (counts.get(entry.tripId) ?? 0) + 1);
  }
  return counts;
}

/** El siguiente documento por reportar, en el orden de las paradas, empezando tras `afterDocumentId`. */
export function nextPendingDocument(detail: ShownTripDetail, afterDocumentId?: string | null): ShownDocument | null {
  const documents = detail.stops.flatMap((stop) => stop.documents);
  const pending = (doc: ShownDocument) => doc.status === "PENDING" && doc.editable && doc.documentId !== afterDocumentId;
  const start = afterDocumentId ? documents.findIndex((doc) => doc.documentId === afterDocumentId) : -1;
  return documents.slice(start + 1).find(pending) ?? documents.slice(0, Math.max(0, start)).find(pending) ?? null;
}
