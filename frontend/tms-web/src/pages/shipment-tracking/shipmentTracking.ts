import type { DocumentDeliveryRow } from "../../shared/api/documentDeliveriesApi";
import type { TripAssignmentView, TripStopView } from "../../shared/api/planningApi";
import type {
  ShipmentDocumentsFilter, ShipmentTrackingQuery, ShipmentTrackingRow, ShipmentTrackingStop,
} from "../../shared/api/shipmentTrackingApi";
import type { OverviewMapPoint } from "../../shared/maps/ShipmentOverviewMap";
import { countsFromStatuses, stopDocumentState, type StopDocumentState } from "../../shared/maps/shipmentMarkers";
import { today } from "../../lib/locale";

/**
 * Seguimiento de despachos (ADR-019 §2): lo que la pantalla calcula, fuera del componente para
 * probarlo. Ningún estado nuevo: todo sale del despacho del almacén (ADR-013), del avance de
 * paradas y de los documentos de ADR-018 tal como los devuelve el servidor.
 */

export const shipmentTrackingKey = (companyId: string) => ["shipment-tracking", companyId] as const;

/** Los filtros de la pantalla, todos texto: `""` es «sin filtro». */
export interface ShipmentFilters {
  q: string;
  dateFrom: string;
  dateTo: string;
  carrierId: string;
  status: string;
  documents: ShipmentDocumentsFilter | "";
}

/** Hoy: lo que salió hoy del almacén. */
export function defaultShipmentFilters(): ShipmentFilters {
  const day = today();
  return { q: "", dateFrom: day, dateTo: day, carrierId: "", status: "", documents: "" };
}

/** Los filtros como parámetros de la API, sin los vacíos. */
export function toQueryParams(filters: ShipmentFilters): ShipmentTrackingQuery {
  return {
    dateFrom: filters.dateFrom || undefined,
    dateTo: filters.dateTo || undefined,
    carrierId: filters.carrierId || undefined,
    status: filters.status || undefined,
    documents: filters.documents || undefined,
    q: filters.q.trim() || undefined,
  };
}

/** El color de una parada en el mapa de conjunto, con el envío como contexto (ver `stopDocumentState`). */
export function stopStateInRow(row: Pick<ShipmentTrackingRow, "rejectedCount" | "notDeliveredCount">, stop: ShipmentTrackingStop): StopDocumentState {
  return stopDocumentState(stop, { tripHasProblems: row.rejectedCount + row.notDeliveredCount > 0 });
}

/** Porcentaje 0-100, o null cuando no hay nada que contar (un 0% diría que no se avanzó). */
export function percent(part: number, total: number): number | null {
  if (total <= 0) return null;
  return Math.round((Math.min(Math.max(part, 0), total) / total) * 100);
}

/**
 * Los puntos del mapa de conjunto: un origen por coordenada (varios envíos salen del mismo almacén
 * y un solo cuadrado basta) y una parada por parada de cada envío listado.
 */
export function overviewPoints(rows: ShipmentTrackingRow[]): OverviewMapPoint[] {
  const origins = new Map<string, OverviewMapPoint & { trips: Set<string> }>();
  const stops: OverviewMapPoint[] = [];
  for (const row of rows) {
    if (row.originLatitude !== null && row.originLongitude !== null) {
      const key = `${row.originLatitude},${row.originLongitude}`;
      const existing = origins.get(key);
      if (existing) existing.trips.add(row.tripId);
      else origins.set(key, {
        id: `origin:${key}`, kind: "origin", groupId: row.tripId, groupLabel: row.originName ?? "",
        latitude: row.originLatitude, longitude: row.originLongitude, title: row.originName ?? "", trips: new Set([row.tripId]),
      });
    }
    for (const stop of row.stops) {
      stops.push({
        id: `${row.tripId}:${stop.stopId}`,
        kind: "stop",
        groupId: row.tripId,
        groupLabel: row.shipmentNumber,
        latitude: stop.latitude,
        longitude: stop.longitude,
        title: `${row.shipmentNumber} · ${stop.sequence}. ${stop.destinationName ?? stop.address ?? ""}`.trim(),
        label: String(stop.sequence),
        state: stopStateInRow(row, stop),
      });
    }
  }
  const originPoints = [...origins.values()].map(({ trips, ...point }) => ({
    ...point,
    // Un origen de un solo envío lo selecciona; uno compartido no elige por nadie.
    groupId: trips.size === 1 ? point.groupId : null,
  }));
  return [...originPoints, ...stops];
}

/** Las paradas de un envío y sus documentos, en el orden de la ruta. */
export interface StopDocuments {
  stop: ShipmentTrackingStop;
  documents: DocumentDeliveryRow[];
}

export interface DocumentsByStop {
  stops: StopDocuments[];
  /** Documentos cuyo pedido no se pudo situar en ninguna parada (o sin detalle del viaje). */
  unassigned: DocumentDeliveryRow[];
}

/**
 * Sitúa cada documento en su parada: documento → pedidos (`orderNumbers`) → destino de la
 * asignación del pedido en el viaje → la parada de ese destino. Es el mismo camino que usa el
 * portal (pedido → parada) con lecturas que la oficina ya tiene. Un documento que cubre pedidos de
 * varias paradas va a la primera de la ruta; uno que no casa con ninguna, o todos cuando no hay
 * detalle del viaje, quedan en `unassigned` y se enseñan igual.
 */
export function groupDocumentsByStop(
  stops: ShipmentTrackingStop[],
  documents: DocumentDeliveryRow[],
  detail: { assignments: Pick<TripAssignmentView, "orderNumber" | "destinationId">[]; stops: Pick<TripStopView, "id" | "destinationId">[] } | null,
): DocumentsByStop {
  const ordered = [...stops].sort((a, b) => a.sequence - b.sequence);
  const result: DocumentsByStop = { stops: ordered.map((stop) => ({ stop, documents: [] })), unassigned: [] };
  if (!detail) {
    result.unassigned = [...documents];
    return result;
  }
  const destinationByOrder = new Map(detail.assignments.map((a) => [a.orderNumber, a.destinationId]));
  const stopIdByDestination = new Map(detail.stops.map((s) => [s.destinationId, s.id]));
  const slotByStopId = new Map(result.stops.map((slot) => [slot.stop.stopId, slot]));

  for (const document of documents) {
    const slots = document.orderNumbers
      .map((orderNumber) => destinationByOrder.get(orderNumber))
      .map((destinationId) => (destinationId ? stopIdByDestination.get(destinationId) : undefined))
      .map((stopId) => (stopId ? slotByStopId.get(stopId) : undefined))
      .filter((slot): slot is StopDocuments => slot !== undefined)
      .sort((a, b) => a.stop.sequence - b.stop.sequence);
    if (slots.length === 0) result.unassigned.push(document);
    else slots[0].documents.push(document);
  }
  return result;
}

/**
 * El color exacto de una parada cuando se tienen sus documentos uno a uno. Solo si están todos los
 * que el servidor cuenta en ella: con una parte, se vuelve a la regla de los contadores.
 */
export function stopStateFromDocuments(stop: ShipmentTrackingStop, documents: DocumentDeliveryRow[] | null, tripHasProblems: boolean): StopDocumentState {
  if (documents === null || documents.length === 0 || documents.length !== stop.documentCount) {
    return stopDocumentState(stop, { tripHasProblems });
  }
  return stopDocumentState(countsFromStatuses(documents.map((d) => d.status)));
}
