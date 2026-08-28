import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'
import type { StopExecutionStatus, TripExceptionType, TripStatus, TripView } from './planningApi'

//
// The control tower (`docs/domain/CONTROL_TOWER_V1.md`): one operating day of transport, read-only.
//
// Its own client rather than another section of `planningApi`, for the reason `trackingApi` gives:
// it is another permission. Both endpoints are behind `monitoring.transport:read`, which a role can
// hold without holding `planning.trip:read` - a supervisor entitled to watch the day and to change
// none of it. A screen importing both from one file would make that easy to forget.
//

/** Mirrors the backend's `DepartureTimeliness` enum - the server's verdict on one departure.
 *
 * Never derived in the browser from `plannedDepartureAt` and `actualDepartureAt`. Two screens
 * deriving "late" would eventually disagree about what it means, and the rule (with the reasoning
 * for having no grace period) lives in `DepartureDelay` on the backend. */
export type DepartureTimeliness =
  | 'NOT_APPLICABLE'
  | 'NOT_SCHEDULED'
  | 'SCHEDULED'
  | 'OVERDUE'
  | 'ON_TIME'
  | 'LATE'

export const DEPARTURE_TIMELINESS: DepartureTimeliness[] = [
  'NOT_APPLICABLE',
  'NOT_SCHEDULED',
  'SCHEDULED',
  'OVERDUE',
  'ON_TIME',
  'LATE',
]

/** The two verdicts a dispatcher acts on. Mirrors `DepartureTimeliness.isDelayed()`; kept as a
 * list rather than a helper so the badge tone and the "needs attention" test read the same set. */
export const DELAYED_TIMELINESS: DepartureTimeliness[] = ['LATE', 'OVERDUE']

/** Mirrors the backend's `ControlTowerSummaryView` record - the KPI strip, counted server-side.
 *
 * Scoped by company and day only. The origin/carrier/status filters narrow the table below and
 * deliberately not these numbers: the strip is the day's whole picture, so filtering to one carrier
 * can never make another carrier's open exception vanish from the count. */
export interface ControlTowerSummaryView {
  tripsDraft: number
  /** Confirmed or loaded and waiting - committed, and still here. */
  tripsScheduled: number
  tripsInTransit: number
  tripsCompleted: number
  tripsCancelled: number
  /** Left after the plan said they would, by any margin - there is no grace period in V1. */
  tripsDepartedLate: number
  /** Were due to leave and have not. The only counter that changes on its own as the clock moves,
   * which is why the response also carries `generatedAt`. */
  tripsOverdue: number
  openExceptions: number
  /** Stops on trips that are out and still unresolved - the work left in the field. */
  outstandingStops: number
  /** Of `outstandingStops`, the ones whose service window has already closed. A subset, never a
   * separate population. */
  stopsPastWindow: number
  /** `null`, and not `0`, when the caller does not hold `orders.order:read`: zero would be a claim
   * about a backlog the response was not allowed to look at. */
  ordersUnplanned: number | null
  /**
   * Cuántos envíos de hoy **no pueden salir** en su estado actual (JOB 12).
   *
   * El total detrás de `ControlTowerView.blockers`, que viene recortado como todos los paneles.
   * Cero se muestra como cero: "no hay nada atascado" es un dato que un despachador quiere leer, no
   * deducir de una lista vacía.
   */
  blockedShipments: number
  /**
   * Cuántos avisos lleva el día (JOB 23).
   *
   * **Se cuenta aparte de `blockedShipments` y jamás se suma con él.** Responden preguntas
   * distintas — "¿hay algo atascado?" y "¿hay algo que convenga saber?" — y un total único haría
   * que tres diferencias de céntimos se leyeran como tres camiones que no pueden salir.
   */
  openAdvisories: number
}

/**
 * Mirrors `ControlTowerBlockerView` (JOB 12): un envío que hoy no sale si nadie hace algo.
 *
 * Es el único panel que informa de lo que está **a punto** de pasar en vez de lo que ya pasó. Cada
 * motivo es un rechazo que ya existe en el servicio, en el agregado y en la base de datos - aquí no
 * se inventa ninguna regla, sólo se dice antes de que el camión llegue a la puerta.
 */
export interface ControlTowerBlockerView {
  tripId: string
  tripNumber: number | null
  shipmentNumber: string
  /**
   * - `AWAITING_CARRIER_VEHICLE` — aceptado por un transportista que no es dueño del vehículo
   *   asignado (V42). Lo resuelve un planificador asignando un vehículo de ese transportista.
   * - `VEHICLE_UNAVAILABLE` / `DRIVER_UNAVAILABLE` — el recurso no puede trabajar a la hora de
   *   salida planificada (V42).
   */
  reason: 'AWAITING_CARRIER_VEHICLE' | 'VEHICLE_UNAVAILABLE' | 'DRIVER_UNAVAILABLE'
  /** El detalle concreto, para leer. Nunca se hace un switch sobre él. */
  detail: string
}

/** Mirrors the backend's `ControlTowerWorkloadView` record. */
export interface ControlTowerWorkloadView {
  trip: TripView
  /** The *worst* of the three capacity dimensions, decided server-side - the one that says whether
   * the truck is full. `null` means "we do not know how full this is"; rendering it as 0% would
   * read as an empty truck. */
  percentUsed: number | null
}

/** Mirrors the backend's `ControlTowerExceptionView` record - trip-level on purpose, see the
 * backend record: the panel says which shipment to open, and the workspace resolves the stop. */
export interface ControlTowerExceptionView {
  id: string
  tripId: string
  shipmentNumber: string | null
  tripStatus: TripStatus | null
  tripStopId: string | null
  exceptionType: TripExceptionType
  /** When the problem happened - operator-supplied, not when it was typed. */
  reportedAt: string
  notes: string | null
}

/** Mirrors the backend's `ControlTowerStopView` record. */
export interface ControlTowerStopView {
  stopId: string
  tripId: string
  shipmentNumber: string | null
  sequence: number
  destinationId: string
  destinationCode: string | null
  destinationName: string | null
  executionStatus: StopExecutionStatus
  serviceWindowStart: string | null
  serviceWindowEnd: string | null
  /** The window end resolved to a real instant in the company's zone - the stop itself stores a
   * local time with no date, and the backend owns which day and zone that belongs to. */
  windowEndsAt: string | null
  /** How far past the window the stop is, or `null` when it is still inside one or has none. */
  minutesPastWindow: number | null
  actualArrivalAt: string | null
  vehicleLicensePlate: string | null
}

/** Mirrors the backend's `ControlTowerView` record - the overview response.
 *
 * The three lists are capped server-side and their totals live in `summary`, so a panel reads as
 * "the worst twenty of forty-seven" rather than as the whole set. */
export interface ControlTowerView {
  date: string
  /** The instant the server judged the clock-dependent facts against - which trips are overdue,
   * which stops have run past their window. Sent so a tab left open can say its verdict is old
   * instead of looking current. */
  generatedAt: string
  summary: ControlTowerSummaryView
  workload: ControlTowerWorkloadView[]
  openExceptions: ControlTowerExceptionView[]
  outstandingStops: ControlTowerStopView[]
  /** Lo que impedirá salir a un camión hoy, antes de que se lo impida. */
  blockers: ControlTowerBlockerView[]
  /**
   * Lo que conviene saber y **no detiene nada** (JOB 23).
   *
   * Una segunda lista a propósito, no más filas en `blockers`. Un bloqueador es un estado que hace
   * que `dispatch` se niegue; un aviso es algo que un supervisor debería saber y sobre lo que puede
   * razonablemente no hacer nada hoy. En cuanto un panel ha dado la voz de alarma por una
   * diferencia de redondeo, el camión que de verdad no puede salir es una fila entre cuarenta.
   */
  advisories: ControlTowerAdvisoryView[]
}

/** Mirrors `ControlTowerAdvisoryView.AdvisoryType`. */
export type AdvisoryType = 'SETTLEMENT_DISCREPANCY_OPEN' | 'STOP_ETA_MISSES_WINDOW'

/**
 * Mirrors `ControlTowerAdvisoryView` (JOB 23).
 *
 * **La torre no es dueña de nada de esto.** Una discrepancia de liquidación se resuelve en la
 * pantalla de liquidaciones; esta fila enlaza con ella y no ofrece cerrarla. Dos registros de una
 * misma disputa se separarían la primera vez que alguien resolviera el que no era.
 */
export interface ControlTowerAdvisoryView {
  type: AdvisoryType
  tripId: string
  shipmentNumber: string | null
  /** El id del registro en su propio módulo, para enlazar allí. */
  sourceId: string
  /** **Null cuando los dos lados no se pudieron comparar** — nunca un cero que se leería como
   * "la factura coincide", que es justo lo contrario (V46). */
  amount: number | null
  currency: string | null
  detail: string
}

/** Mirrors the backend's `ControlTowerTripView` record - one row of the operational table.
 *
 * Composed of `TripView` rather than repeating it: a control tower row is a trip seen through one
 * lens, and the lens is the five monitoring fields beside it. */
export interface ControlTowerTripView {
  trip: TripView
  departureTimeliness: DepartureTimeliness
  /** How late, in minutes, or `null` when the trip is not late. Always sent so a screen can tell
   * three minutes from ninety-five instead of painting both red. */
  departureDelayMinutes: number | null
  stopsTotal: number
  /** Completed, skipped or failed. A skipped stop counts as resolved - somebody dealt with it. */
  stopsResolved: number
  stopsPastWindow: number
  nextStopSequence: number | null
  nextStopDueAt: string | null
  openExceptions: number
}

export interface ControlTowerParams {
  companyId: string
  /** Omitted means today *in the company's time zone*, decided by the server - never by the
   * browser, whose idea of today can be a day out. */
  date?: string
  originId?: string
  carrierId?: string
  signal?: AbortSignal
}

export function fetchControlTower(params: ControlTowerParams): Promise<ControlTowerView> {
  const { companyId, signal, ...query } = params
  return apiRequest<ControlTowerView>('/monitoring/control-tower', { companyId, signal, query })
}

export interface ControlTowerBoardParams extends ControlTowerParams {
  page?: number
  size?: number
  sort?: string
  status?: TripStatus
}

export function fetchControlTowerTrips(
  params: ControlTowerBoardParams,
): Promise<PageResponse<ControlTowerTripView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<ControlTowerTripView>>('/monitoring/control-tower/trips', {
    companyId,
    signal,
    query,
  })
}
