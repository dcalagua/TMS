import type { DriverLicenseStatus } from './driversApi'
import { apiDownload, apiRequest, apiUpload, type DownloadedFile } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `PlanningRunStatus` enum (`planning/domain/PlanningRunStatus.java`). */
export type PlanningRunStatus = 'DRAFT' | 'CONFIRMED' | 'CANCELLED'

export const PLANNING_RUN_STATUSES: PlanningRunStatus[] = ['DRAFT', 'CONFIRMED', 'CANCELLED']

/** Mirrors the backend's `PlanningMode` enum. Only `MANUAL` is reachable in V1. */
export type PlanningMode = 'MANUAL' | 'AUTOMATIC'

/** Mirrors the backend's `TripStatus` enum (`planning/domain/TripStatus.java`).
 *
 * The legal moves between these values live on the server and reach the browser as
 * `TripView.allowedTransitions` - there is deliberately no transition table in TypeScript, because
 * a second copy of the lifecycle is a copy that goes out of date. */
export type TripStatus =
  | 'DRAFT'
  | 'CONFIRMED'
  | 'READY_FOR_DISPATCH'
  | 'IN_TRANSIT'
  | 'COMPLETED'
  | 'CANCELLED'

/** In lifecycle order, which is the order a status filter should offer them in. */
export const TRIP_STATUSES: TripStatus[] = [
  'DRAFT', 'CONFIRMED', 'READY_FOR_DISPATCH', 'IN_TRANSIT', 'COMPLETED', 'CANCELLED',
]

/** Mirrors the backend's `StopExecutionStatus` enum (`planning/domain/StopExecutionStatus.java`).
 *
 * Same contract as `TripStatus`: the transition table lives on the server and reaches the browser
 * as `TripStopView.allowedExecutionTransitions`. `SKIPPED` means never attempted, `FAILED` means
 * attempted and not served - the two are different facts and the UI must not merge them. */
export type StopExecutionStatus =
  | 'PENDING'
  | 'ARRIVED'
  | 'IN_SERVICE'
  | 'COMPLETED'
  | 'SKIPPED'
  | 'FAILED'

/** In lifecycle order - the order the stop actions are offered in. */
export const STOP_EXECUTION_STATUSES: StopExecutionStatus[] = [
  'PENDING', 'ARRIVED', 'IN_SERVICE', 'COMPLETED', 'SKIPPED', 'FAILED',
]

/** Mirrors the backend's `TransportEventType` enum: what one timeline entry records. */
export type TransportEventType =
  | 'TRIP_CONFIRMED'
  | 'TRIP_READY'
  | 'TRIP_DISPATCHED'
  | 'TRIP_COMPLETED'
  | 'TRIP_CANCELLED'
  | 'ARRIVED_AT_STOP'
  | 'SERVICE_STARTED'
  | 'STOP_COMPLETED'
  | 'STOP_SKIPPED'
  | 'STOP_FAILED'
  | 'DELIVERY_RECORDED'
  | 'TENDER_SENT'
  | 'TENDER_ACCEPTED'
  | 'TENDER_REJECTED'
  | 'TENDER_EXPIRED'
  | 'TENDER_CANCELLED'
  | 'EXCEPTION_REPORTED'
  | 'EXCEPTION_RESOLVED'

/** Every value the timeline can carry, so `enums.test.ts` can prove each one has a label. */
export const TRANSPORT_EVENT_TYPES: TransportEventType[] = [
  'TRIP_CONFIRMED', 'TRIP_READY', 'TRIP_DISPATCHED', 'TRIP_COMPLETED', 'TRIP_CANCELLED',
  'ARRIVED_AT_STOP', 'SERVICE_STARTED', 'STOP_COMPLETED', 'STOP_SKIPPED', 'STOP_FAILED',
  'DELIVERY_RECORDED',
  'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED', 'TENDER_CANCELLED',
  'EXCEPTION_REPORTED', 'EXCEPTION_RESOLVED',
]

/** Mirrors the backend's `DeliveryResult` enum (`planning/domain/DeliveryResult.java`).
 *
 * What happened to *the goods of one order* at a stop, which is not what the stop itself did: a
 * stop can be `COMPLETED` with one of its orders `REJECTED`, and both are true. There is no
 * "pending" value - an order with no delivery recorded simply has no entry in
 * `TripDetailView.deliveries`, and the UI must not invent one. */
export type DeliveryResult =
  | 'DELIVERED'
  | 'PARTIAL'
  | 'REJECTED'
  | 'FAILED'
  | 'NOT_ATTEMPTED'

/** Best case first, which is the order the picker offers them in. */
export const DELIVERY_RESULTS: DeliveryResult[] = [
  'DELIVERED', 'PARTIAL', 'REJECTED', 'FAILED', 'NOT_ATTEMPTED',
]

/** The results the backend refuses without an explanation - `DeliveryResult.requiresNotes`.
 *
 * Duplicated here only to mark the field required in the form, never to decide the outcome: the
 * server re-checks, and a client that got this wrong would be corrected rather than obeyed. */
export const DELIVERY_RESULTS_NEEDING_NOTES: DeliveryResult[] = ['PARTIAL', 'REJECTED', 'FAILED']

/** The results reached with somebody present - the only ones that may name a receiver. */
export const DELIVERY_RESULTS_WITH_RECEIVER: DeliveryResult[] = ['DELIVERED', 'PARTIAL', 'REJECTED']

/** The results that must say when the goods changed hands. */
export const DELIVERY_RESULTS_NEEDING_TIME: DeliveryResult[] = ['DELIVERED', 'PARTIAL']

/** Mirrors the backend's `EvidenceType` enum. `SIGNATURE` is a captured signature image, never a
 * digital signature in the legal sense - see `EvidenceType.java`. */
export type EvidenceType = 'SIGNATURE' | 'PHOTO' | 'DOCUMENT'

export const EVIDENCE_TYPES: EvidenceType[] = ['SIGNATURE', 'PHOTO', 'DOCUMENT']

/** Mirrors the backend's `TransportEventSource` enum: who or what reported the entry. */
export type TransportEventSource = 'OPERATOR' | 'SYSTEM' | 'INTEGRATION'

/** Mirrors the backend's `TripExceptionType` enum - the catalogue of operational problems.
 *
 * Operational, never technical: a rejected payload or a 500 is not one of these. */
export type TripExceptionType =
  | 'TRAFFIC_DELAY'
  | 'VEHICLE_BREAKDOWN'
  | 'CUSTOMER_CLOSED'
  | 'DELIVERY_REJECTED'
  | 'ADDRESS_NOT_FOUND'
  | 'DELIVERY_FAILED'
  | 'OTHER'

/** The order a picker offers them in: journey problems first, then delivery ones, then OTHER. */
export const TRIP_EXCEPTION_TYPES: TripExceptionType[] = [
  'TRAFFIC_DELAY', 'VEHICLE_BREAKDOWN', 'CUSTOMER_CLOSED', 'DELIVERY_REJECTED',
  'ADDRESS_NOT_FOUND', 'DELIVERY_FAILED', 'OTHER',
]

/** The four types the backend refuses without a stop - `TripExceptionType.requiresStop`.
 *
 * Duplicated here only to *disable* an option in the picker, never to decide the outcome: the
 * server re-checks, and a client that got this wrong would be corrected rather than obeyed. */
export const STOP_SCOPED_EXCEPTION_TYPES: TripExceptionType[] = [
  'CUSTOMER_CLOSED', 'DELIVERY_REJECTED', 'ADDRESS_NOT_FOUND', 'DELIVERY_FAILED',
]

/** Mirrors the backend's `TripExceptionStatus` enum. */
export type TripExceptionStatus = 'OPEN' | 'RESOLVED'

export const TRIP_EXCEPTION_STATUSES: TripExceptionStatus[] = ['OPEN', 'RESOLVED']

/** Mirrors the backend's `CapacitySource` enum: where a trip's limits came from. */
export type CapacitySource = 'LIVE' | 'SNAPSHOT' | 'NONE'

/** Mirrors the backend's `CapacityDimension` record. `limit`/`remaining`/`percentUsed` are
 * `null` in the documented cases (see `docs/domain/CAPACITY_MODEL.md`) - never invented
 * client-side. */
export interface CapacityDimension {
  used: number
  limit: number | null
  remaining: number | null
  percentUsed: number | null
  exceeded: boolean
  unlimited: boolean
}

/** Mirrors the backend's `TripCapacityView` record. */
export interface TripCapacityView {
  tripId: string
  source: CapacitySource
  orderCount: number
  weight: CapacityDimension
  volume: CapacityDimension
  pallets: CapacityDimension
  withinCapacity: boolean
}

/** Mirrors the backend's `EligibleOrderView` record - a planning-board row, never lines. */
export interface EligibleOrderView {
  id: string
  orderNumber: string
  originId: string
  destinationId: string
  destinationCode: string | null
  destinationName: string | null
  customerName: string | null
  customerReference: string | null
  serviceDate: string
  priority: string
  requestedWindowStart: string | null
  requestedWindowEnd: string | null
  totalWeightKg: number
  totalVolumeM3: number
  totalPallets: number
  /** Lo que queda por planificar (migración V37): igual al total si nadie la ha tocado, menor si
   * parte del pedido ya viaja en otro camión. Es la cifra sobre la que se decide, no el total. */
  pendingWeightKg: number
  pendingVolumeM3: number
  pendingPallets: number
  /** Parte del pedido ya está en un viaje. Bandera propia y no una comparación de dos números:
   * "esto es un reparto" es lo que el tablero tiene que decir en voz alta. */
  partiallyAllocated: boolean
}

export interface EligibleOrderListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  originId?: string
  destinationId?: string
  serviceDate?: string
  orderNumber?: string
  signal?: AbortSignal
}

export function fetchEligibleOrders(params: EligibleOrderListParams): Promise<PageResponse<EligibleOrderView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<EligibleOrderView>>('/planning/eligible-orders', { companyId, signal, query })
}

/** Mirrors the backend's `PlanningRunView` record - the run list-row shape. */
export interface PlanningRunView {
  id: string
  planNumber: string
  originId: string
  originCode: string | null
  originName: string | null
  planningDate: string
  mode: PlanningMode
  status: PlanningRunStatus
  notes: string | null
  tripCount: number
  assignedOrderCount: number
  confirmedAt: string | null
  cancelledAt: string | null
  cancelReason: string | null
  version: number
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `TripView` record - the board-row shape of a trip and the shipment
 * header (`docs/domain/SHIPMENT_V2.md`).
 *
 * `shipmentNumber` is the trip's identity outside the board (`SH-00000012`); `tripNumber` is its
 * identity inside `planNumber` and means nothing without it, so a screen that shows one without
 * the other should show the shipment number. Everything except those two and the trip's own
 * columns is resolved server-side - the browser never joins a master to a trip itself. */
export interface TripView {
  id: string
  companyId: string
  planningRunId: string
  planNumber: string | null
  planningDate: string
  tripNumber: number
  shipmentNumber: string
  status: TripStatus
  originId: string | null
  originCode: string | null
  originName: string | null
  originLatitude: number | null
  originLongitude: number | null
  vehicleId: string | null
  vehicleCode: string | null
  vehicleLicensePlate: string | null
  vehicleTypeCode: string | null
  carrierId: string | null
  /** The carrier the shipment was *planned* with, resolved from `carrierId` - not the vehicle's
   * current carrier, which may since have changed (see `CarrierLookupPort`). */
  carrierName: string | null
  driverId: string | null
  driverCode: string | null
  /** "Last, first" - composed server-side so no screen has to guess the order of the two columns. */
  driverName: string | null
  driverPhone: string | null
  driverLicenseNumber: string | null
  driverLicenseExpiresOn: string | null
  /** Judged server-side against this trip's *planning date*, not against today: the board is
   * indexed by the day the trip runs, so "expiring soon" means soon relative to that day. */
  driverLicenseStatus: DriverLicenseStatus | null
  routeId: string | null
  routeCode: string | null
  routeName: string | null
  /** What the plan asked for. Never rewritten by what happened - see `actualDepartureAt`. */
  plannedDepartureAt: string | null
  readyAt: string | null
  /** When the vehicle really left. `plannedDepartureAt - actualDepartureAt` is the departure delay. */
  actualDepartureAt: string | null
  actualCompletionAt: string | null
  cancelledAt: string | null
  cancelReason: string | null
  /** The states this trip may still move to, decided server-side (`TripStatus`'s transition
   * table). Render buttons from this rather than from a `switch` on `status`: the server is the
   * authority on the lifecycle and re-checks every transition it is asked to make anyway. */
  allowedTransitions: TripStatus[]
  capacity: TripCapacityView
  stopCount: number
  orderCount: number
  version: number
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `PlanningRunDetailView` record - the planning board: one call. */
export interface PlanningRunDetailView {
  run: PlanningRunView
  trips: TripView[]
}

/** Mirrors the backend's `TripAssignmentView` record. Amounts come from the assignment row,
 * not the order header - see the record's javadoc. */
export interface TripAssignmentView {
  assignmentId: string
  orderId: string
  orderNumber: string
  destinationId: string
  destinationCode: string | null
  destinationName: string | null
  customerName: string | null
  serviceDate: string
  priority: string
  requestedWindowStart: string | null
  requestedWindowEnd: string | null
  assignedWeightKg: number
  assignedVolumeM3: number
  assignedPallets: number
  wholeOrder: boolean
  assignedAt: string
}

/** Mirrors the backend's `TripStopView` record.
 *
 * `sequence` is always part of a contiguous 1..N series over the trip's stops - the backend
 * refuses to persist anything else - so a map may number its markers from it directly.
 * `latitude`/`longitude` are the destination's current coordinates, both null together when the
 * destination has never been geocoded: render the stop without a marker rather than inventing a
 * position. `address` is read live from the same master, independently of the coordinates - a
 * destination can have one without the other. */
export interface TripStopView {
  id: string
  sequence: number
  destinationId: string
  destinationCode: string | null
  destinationName: string | null
  latitude: number | null
  longitude: number | null
  address: string | null
  serviceWindowStart: string | null
  serviceWindowEnd: string | null
  orderCount: number
  executionStatus: StopExecutionStatus
  /** The outcomes this stop may still move to, decided server-side. Empty unless the trip is out:
   * a stop cannot be worked before its vehicle leaves. Render these and derive nothing from
   * `executionStatus` - the same contract `TripView.allowedTransitions` states for the trip. */
  allowedExecutionTransitions: StopExecutionStatus[]
  actualArrivalAt: string | null
  serviceStartedAt: string | null
  actualDepartureAt: string | null
  executionNotes: string | null
  /** Minutes between arrival and departure, or null until both ends are known. */
  dwellMinutes: number | null
  openExceptionCount: number
}

/** Mirrors the backend's `TransportEventView` record - one entry of a trip's timeline.
 *
 * `stopSequence` and `stopDestinationName` are null for a trip-level entry. `actorName` is the
 * operator's email or an integration's machine label, snapshotted when the entry was written.
 * `metadata` is opaque JSON the UI does not read into. */
export interface TransportEventView {
  id: string
  tripId: string
  tripStopId: string | null
  stopSequence: number | null
  stopDestinationCode: string | null
  stopDestinationName: string | null
  eventType: TransportEventType
  eventTime: string
  /** When the entry was typed, against `eventTime`'s when-it-happened. */
  recordedAt: string
  source: TransportEventSource
  actorName: string | null
  notes: string | null
  metadata: string | null
}

/** Mirrors the backend's `TripExceptionView` record. */
export interface TripExceptionView {
  id: string
  tripId: string
  tripStopId: string | null
  stopSequence: number | null
  stopDestinationCode: string | null
  stopDestinationName: string | null
  exceptionType: TripExceptionType
  status: TripExceptionStatus
  reportedAt: string
  notes: string | null
  resolvedAt: string | null
  resolutionNotes: string | null
}

/** Mirrors the backend's `DeliveryEvidenceView` record - one proof-of-delivery artefact.
 *
 * There is deliberately no URL here. The bytes are fetched with `downloadDeliveryEvidence`, which
 * goes through the authenticated, company-scoped API like every other request; a link in the
 * payload would be a second, quieter way to reach a customer's signed delivery note. */
export interface DeliveryEvidenceView {
  id: string
  evidenceType: EvidenceType
  contentType: string
  sizeBytes: number
  checksumSha256: string
  originalFilename: string | null
  capturedAt: string | null
  uploadedAt: string
}

/** Mirrors the backend's `OrderDeliveryView` record - what happened to one order at one stop.
 *
 * Flat rather than nested inside the stop it belongs to: a screen groups by `tripStopId` to show it
 * under the stop and by `orderId` to show it against the order, and both are here. */
export interface OrderDeliveryView {
  id: string
  tripStopId: string
  stopSequence: number | null
  orderId: string
  orderNumber: string | null
  result: DeliveryResult
  deliveredAt: string | null
  receiverName: string | null
  receiverDocument: string | null
  notes: string | null
  source: TransportEventSource
  recordedByName: string | null
  /** When the row was typed, against `deliveredAt`'s when-it-happened. */
  recordedAt: string
  evidence: DeliveryEvidenceView[]
}

/** Mirrors the backend's `TripDetailView` record.
 *
 * The timeline is deliberately absent: it grows over a long day and is fetched on its own through
 * `fetchTripEvents`, rather than being re-sent by every mutation that returns a trip. */
/** Mirrors `TripRouteMetrics.TripRouteLegView` - un tramo del recorrido. */
export interface TripRouteLegView {
  /** `null` en el tramo que sale del origen: el origen no es la parada cero. */
  fromStopSequence: number | null
  fromLabel: string
  toStopSequence: number
  toLabel: string
  distanceKm: number
  travelMinutes: number
  estimated: boolean
}

/**
 * Mirrors the backend's `TripRouteMetrics` (migración V38): cuánto conduce el viaje y cuánto tarda.
 *
 * `estimated` es la bandera importante. Sin un proveedor de rutas configurado las cifras salen del
 * estimador local — línea recta por un factor de carretera — y eso es útil pero no es una medición.
 * La pantalla tiene que decirlo, no esconderlo.
 *
 * `totalDuration` es solo conducción: el tiempo en el muelle es el `serviceTimeMinutes` de la
 * ubicación y mezclar ambos daría una cifra que no es ni un trayecto ni una jornada.
 */
export interface TripRouteMetrics {
  totalDistanceKm: number
  totalMinutes: number
  legs: TripRouteLegView[]
  provider: string | null
  estimated: boolean
  /** Tramos que no se pudieron medir porque una ubicación no tiene coordenadas. */
  unmeasurableLegs: number
  complete: boolean
}

export interface TripDetailView {
  trip: TripView
  assignments: TripAssignmentView[]
  stops: TripStopView[]
  /** Newest first, open and resolved together. */
  exceptions: TripExceptionView[]
  /** In visiting order. An assigned order with no entry here has not been recorded yet. */
  deliveries: OrderDeliveryView[]
  /** Nunca null: un viaje sin recorrido medible trae ceros y sus tramos no medidos contados. */
  routing: TripRouteMetrics
}

export interface PlanningRunListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  planNumber?: string
  originId?: string
  planningDateFrom?: string
  planningDateTo?: string
  status?: PlanningRunStatus
  signal?: AbortSignal
}

export function fetchPlanningRuns(params: PlanningRunListParams): Promise<PageResponse<PlanningRunView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<PlanningRunView>>('/planning/runs', { companyId, signal, query })
}

export function fetchPlanningRun(companyId: string, id: string, signal?: AbortSignal): Promise<PlanningRunDetailView> {
  return apiRequest<PlanningRunDetailView>(`/planning/runs/${id}`, { companyId, signal })
}

/** Mirrors the backend's `PlanningRunRequest` record. `mode` is absent on purpose - V1 only
 * creates `MANUAL` runs. */
export interface PlanningRunRequest {
  originId: string
  planningDate: string
  notes?: string | null
}

export function createPlanningRun(companyId: string, request: PlanningRunRequest): Promise<PlanningRunDetailView> {
  return apiRequest<PlanningRunDetailView>('/planning/runs', { method: 'POST', companyId, body: request })
}

/** Mirrors the backend's `PlanningActionRequest` record: mandatory `version`, optional `reason`. */
export interface PlanningActionRequest {
  version: number
  reason?: string | null
}

export function confirmPlanningRun(
  companyId: string, id: string, request: PlanningActionRequest,
): Promise<PlanningRunDetailView> {
  return apiRequest<PlanningRunDetailView>(`/planning/runs/${id}/confirm`, { method: 'POST', companyId, body: request })
}

export function cancelPlanningRun(
  companyId: string, id: string, request: PlanningActionRequest,
): Promise<PlanningRunDetailView> {
  return apiRequest<PlanningRunDetailView>(`/planning/runs/${id}/cancel`, { method: 'POST', companyId, body: request })
}

// --- automatic planning ----------------------------------------------------------------

/**
 * Mirrors the backend's `AutoPlanView.UnplannedOrderView`. `reason` is the enum name, never a
 * sentence: the backend does not translate, and the values are labelled by `statuses`.
 */
export interface UnplannedOrderView {
  orderId: string
  orderNumber: string | null
  reason:
    | 'EXCEEDS_LARGEST_VEHICLE'
    | 'NO_VEHICLE_AVAILABLE'
    | 'NO_FLEET'
    | 'TAKEN_WHILE_PLANNING'
    | 'NOT_SERVICEABLE_ON_DATE'
}

/** Mirrors `AutoPlanView.ProposedTripView`. */
export interface ProposedTripView {
  vehicleId: string
  vehicleCode: string | null
  routeId: string | null
  orderNumbers: string[]
  stopCount: number
}

/**
 * Mirrors the backend's `AutoPlanView`. Preview and apply return the same shape, differing only
 * in `applied` and `created` - so what the planner reviewed is what they get.
 */
export interface AutoPlanView {
  applied: boolean
  engine: string
  proposed: ProposedTripView[]
  created: TripDetailView[]
  unplanned: UnplannedOrderView[]
  ordersConsidered: number
  vehiclesOffered: number
}

/** What automatic planning would do. Writes nothing. */
export function previewAutoPlan(companyId: string, runId: string, signal?: AbortSignal): Promise<AutoPlanView> {
  return apiRequest<AutoPlanView>(`/planning/runs/${runId}/auto-plan/preview`, { companyId, signal })
}

/** Writes the proposal as draft trips. Never confirms them. */
export function applyAutoPlan(
  companyId: string, runId: string, request: PlanningActionRequest,
): Promise<AutoPlanView> {
  return apiRequest<AutoPlanView>(`/planning/runs/${runId}/auto-plan`, { method: 'POST', companyId, body: request })
}

/** Mirrors the backend's `TripCreateRequest` record. `version` is the *run's* version - trip
 * creation is a run-level operation (`PlanningRunController.createTrip`). */
export interface TripCreateRequest {
  vehicleId?: string | null
  plannedDepartureAt?: string | null
  version: number
}

export function createTrip(companyId: string, runId: string, request: TripCreateRequest): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/runs/${runId}/trips`, { method: 'POST', companyId, body: request })
}

export function fetchTrip(companyId: string, id: string, signal?: AbortSignal): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${id}`, { companyId, signal })
}

export function fetchTripCapacity(companyId: string, id: string, signal?: AbortSignal): Promise<TripCapacityView> {
  return apiRequest<TripCapacityView>(`/planning/trips/${id}/capacity`, { companyId, signal })
}

/** Mirrors the backend's `TripVehicleRequest` record. The carrier is never sent - it is
 * resolved from the vehicle server-side. */
export interface TripVehicleRequest {
  vehicleId: string
  plannedDepartureAt?: string | null
  version: number
}

export function updateTripVehicle(
  companyId: string, id: string, request: TripVehicleRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${id}/vehicle`, { method: 'PUT', companyId, body: request })
}

/** Mirrors the backend's `TripDriverRequest` record.
 *
 * `driverId` is nullable and that is the whole difference from `TripVehicleRequest`: sending null
 * clears the assignment, which is a real state a dispatcher records ("the driver we had is off"),
 * and releases the person for another trip that day. */
export interface TripDriverRequest {
  driverId: string | null
  version: number
}

/** Unlike the vehicle, this stays available after confirmation and up to departure - a driver
 * calling in sick at 05:00 is not a re-plan. The server refuses it once the truck has left. */
export function updateTripDriver(
  companyId: string, id: string, request: TripDriverRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${id}/driver`, { method: 'PUT', companyId, body: request })
}

/**
 * Mirrors the backend's `AssignOrderRequest` record.
 *
 * Sin cantidades significa "todo lo que queda por planificar de este pedido", que es el caso
 * ordinario. Con cantidades es un reparto (migración V37): 70 de los 100 pallets suben a este
 * camión y el resto espera otro.
 *
 * El servidor nunca se fía de estas cifras como carga: rechaza la asignación si excede lo
 * pendiente, en el servicio y otra vez en `ck_transport_order_not_over_allocated`. Ver
 * `docs/domain/CAPACITY_MODEL.md`, "The frontend is never trusted".
 */
export interface AssignOrderRequest {
  orderId: string
  weightKg?: number
  volumeM3?: number
  pallets?: number
}

export function assignOrderToTrip(
  companyId: string, tripId: string, request: AssignOrderRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${tripId}/assignments`, { method: 'POST', companyId, body: request })
}

export function removeOrderFromTrip(
  companyId: string, tripId: string, orderId: string, reason?: string,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${tripId}/assignments/${orderId}`, {
    method: 'DELETE', companyId, query: reason ? { reason } : undefined,
  })
}

/** Mirrors the backend's `MoveOrderRequest` record. Takes no version: moves are serialised by
 * the two trips' row locks and the assignment uniqueness invariant. */
export interface MoveOrderRequest {
  targetTripId: string
}

export function moveOrderToTrip(
  companyId: string, tripId: string, orderId: string, request: MoveOrderRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${tripId}/assignments/${orderId}/move`, {
    method: 'POST', companyId, body: request,
  })
}

/** Mirrors the backend's `TripStopOrderRequest` record: must be exactly the destinations the
 * trip currently serves, in the desired sequence - sequence numbers are server-assigned. */
export interface TripStopOrderRequest {
  destinationIds: string[]
}

export function reorderTripStops(
  companyId: string, tripId: string, request: TripStopOrderRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${tripId}/stops`, { method: 'PUT', companyId, body: request })
}

/** Mirrors the backend's `TripRouteRequest` record.
 *
 * The route is a *suggestion*: sending it never changes which destinations the shipment serves.
 * `applySequence` asks the backend to reorder the stops the shipment already has into the route's
 * order - destinations the route omits are kept, at the end. Send `routeId: null` to clear the
 * reference. */
export interface TripRouteRequest {
  routeId: string | null
  applySequence: boolean
  version: number
}

export function updateTripRoute(
  companyId: string, id: string, request: TripRouteRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${id}/route`, { method: 'PUT', companyId, body: request })
}

export function cancelTrip(
  companyId: string, id: string, request: PlanningActionRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${id}/cancel`, { method: 'POST', companyId, body: request })
}

// --- trip execution --------------------------------------------------------------------
//
// The Trips screen (`docs/domain/TRIP_EXECUTION_V1.md`): a dispatcher's view of the day, indexed
// by date rather than by planning run, plus the three transitions that move a shipment through it.

export interface TripListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  shipmentNumber?: string
  status?: TripStatus
  originId?: string
  carrierId?: string
  vehicleId?: string
  /** "Where is Ana today" - narrows the board to one driver, cancelled trips included. */
  driverId?: string
  planningDateFrom?: string
  planningDateTo?: string
  signal?: AbortSignal
}

export function fetchTrips(params: TripListParams): Promise<PageResponse<TripView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<TripView>>('/planning/trips', { companyId, signal, query })
}

/** Mirrors the backend's `TripExecutionRequest` record.
 *
 * `occurredAt` is the *business* time - when the truck actually left, not when the button was
 * pressed. Omit it and the server stamps its own clock, which is what a dispatcher acting live
 * means; send it to record something that happened earlier. The server refuses a future time and
 * one that would put the lifecycle out of order. */
export interface TripExecutionRequest {
  version: number
  occurredAt?: string | null
}

export function markTripReady(
  companyId: string, id: string, request: TripExecutionRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${id}/ready`, { method: 'POST', companyId, body: request })
}

export function dispatchTrip(
  companyId: string, id: string, request: TripExecutionRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${id}/dispatch`, { method: 'POST', companyId, body: request })
}

export function completeTrip(
  companyId: string, id: string, request: TripExecutionRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${id}/complete`, { method: 'POST', companyId, body: request })
}

// --- stop execution, timeline and exceptions (migration V27) ----------------------------
//
// What happens at each stop of a trip that is out, and the record it leaves behind. None of the
// stop actions takes a `version`: they are serialised by the trip's row lock and guarded by the
// stop transition table, the same rule the assignment endpoints follow. Every one of them returns
// the whole trip, so the screen re-renders from one answer instead of patching its own state.

/** Mirrors the backend's `TripStopExecutionRequest` record.
 *
 * `occurredAt` is the business time, as everywhere else in execution: omit it and the server
 * stamps its own clock. */
export interface TripStopExecutionRequest {
  occurredAt?: string | null
  notes?: string | null
}

/** Mirrors the backend's `TripStopFailureRequest` record - a stop that was not served.
 *
 * `exceptionType` is mandatory and opens a `TripException` of that type, which is what makes
 * "how many deliveries did we miss, and why" a query rather than a reading exercise. */
export interface TripStopFailureRequest {
  occurredAt?: string | null
  exceptionType: TripExceptionType
  notes?: string | null
}

export function arriveAtStop(
  companyId: string, tripId: string, stopId: string, request: TripStopExecutionRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${tripId}/stops/${stopId}/arrive`,
    { method: 'POST', companyId, body: request })
}

export function startStopService(
  companyId: string, tripId: string, stopId: string, request: TripStopExecutionRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${tripId}/stops/${stopId}/service`,
    { method: 'POST', companyId, body: request })
}

export function completeStop(
  companyId: string, tripId: string, stopId: string, request: TripStopExecutionRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${tripId}/stops/${stopId}/complete`,
    { method: 'POST', companyId, body: request })
}

export function skipStop(
  companyId: string, tripId: string, stopId: string, request: TripStopFailureRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${tripId}/stops/${stopId}/skip`,
    { method: 'POST', companyId, body: request })
}

export function failStop(
  companyId: string, tripId: string, stopId: string, request: TripStopFailureRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${tripId}/stops/${stopId}/fail`,
    { method: 'POST', companyId, body: request })
}

/** The trip's timeline, oldest first. A read of the trip, so `planning.trip:read` is enough. */
export function fetchTripEvents(
  companyId: string, tripId: string, signal?: AbortSignal,
): Promise<TransportEventView[]> {
  return apiRequest<TransportEventView[]>(`/planning/trips/${tripId}/events`, { companyId, signal })
}

/** Mirrors the backend's `TripExceptionRequest` record. */
export interface TripExceptionRequest {
  tripStopId?: string | null
  exceptionType: TripExceptionType
  occurredAt?: string | null
  notes?: string | null
}

/** Mirrors the backend's `TripExceptionResolutionRequest` record. `notes` is required: "RESOLVED"
 * with no explanation says a row was clicked, not that anything was done. */
export interface TripExceptionResolutionRequest {
  occurredAt?: string | null
  notes: string
}

export function reportTripException(
  companyId: string, tripId: string, request: TripExceptionRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${tripId}/exceptions`,
    { method: 'POST', companyId, body: request })
}

export function resolveTripException(
  companyId: string, tripId: string, exceptionId: string, request: TripExceptionResolutionRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(`/planning/trips/${tripId}/exceptions/${exceptionId}/resolve`,
    { method: 'POST', companyId, body: request })
}

// --- delivery results and proof of delivery (migration V28) ---------------------------
//
// What was handed over at a stop, one order at a time, and the artefacts backing it up. Recording
// takes `planning.trip:execute` like the stop actions; reading and downloading take
// `planning.trip:read` plus `orders.order:read`, because a delivery names an order.

/** Mirrors the backend's `DeliveryResultRequest` record.
 *
 * A `PUT` carrying the *whole* state of one delivery, not a patch: an omitted `receiverName` means
 * there is no receiver, which is the only way a name typed by mistake can be removed. Which
 * combinations are legal is the server's answer - see `DELIVERY_RESULTS_NEEDING_NOTES` and its
 * siblings for the copies this client keeps only to shape the form. */
export interface DeliveryResultRequest {
  result: DeliveryResult
  deliveredAt?: string | null
  receiverName?: string | null
  receiverDocument?: string | null
  notes?: string | null
}

export function recordDelivery(
  companyId: string, tripId: string, stopId: string, orderId: string, request: DeliveryResultRequest,
): Promise<TripDetailView> {
  return apiRequest<TripDetailView>(
    `/planning/trips/${tripId}/stops/${stopId}/orders/${orderId}/delivery`,
    { method: 'PUT', companyId, body: request })
}

/** Attaches one artefact to a recorded delivery.
 *
 * Answers 503 (`storage-unavailable`) where the deployment has no evidence store configured, which
 * is the default. That is not a fault and the UI says so rather than showing an error: the delivery
 * *result* is the fact and is recorded either way. */
export function uploadDeliveryEvidence(
  companyId: string,
  tripId: string,
  deliveryId: string,
  input: { evidenceType: EvidenceType; capturedAt?: string | null; file: File },
): Promise<TripDetailView> {
  const formData = new FormData()
  formData.append('evidenceType', input.evidenceType)
  if (input.capturedAt) {
    formData.append('capturedAt', input.capturedAt)
  }
  formData.append('file', input.file)
  return apiUpload<TripDetailView>(`/planning/trips/${tripId}/deliveries/${deliveryId}/evidence`,
    { companyId, formData })
}

/** The bytes of one artefact, as a blob plus the name the server suggested. */
export function downloadDeliveryEvidence(
  companyId: string, tripId: string, deliveryId: string, evidenceId: string,
): Promise<DownloadedFile> {
  return apiDownload(`/planning/trips/${tripId}/deliveries/${deliveryId}/evidence/${evidenceId}/content`,
    { companyId })
}
