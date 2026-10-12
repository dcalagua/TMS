import type { PortalDocument, PortalTripDetail } from "../../../shared/api/portalApi";
import { byCreatedAt, type OutboxEntry } from "./types";

/**
 * Lo que el transportista ve mientras su reporte espera en la cola: el documento ya con el
 * estado que eligió, marcado «Pendiente de sincronizar». Sin esto, quien reporta sin señal ve
 * su documento aún «Pendiente» y lo reporta otra vez.
 */

/** Un documento tal y como se enseña: el del servidor, más lo que dice la cola. */
export interface ShownDocument extends PortalDocument {
  /** Hay un reporte suyo en cola (PENDING o SENDING); el estado enseñado es ese. */
  pendingSync: boolean;
  /** Su último reporte en cola fue rechazado por el servidor; el estado enseñado es el del servidor. */
  syncFailed: boolean;
}

export interface ShownTripDetail extends Omit<PortalTripDetail, "stops"> {
  stops: (Omit<PortalTripDetail["stops"][number], "documents"> & { documents: ShownDocument[] })[];
}

/**
 * Aplica las entradas de la cola de este viaje sobre su detalle. Por documento cuenta la más
 * reciente: es la que el servidor acabará teniendo. Los contadores del viaje se recalculan con
 * lo enseñado para que la barra de progreso no contradiga la lista.
 */
export function applyOutboxOverlay(detail: PortalTripDetail, entries: OutboxEntry[]): ShownTripDetail {
  const queued = new Map<string, OutboxEntry>();
  const failed = new Set<string>();
  for (const entry of [...entries].sort(byCreatedAt)) {
    if (entry.tripId !== detail.trip.tripId) continue;
    if (entry.state === "FAILED") failed.add(entry.documentId);
    else queued.set(entry.documentId, entry);
  }

  let changed = false;
  const stops = detail.stops.map((stop) => ({
    ...stop,
    documents: stop.documents.map((doc): ShownDocument => {
      const entry = queued.get(doc.documentId);
      if (!entry || !doc.editable) return { ...doc, pendingSync: false, syncFailed: failed.has(doc.documentId) && !entry };
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

  if (!changed) return { ...detail, stops };

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
