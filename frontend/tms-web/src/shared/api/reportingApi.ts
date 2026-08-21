import { apiDownload, apiRequest, type DownloadedFile } from './httpClient'
import type { TripStatus } from './planningApi'

//
// KPIs and reporting (`docs/domain/KPIS_REPORTING_V1.md`): one company, a span of operating days,
// every figure counted server-side.
//
// Its own client rather than another section of `controlTowerApi`, even though both endpoints sit
// behind `monitoring.transport:read`: the control tower is one day and refreshes on a timer, this is
// a range and refreshes when somebody changes a filter, and the two answer different questions. A
// file that held both would make it easy to reach for the wrong one.
//
// **Nothing here is computed in the browser.** Every percentage arrives as a number the backend
// divided, and every `null` means "nothing was measured" and must render as a dash - never as 0%
// and never as 100%. Deriving any of them here would give the product a second opinion about what
// "on time" means; the rule lives in `KpiRate` on the backend.
//

/**
 * Mirrors the backend's `KpiShipmentsView`.
 *
 * `departuresMeasured` is the denominator of `onTimeDeparturePercent` and is sent so a screen can
 * say what the percentage is *about*: 92% over five measured departures is a different claim from
 * 92% over four hundred.
 */
export interface KpiShipmentsView {
  /** Every shipment planned in the range, cancelled ones included. */
  trips: number
  /** `trips` minus the cancelled ones - the denominator of `completionPercent`. */
  tripsRun: number
  tripsCancelled: number
  tripsCompleted: number
  /** Every `TripStatus` is present, padded server-side, so a client never has to know the enum. */
  byStatus: Record<TripStatus, number>
  /** Shipments carrying both a planned and an actual departure. */
  departuresMeasured: number
  departuresLate: number
  /** 0-100, or `null` when nothing was measured. */
  onTimeDeparturePercent: number | null
  completionPercent: number | null
}

/** Mirrors the backend's `KpiServiceView` - what the vehicle did, and separately what the goods did. */
export interface KpiServiceView {
  stops: number
  stopsCompleted: number
  stopsSkipped: number
  stopsFailed: number
  /** Stops carrying both a recorded arrival and a promised window. */
  serviceWindowsMeasured: number
  serviceWindowsMissed: number
  onTimeServicePercent: number | null
  deliveriesRecorded: number
  deliveriesDelivered: number
  /** Partial, refused or failed. Deliberately excludes `NOT_ATTEMPTED` - see `DeliveryResult`. */
  deliveriesShort: number
  deliveriesNotAttempted: number
  deliverySuccessPercent: number | null
}

/** Mirrors the backend's `KpiExceptionsView`. */
export interface KpiExceptionsView {
  exceptions: number
  open: number
  resolved: number
  /** Problems per hundred shipments that ran. Not a percentage: it may exceed 100. */
  per100Trips: number | null
}

/**
 * Mirrors the backend's `KpiUtilizationView`.
 *
 * Each percentage is the range's whole load over its whole capacity - never the average of the
 * per-shipment percentages. `trips` says how many shipments the three figures cover, which is not
 * the same as the range's shipment count: drafts and cancellations are outside it.
 */
export interface KpiUtilizationView {
  trips: number
  weightUsedKg: number
  weightCapacityKg: number
  weightPercent: number | null
  volumeUsedM3: number
  volumeCapacityM3: number
  volumePercent: number | null
  palletsUsed: number
  palletCapacity: number
  palletsPercent: number | null
}

/** Mirrors the backend's `KpiOrdersView`. `inputOrders === planned + unplanned`, always. */
export interface KpiOrdersView {
  inputOrders: number
  planned: number
  unplanned: number
  readyToPlan: number
  notReady: number
  /** Outside the three figures above: a withdrawn order was never work the plan failed to cover. */
  cancelled: number
  plannedPercent: number | null
}

/** Mirrors the backend's `KpiTenderView`. Counted in offers, not in shipments. */
export interface KpiTenderView {
  attempts: number
  accepted: number
  rejected: number
  /** A floor, not an exact figure: an offer that lapsed today may still be awaiting a response. */
  expired: number
  cancelled: number
  awaitingResponse: number
  draft: number
  /** `accepted + rejected` - the denominator of both rates below. */
  answered: number
  acceptancePercent: number | null
  rejectionPercent: number | null
}

/** Mirrors the backend's `KpiCostView`. One per currency; there is no grand total and never will be. */
export interface KpiCostView {
  currency: string
  tripsEstimated: number
  estimatedAmount: number
  tripsWithActual: number
  actualAmount: number
  /** Shipments carrying both figures - the only ones `variance` is about. */
  tripsComparable: number
  comparableEstimated: number
  comparableActual: number
  /** Positive means it cost more than the tariff said. `null` when nothing is comparable. */
  variance: number | null
  variancePercent: number | null
}

/** Mirrors the backend's `KpiDailyRow` - one operating day, including the days nothing happened on. */
export interface KpiDailyRow {
  date: string
  trips: number
  tripsCancelled: number
  tripsCompleted: number
  departuresMeasured: number
  departuresLate: number
  onTimeDeparturePercent: number | null
  deliveriesRecorded: number
  deliveriesDelivered: number
  deliverySuccessPercent: number | null
  exceptions: number
  exceptionsOpen: number
}

/**
 * Mirrors the backend's `KpiReportView`.
 *
 * `orders`, `tenders` and `cost` are `null` when the caller does not hold the permission that owns
 * them (`orders.order:read`, `planning.tender:read`, `rates.trip_cost:read`). Null is not empty: the
 * screen says "not available to you" rather than showing a zero it was never told.
 */
export interface KpiReportView {
  from: string
  to: string
  days: number
  generatedAt: string
  shipments: KpiShipmentsView
  service: KpiServiceView
  exceptions: KpiExceptionsView
  utilization: KpiUtilizationView
  orders: KpiOrdersView | null
  tenders: KpiTenderView | null
  cost: KpiCostView[] | null
  daily: KpiDailyRow[]
}

export interface KpiParams {
  companyId: string
  /** Inclusive. Omitted means thirty days before `to`, decided by the server. */
  from?: string
  /** Inclusive. Omitted means today *in the company's time zone* - never the browser's. */
  to?: string
  signal?: AbortSignal
}

export function fetchKpiReport(params: KpiParams): Promise<KpiReportView> {
  const { companyId, signal, ...query } = params
  return apiRequest<KpiReportView>('/reporting/kpis', { companyId, signal, query })
}

/**
 * Downloads the daily table as CSV. The server names the file after the range it covers, so a copy
 * on somebody's desktop still says which days it is about.
 */
export function downloadKpiCsv(params: KpiParams): Promise<DownloadedFile> {
  const { companyId, signal, ...query } = params
  return apiDownload('/reporting/kpis/export', { companyId, query, signal })
}
