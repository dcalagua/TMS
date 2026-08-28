import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/** Mirrors the backend's `RateCardScope`. */
export const RATE_CARD_SCOPES = ['CARRIER', 'ORIGIN', 'LANE', 'ROUTE'] as const
export type RateCardScope = (typeof RATE_CARD_SCOPES)[number]

/** Mirrors the backend's `RateComponent`, in the order an estimate is shown in. */
/**
 * Mirrors `RateComponent`, **in the order an estimate is calculated and shown** - which is part of
 * the meaning, not a display preference. See `docs/domain/RATE_ENGINE_V2.md`: the fuel surcharge is
 * a percentage of the linehaul above it and of nothing below it.
 */
export const RATE_COMPONENTS = [
  'BASE', 'DISTANCE', 'WEIGHT', 'VOLUME', 'PALLETS', 'STOP_OFF',
  'FUEL_SURCHARGE',
  'WAITING_TIME', 'TOLL', 'OTHER_ACCESSORIAL',
  'MINIMUM_ADJUSTMENT', 'MAXIMUM_ADJUSTMENT',
] as const
export type RateComponent = (typeof RATE_COMPONENTS)[number]

export type CostComponentStatus = 'APPLIED' | 'NOT_CALCULABLE'
export type CostUnit = 'KM' | 'KG' | 'M3' | 'PALLET' | 'STOP' | 'HOUR' | 'PERCENT'

/** Mirrors the backend's `CostQuantitySource` - where a line's quantity came from. */
export const COST_QUANTITY_SOURCES = [
  'ROUTE_REFERENCE', 'ORDER_DECLARED_TOTALS', 'MEASURED_ROUTE', 'TRIP_STOPS', 'LINEHAUL_SUBTOTAL',
  'RECORDED_WAITING',
] as const
export type CostQuantitySource = (typeof COST_QUANTITY_SOURCES)[number]

/** Mirrors the backend's `CostComponentReason` - why a line could not be calculated. */
export const COST_COMPONENT_REASONS = [
  'DISTANCE_UNKNOWN',
  'WEIGHT_UNKNOWN',
  'VOLUME_UNKNOWN',
  'PALLETS_UNKNOWN',
] as const
export type CostComponentReason = (typeof COST_COMPONENT_REASONS)[number]

/** Mirrors the backend's `RateCardView` record. */
export interface RateCardView {
  id: string
  code: string
  name: string
  carrierId: string
  carrierCode: string | null
  carrierName: string | null
  scope: RateCardScope
  scopeTargetId: string | null
  scopeTargetCode: string | null
  scopeTargetName: string | null
  vehicleTypeId: string | null
  vehicleTypeCode: string | null
  vehicleTypeName: string | null
  currency: string
  validFrom: string
  validTo: string | null
  baseAmount: number | null
  amountPerKm: number | null
  amountPerKg: number | null
  amountPerM3: number | null
  amountPerPallet: number | null
  minimumAmount: number | null
  /** El extremo lejano del carril. Solo para scope LANE (V39). */
  destinationId: string | null
  destinationCode: string | null
  destinationName: string | null
  amountPerStop: number | null
  /** Porcentaje del flete base, nunca de los accesorios. */
  fuelSurchargePercent: number | null
  amountPerWaitingHour: number | null
  tollAmount: number | null
  accessorialAmount: number | null
  /** El nombre bajo el que aparece el accesorio. Viaja con el importe o no viaja. */
  accessorialLabel: string | null
  maximumAmount: number | null
  active: boolean
  createdAt: string
  updatedAt: string
}

/** Mirrors the backend's `RateCardRequest` record - shared shape for create and update. */
export interface RateCardRequest {
  code: string
  name: string
  carrierId: string
  scope: RateCardScope
  originId?: string | null
  routeId?: string | null
  vehicleTypeId?: string | null
  currency: string
  validFrom: string
  validTo?: string | null
  baseAmount?: number | null
  amountPerKm?: number | null
  amountPerKg?: number | null
  amountPerM3?: number | null
  amountPerPallet?: number | null
  minimumAmount?: number | null
  destinationId?: string | null
  amountPerStop?: number | null
  fuelSurchargePercent?: number | null
  amountPerWaitingHour?: number | null
  tollAmount?: number | null
  accessorialAmount?: number | null
  accessorialLabel?: string | null
  maximumAmount?: number | null
}

export interface RateCardListParams {
  companyId: string
  page?: number
  size?: number
  sort?: string
  code?: string
  name?: string
  carrierId?: string
  scope?: RateCardScope
  vehicleTypeId?: string
  currency?: string
  /** ISO date; keeps only the cards in force that day. */
  onDate?: string
  active?: boolean
  signal?: AbortSignal
}

export function fetchRateCards(params: RateCardListParams): Promise<PageResponse<RateCardView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<RateCardView>>('/rates/rate-cards', { companyId, signal, query })
}

export function createRateCard(companyId: string, request: RateCardRequest): Promise<RateCardView> {
  return apiRequest<RateCardView>('/rates/rate-cards', { method: 'POST', companyId, body: request })
}

export function updateRateCard(companyId: string, id: string, request: RateCardRequest): Promise<RateCardView> {
  return apiRequest<RateCardView>(`/rates/rate-cards/${id}`, { method: 'PUT', companyId, body: request })
}

export function activateRateCard(companyId: string, id: string): Promise<RateCardView> {
  return apiRequest<RateCardView>(`/rates/rate-cards/${id}/activate`, { method: 'POST', companyId })
}

export function deactivateRateCard(companyId: string, id: string): Promise<RateCardView> {
  return apiRequest<RateCardView>(`/rates/rate-cards/${id}/deactivate`, { method: 'POST', companyId })
}

// --- trip cost -------------------------------------------------------------------------

/** Mirrors the backend's `TripCostComponentView` record - one line of an estimate. */
export interface TripCostComponentView {
  component: RateComponent
  status: CostComponentStatus
  rate: number | null
  quantity: number | null
  unit: CostUnit | null
  quantitySource: CostQuantitySource | null
  amount: number
  reason: CostComponentReason | null
}

/**
 * Mirrors the backend's `TripCostView` record.
 *
 * `priced` is false for a trip nobody has costed - the endpoint answers 200 with this shape
 * rather than 404, so the screen renders "not priced yet" instead of an error.
 */
export interface TripCostView {
  tripId: string
  priced: boolean
  currency: string | null
  estimatedAmount: number | null
  estimatedAt: string | null
  rateCardId: string | null
  rateCardCode: string | null
  rateCardName: string | null
  rateCardScope: RateCardScope | null
  estimateComplete: boolean
  components: TripCostComponentView[]
  actualAmount: number | null
  actualReference: string | null
  actualNotes: string | null
  actualRecordedAt: string | null
  variance: number | null
  closed: boolean
  closedAt: string | null
  updatedAt: string | null
}

/** Mirrors the backend's `ActualCostRequest` record. */
export interface ActualCostRequest {
  amount: number
  /** Required only when the trip has no estimate to inherit a currency from. */
  currency?: string | null
  reference?: string | null
  notes?: string | null
}

export function fetchTripCost(companyId: string, tripId: string, signal?: AbortSignal): Promise<TripCostView> {
  return apiRequest<TripCostView>(`/rates/trips/${tripId}/cost`, { companyId, signal })
}

export function estimateTripCost(companyId: string, tripId: string): Promise<TripCostView> {
  return apiRequest<TripCostView>(`/rates/trips/${tripId}/cost/estimate`, { method: 'POST', companyId })
}

export function recordActualTripCost(
  companyId: string,
  tripId: string,
  request: ActualCostRequest,
): Promise<TripCostView> {
  return apiRequest<TripCostView>(`/rates/trips/${tripId}/cost/actual`, { method: 'PUT', companyId, body: request })
}

export function closeTripCost(companyId: string, tripId: string): Promise<TripCostView> {
  return apiRequest<TripCostView>(`/rates/trips/${tripId}/cost/close`, { method: 'POST', companyId })
}

export function reopenTripCost(companyId: string, tripId: string): Promise<TripCostView> {
  return apiRequest<TripCostView>(`/rates/trips/${tripId}/cost/reopen`, { method: 'POST', companyId })
}
