import { apiDownload, apiRequest, type DownloadedFile } from './httpClient'
import type { TripStatus } from './planningApi'

//
// Scorecard de transportistas (`docs/domain/CARRIER_SCORECARD_V1.md`): por transportista —y la
// flota propia— cómo corrieron sus viajes en un rango de días operativos.
//
// Igual que los KPIs: **nada se calcula en el navegador**. Cada porcentaje llega dividido por el
// backend y un `null` significa "no hubo denominador": se pinta "sin datos", nunca 0%.
//
// Cuatro bloques llegan `null` cuando la cuenta no tiene el permiso que los gobierna: ofertas
// (`planning.tender:read`), documentos (`planning.document_delivery:read`), facturas
// (`settlement.invoice:read`) y costo (`rates.trip_cost:read`). Los cuatro `*Visible` lo dicen una vez
// para todo el informe, para que la pantalla quite la columna en vez de llenarla de rayas.
//

export interface CarrierTenderFigures {
  offered: number
  accepted: number
  rejected: number
  expired: number
  awaiting: number
  timed: number
  /** aceptadas / (aceptadas + rechazadas + vencidas). */
  acceptancePercent: number | null
  averageResponseMinutes: number | null
}

export interface CarrierDocumentFigures {
  documents: number
  delivered: number
  returned: number
  deliveredPercent: number | null
  /** Cargos devueltos a la oficina / documentos entregados. */
  cargoReturnPercent: number | null
}

/** Una moneda de las facturas del transportista con fecha en el rango. */
export interface CarrierInvoiceFigures {
  currency: string
  invoices: number
  withDiscrepancy: number
  discrepancyRatePercent: number | null
  amountObserved: number
}

/** Una moneda del costo de sus viajes: el real si se registró, si no el estimado. */
export interface CarrierCostFigures {
  currency: string
  tripsCosted: number
  tripsWithActual: number
  amount: number
  costPerTrip: number | null
  weightKg: number
  costPerKg: number | null
}

export interface CarrierScorecardRow {
  /** El id del transportista, o `OWN_FLEET`. Es lo que pide el detalle. */
  carrierKey: string
  carrierId: string | null
  ownFleet: boolean
  carrierCode: string | null
  carrierName: string | null
  trips: number
  tripsCancelled: number
  tripsExecuted: number
  tripsCompleted: number
  departuresMeasured: number
  departuresLate: number
  onTimeDeparturePercent: number | null
  stops: number
  serviceWindowsMeasured: number
  serviceWindowsMissed: number
  inWindowPercent: number | null
  deliveriesRecorded: number
  deliveriesDelivered: number
  deliveriesShort: number
  deliveriesNotAttempted: number
  deliverySuccessPercent: number | null
  exceptions: number
  exceptionsOpen: number
  exceptionsPer100Trips: number | null
  tenders: CarrierTenderFigures | null
  documents: CarrierDocumentFigures | null
  invoices: CarrierInvoiceFigures[] | null
  cost: CarrierCostFigures[] | null
}

export interface CarrierScorecardView {
  from: string
  to: string
  days: number
  generatedAt: string
  tendersVisible: boolean
  documentsVisible: boolean
  invoicesVisible: boolean
  costVisible: boolean
  /** Viajes sin transportista ni vehículo: de nadie todavía. */
  unattributedTrips: number
  carriers: CarrierScorecardRow[]
}

export interface CarrierScorecardTripRow {
  tripId: string
  shipmentNumber: string
  planningDate: string
  status: TripStatus
  plannedDepartureAt: string | null
  actualDepartureAt: string | null
  /** Null cuando la salida no se midió. */
  departureLate: boolean | null
  stops: number
  windowsMeasured: number
  windowsMissed: number
  deliveriesRecorded: number
  deliveriesDelivered: number
  deliveriesShort: number
  exceptions: number
}

export interface CarrierScorecardTripsView {
  carrierKey: string
  carrierId: string | null
  ownFleet: boolean
  carrierName: string | null
  from: string
  to: string
  total: number
  /** La lista se cortó antes de `total`: el backend lo dice en vez de callarlo. */
  truncated: boolean
  trips: CarrierScorecardTripRow[]
}

export interface CarrierScorecardParams {
  companyId: string
  from?: string
  to?: string
  signal?: AbortSignal
}

export function fetchCarrierScorecard(params: CarrierScorecardParams): Promise<CarrierScorecardView> {
  const { companyId, signal, ...query } = params
  return apiRequest<CarrierScorecardView>('/reporting/carrier-scorecard', { companyId, signal, query })
}

export function fetchCarrierScorecardTrips(
  params: CarrierScorecardParams & { carrier: string },
): Promise<CarrierScorecardTripsView> {
  const { companyId, signal, ...query } = params
  return apiRequest<CarrierScorecardTripsView>('/reporting/carrier-scorecard/trips', { companyId, signal, query })
}

/** El scorecard en CSV. El servidor nombra el archivo con el rango que cubre. */
export function downloadCarrierScorecardCsv(params: CarrierScorecardParams): Promise<DownloadedFile> {
  const { companyId, signal, ...query } = params
  return apiDownload('/reporting/carrier-scorecard/export', { companyId, query, signal })
}
