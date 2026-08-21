import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `PlanningRunStatus` enum (`planning/domain/PlanningRunStatus.java`). */
export type PlanningRunStatus = 'DRAFT' | 'CONFIRMED' | 'CANCELLED'

export const PLANNING_RUN_STATUSES: PlanningRunStatus[] = ['DRAFT', 'CONFIRMED', 'CANCELLED']

/** Mirrors the backend's `PlanningMode` enum. Only `MANUAL` is reachable in V1. */
export type PlanningMode = 'MANUAL' | 'AUTOMATIC'

/** Mirrors the backend's `TripStatus` enum (`planning/domain/TripStatus.java`). */
export type TripStatus = 'DRAFT' | 'CONFIRMED' | 'CANCELLED'

export const TRIP_STATUSES: TripStatus[] = ['DRAFT', 'CONFIRMED', 'CANCELLED']

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
  routeId: string | null
  routeCode: string | null
  routeName: string | null
  plannedDepartureAt: string | null
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
}

/** Mirrors the backend's `TripDetailView` record. */
export interface TripDetailView {
  trip: TripView
  assignments: TripAssignmentView[]
  stops: TripStopView[]
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

/** Mirrors the backend's `AssignOrderRequest` record. No quantities: the backend snapshots the
 * order's own totals - see `docs/domain/CAPACITY_MODEL.md`, "The frontend is never trusted". */
export interface AssignOrderRequest {
  orderId: string
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
