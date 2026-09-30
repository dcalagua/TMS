import {
  fetchControlTowerTrips, type ControlTowerBoardParams, type ControlTowerTripView,
} from "../../shared/api/controlTowerApi";
import type { DeliveryResult, TripDetailView } from "../../shared/api/planningApi";

/**
 * Seguimiento de reparto: lo que la pantalla calcula, fuera del componente para probarlo.
 *
 * No hay aquí ningún estado nuevo. La fuente es el tablero de la torre de control
 * (`/monitoring/control-tower/trips`), que ya trae por fila el progreso de paradas y las
 * incidencias abiertas; el detalle de entregas por pedido sale del detalle del viaje, que se pide
 * solo al abrir una fila.
 */

/** Tamaño de página al traer el tablero: el máximo que acepta el backend (`PageQuery.MAX_SIZE`). */
export const BOARD_PAGE_SIZE = 200;

/** Tope de páginas: 1.000 envíos en un día es más del triple del objetivo de escala. Si se
 * alcanza, la pantalla lo dice en lugar de fingir que lo tiene todo. */
export const BOARD_MAX_PAGES = 5;

export interface BoardSnapshot {
  rows: ControlTowerTripView[];
  totalElements: number;
  /** Hay más envíos de los que se trajeron: los KPI son de los primeros `rows.length`. */
  truncated: boolean;
}

/**
 * El tablero entero del día para los filtros dados, en unas pocas páginas grandes y no en una
 * por cada pantalla de la tabla: los KPI tienen que ser del día y no de la página visible, y 300
 * envíos caben en dos peticiones.
 */
export async function fetchBoardSnapshot(
  params: Omit<ControlTowerBoardParams, "page" | "size">,
  fetchPage: typeof fetchControlTowerTrips = fetchControlTowerTrips,
): Promise<BoardSnapshot> {
  const rows: ControlTowerTripView[] = [];
  let totalElements = 0;
  for (let page = 0; page < BOARD_MAX_PAGES; page += 1) {
    const response = await fetchPage({ ...params, page, size: BOARD_PAGE_SIZE });
    rows.push(...response.content);
    totalElements = response.totalElements;
    if (rows.length >= totalElements || response.content.length === 0) break;
  }
  return { rows, totalElements, truncated: rows.length < totalElements };
}

export interface TrackingKpis {
  tripsInTransit: number;
  stopsTotal: number;
  /** Completadas, omitidas o fallidas: la parada ya no espera a nadie. */
  stopsResolved: number;
  stopsPending: number;
  stopsPastWindow: number;
  openExceptions: number;
  /** Porcentaje de paradas resueltas, o null si no hay paradas (un 0% diría que no se avanzó). */
  progressPercent: number | null;
}

export function aggregateKpis(rows: ControlTowerTripView[]): TrackingKpis {
  let tripsInTransit = 0;
  let stopsTotal = 0;
  let stopsResolved = 0;
  let stopsPastWindow = 0;
  let openExceptions = 0;
  for (const row of rows) {
    if (row.trip.status === "IN_TRANSIT") tripsInTransit += 1;
    stopsTotal += row.stopsTotal;
    stopsResolved += Math.min(row.stopsResolved, row.stopsTotal);
    stopsPastWindow += row.stopsPastWindow;
    openExceptions += row.openExceptions;
  }
  return {
    tripsInTransit,
    stopsTotal,
    stopsResolved,
    stopsPending: stopsTotal - stopsResolved,
    stopsPastWindow,
    openExceptions,
    progressPercent: stopsTotal === 0 ? null : Math.round((stopsResolved / stopsTotal) * 100),
  };
}

/** El progreso de una fila, 0-100, o null para un viaje sin paradas. */
export function stopProgress(row: Pick<ControlTowerTripView, "stopsTotal" | "stopsResolved">): number | null {
  if (row.stopsTotal <= 0) return null;
  return Math.round((Math.min(row.stopsResolved, row.stopsTotal) / row.stopsTotal) * 100);
}

/** Búsqueda local por envío, placa, conductor o transportista sobre lo ya traído. */
export function matchesSearch(row: ControlTowerTripView, search: string): boolean {
  const needle = search.trim().toLowerCase();
  if (needle === "") return true;
  const { trip } = row;
  return [trip.shipmentNumber, trip.vehicleLicensePlate, trip.driverName, trip.carrierName, trip.routeName]
    .some((value) => value?.toLowerCase().includes(needle));
}

export interface DeliverySummary {
  /** Pedidos asignados al viaje. */
  orders: number;
  delivered: number;
  partial: number;
  /** Rechazados o fallidos: alguien tiene que llamar al cliente. */
  notDelivered: number;
  notAttempted: number;
  /** Sin entrega registrada todavía. No es un estado del backend: es la ausencia de registro. */
  unrecorded: number;
}

/**
 * Qué pasó con cada pedido del viaje, desde el detalle. Una entrega por (parada, pedido): se
 * cuenta por pedido asignado, y el que no tiene registro queda como "sin registrar", sin
 * inventarle un estado.
 */
export function deliverySummary(detail: Pick<TripDetailView, "assignments" | "deliveries">): DeliverySummary {
  const resultByOrder = new Map<string, DeliveryResult>();
  for (const delivery of detail.deliveries) resultByOrder.set(delivery.orderId, delivery.result);
  const orderIds = new Set(detail.assignments.map((assignment) => assignment.orderId));
  const summary: DeliverySummary = { orders: orderIds.size, delivered: 0, partial: 0, notDelivered: 0, notAttempted: 0, unrecorded: 0 };
  for (const orderId of orderIds) {
    const result = resultByOrder.get(orderId);
    if (result === undefined) summary.unrecorded += 1;
    else if (result === "DELIVERED") summary.delivered += 1;
    else if (result === "PARTIAL") summary.partial += 1;
    else if (result === "REJECTED" || result === "FAILED") summary.notDelivered += 1;
    else summary.notAttempted += 1;
  }
  return summary;
}
