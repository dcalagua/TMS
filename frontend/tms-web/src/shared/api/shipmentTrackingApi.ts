import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

//
// Seguimiento de despachos (ADR-019 §2): los envíos que el almacén despachó de verdad - el
// despacho de ADR-013 - con su avance de paradas y el estado de sus documentos (ADR-018). Solo
// lectura, permiso `monitoring.transport:read`, como el resto del monitoreo. Las posiciones de un
// envío siguen saliendo de `trackingApi` (ADR-007) y no mueven nada.
//

/** Cómo compara lo que salió con el plan (ADR-013 §5). Lo decide el servidor. */
export type DispatchVerification = 'UNVERIFIED' | 'MATCHED' | 'MISMATCH' | 'OVERRIDDEN'

/** `PENDING` = con documentos pendientes; `COMPLETE` = ninguno pendiente. Omitido = todos. */
export type ShipmentDocumentsFilter = 'PENDING' | 'COMPLETE'

/** Mirrors the backend's `ShipmentTrackingStopView`. */
export interface ShipmentTrackingStop {
  stopId: string
  sequence: number
  destinationName: string | null
  address: string | null
  /** Null together when the destination was never geocoded: no marker, never an invented point. */
  latitude: number | null
  longitude: number | null
  executionStatus: string
  documentCount: number
  pendingCount: number
  /**
   * Desglose opcional por parada. El contrato V1 solo trae `documentCount` y `pendingCount`; si el
   * servidor añade estos campos, el mapa distingue además la parada con un rechazo o una no
   * entrega. Su ausencia nunca se lee como "todo entregado": ver `stopDocumentState`.
   */
  deliveredCount?: number
  rejectedCount?: number
  notDeliveredCount?: number
}

/** Mirrors the backend's `ShipmentTrackingRowView`: one trip with a current warehouse dispatch. */
export interface ShipmentTrackingRow {
  tripId: string
  shipmentNumber: string
  tripStatus: string
  planningDate: string
  carrierId: string | null
  carrierName: string | null
  dispatchReference: string
  dispatchedAt: string
  dispatchVerification: DispatchVerification
  /** Como lo reportó el almacén, no como se planificó. */
  vehicleLicensePlate: string | null
  driverName: string | null
  originName: string | null
  originLatitude: number | null
  originLongitude: number | null
  stopsTotal: number
  stopsResolved: number
  documentCount: number
  pendingCount: number
  deliveredCount: number
  rejectedCount: number
  notDeliveredCount: number
  returnedCount: number
  inconsistentCount: number
  lastReportAt: string | null
  stops: ShipmentTrackingStop[]
}

/** Mirrors the backend's `ShipmentTrackingSummaryView`. */
export interface ShipmentTrackingSummary {
  shipments: number
  inTransit: number
  completed: number
  withPendingDocuments: number
  documentsTotal: number
  documentsPending: number
  documentsDelivered: number
  documentsRejected: number
  documentsNotDelivered: number
  awaitingReturn: number
  dispatchMismatches: number
}

export interface ShipmentTrackingQuery {
  /** `yyyy-mm-dd`, fecha de despacho en la zona de la empresa, ambos extremos incluidos. */
  dateFrom?: string
  dateTo?: string
  carrierId?: string
  /** Un `TripStatus`. */
  status?: string
  documents?: ShipmentDocumentsFilter
  q?: string
}

export interface ShipmentTrackingListParams extends ShipmentTrackingQuery {
  companyId: string
  page?: number
  size?: number
  signal?: AbortSignal
}

/** `monitoring.transport:read`. */
export function fetchShipmentTracking(params: ShipmentTrackingListParams): Promise<PageResponse<ShipmentTrackingRow>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<ShipmentTrackingRow>>('/monitoring/shipment-tracking', { companyId, signal, query: { ...query } })
}

/** `monitoring.transport:read`. Responde a las fechas y al transportista, no al estado ni a los documentos. */
export function fetchShipmentTrackingSummary(params: {
  companyId: string; dateFrom?: string; dateTo?: string; carrierId?: string; signal?: AbortSignal
}): Promise<ShipmentTrackingSummary> {
  const { companyId, signal, ...query } = params
  return apiRequest<ShipmentTrackingSummary>('/monitoring/shipment-tracking/summary', { companyId, signal, query: { ...query } })
}
