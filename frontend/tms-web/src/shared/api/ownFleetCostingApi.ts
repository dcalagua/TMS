import { apiRequest } from './httpClient'

/** Mirrors `TransportCostNature` (migración V48). */
export type TransportCostNature = 'EXTERNAL_CARRIER_PRICE' | 'OWN_FLEET_INTERNAL_COST'

/** Mirrors `OwnFleetComponent`. */
export type OwnFleetComponent =
  | 'FIXED_TRIP'
  | 'FUEL_PER_KM'
  | 'DRIVER_PER_HOUR'
  | 'VEHICLE_PER_HOUR'
  | 'MAINTENANCE_PER_KM'
  | 'DEPRECIATION_PER_KM'
  | 'TOLL'

/** Mirrors `OwnFleetQuantitySource`. De dónde salió la cantidad de cada línea. */
export type OwnFleetQuantitySource =
  | 'MEASURED_ROUTE'
  | 'STRAIGHT_LINE_ESTIMATE'
  | 'TRIP_EXECUTION_WINDOW'
  /** Ejecución **más** el reposicionamiento de entrada (V47). */
  | 'RESOURCE_DUTY_WINDOW'
  | 'PROFILE_FLAT'

/** Mirrors `OwnFleetCostReason`. Por qué falta el total de una estimación que sí existe. */
export type OwnFleetCostReason = 'DISTANCE_UNKNOWN' | 'DUTY_UNKNOWN'

/** Mirrors `OwnFleetQuoteUnavailable`. Por qué no hay estimación en absoluto. */
export type OwnFleetQuoteUnavailable =
  | 'NO_VEHICLE_ASSIGNED'
  | 'NOT_OWN_FLEET'
  /** Nadie configuró tarifas para ese camión. **No es costo cero.** */
  | 'NO_PROFILE_IN_FORCE'

/** Mirrors `OwnFleetProfileState`. */
export type OwnFleetProfileState = 'ACTIVE' | 'INCOMPLETE' | 'EXPIRED' | 'FUTURE' | 'INACTIVE'

export interface OwnFleetCostProfileView {
  id: string
  vehicleId: string | null
  vehicleLabel: string | null
  vehicleTypeId: string | null
  vehicleTypeLabel: string | null
  currency: string
  effectiveFrom: string
  effectiveTo: string | null
  active: boolean
  state: OwnFleetProfileState
  /**
   * Cada tarifa es `null` cuando **el perfil no cobra ese componente**, y `0` cuando lo cobra a
   * cero. No son lo mismo y la pantalla no puede colapsarlos: un `0` escrito donde iba un vacío
   * convierte "no lo modelamos" en "lo modelamos en nada".
   */
  fixedTripAmount: number | null
  fuelPerKm: number | null
  driverPerHour: number | null
  vehiclePerHour: number | null
  maintenancePerKm: number | null
  depreciationPerKm: number | null
  tollAmount: number | null
  /** Si este perfil necesita una distancia antes de poder dar un total comparable. */
  needsDistance: boolean
  needsDuty: boolean
  notes: string | null
}

export interface OwnFleetQuoteLine {
  component: OwnFleetComponent
  status: 'APPLIED' | 'NOT_CALCULABLE'
  rate: number | null
  quantity: number | null
  unit: 'KM' | 'HOUR' | null
  quantitySource: OwnFleetQuantitySource | null
  amount: number
  reason: OwnFleetCostReason | null
}

export interface OwnFleetQuoteView {
  tripId: string
  /** Siempre `OWN_FLEET_INTERNAL_COST`. Viaja explícito para que nadie lo confunda con un precio. */
  nature: TransportCostNature
  currency: string | null
  /**
   * **Null cuando falta el insumo de algún componente que el perfil sí cobra.** Nunca un cero en su
   * lugar, y nunca una suma parcial ascendida a total: un plan no debe parecer barato por no poder
   * costear sus propios costos.
   */
  comparableTotal: number | null
  /** Lo que suman las líneas calculables. Para diagnosticar, jamás para decidir. */
  partialSubtotal: number | null
  complete: boolean
  profileId: string | null
  profileScope: 'VEHICLE' | 'VEHICLE_TYPE' | null
  blockingReasons: OwnFleetCostReason[]
  unavailableReason: OwnFleetQuoteUnavailable | null
  lines: OwnFleetQuoteLine[]
}

export interface OwnFleetCostProfileRequest {
  vehicleId?: string | null
  vehicleTypeId?: string | null
  currency: string
  effectiveFrom: string
  effectiveTo?: string | null
  fixedTripAmount?: number | null
  fuelPerKm?: number | null
  driverPerHour?: number | null
  vehiclePerHour?: number | null
  maintenancePerKm?: number | null
  depreciationPerKm?: number | null
  tollAmount?: number | null
  notes?: string | null
}

const BASE = '/costing/own-fleet'

export function listOwnFleetProfiles(companyId: string): Promise<OwnFleetCostProfileView[]> {
  return apiRequest<OwnFleetCostProfileView[]>(`${BASE}/profiles`, { companyId })
}

export function createOwnFleetProfile(
  companyId: string, request: OwnFleetCostProfileRequest,
): Promise<OwnFleetCostProfileView> {
  return apiRequest<OwnFleetCostProfileView>(`${BASE}/profiles`, {
    method: 'POST', companyId, body: request,
  })
}

export function updateOwnFleetProfile(
  companyId: string, id: string, request: OwnFleetCostProfileRequest,
): Promise<OwnFleetCostProfileView> {
  return apiRequest<OwnFleetCostProfileView>(`${BASE}/profiles/${id}`, {
    method: 'PUT', companyId, body: request,
  })
}

export function setOwnFleetProfileActive(
  companyId: string, id: string, active: boolean,
): Promise<OwnFleetCostProfileView> {
  return apiRequest<OwnFleetCostProfileView>(`${BASE}/profiles/${id}/active`, {
    method: 'PUT', companyId, body: { active },
  })
}

/** Responde 200 con un motivo cuando no hay costo. Un 404 dejaría a la pantalla sin qué decir. */
export function quoteOwnFleetTrip(companyId: string, tripId: string): Promise<OwnFleetQuoteView> {
  return apiRequest<OwnFleetQuoteView>(`${BASE}/trips/${tripId}/quote`, { companyId })
}
