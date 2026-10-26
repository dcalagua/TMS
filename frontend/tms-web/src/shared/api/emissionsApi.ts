import { apiDownload, apiRequest, type DownloadedFile } from './httpClient'

/**
 * Huella de carbono (migración V73): kg CO₂e de un viaje (su distancia × el factor del tipo de
 * vehículo), repartidos entre sus pedidos con la misma base del costo para servir, y el informe por
 * cliente, destino o ruta. Se calcula al leer y no se guarda.
 *
 * **Sin factor no hay cifra**: `status` distinto de `CALCULATED` es «sin datos», nunca cero.
 */

/** Mirrors `EmissionCalculator.Status`. */
export type EmissionStatus = 'CALCULATED' | 'NO_VEHICLE' | 'NO_FACTOR' | 'NO_DISTANCE'

/** Mirrors `FuelType` (V73). */
export type FuelType = 'DIESEL' | 'GASOLINE' | 'LPG' | 'CNG' | 'ELECTRIC' | 'HYBRID' | 'OTHER'

export const FUEL_TYPES: FuelType[] = ['DIESEL', 'GASOLINE', 'LPG', 'CNG', 'ELECTRIC', 'HYBRID', 'OTHER']

export interface OrderEmission {
  assignmentId: string
  orderId: string
  orderNumber: string
  customerLabel: string | null
  destinationId: string | null
  destinationLabel: string | null
  basisQuantity: number | null
  share: number | null
  /** Null cuando el pedido no se pudo medir en la base (o el viaje no tiene cifra). */
  kgCo2e: number | null
}

/** Mirrors `TripEmissionView`. */
export interface TripEmissionView {
  tripId: string
  shipmentNumber: string | null
  status: EmissionStatus
  kgCo2e: number | null
  distanceKm: number | null
  /** Algún tramo salió del estimador local: la huella también es estimada. */
  estimated: boolean
  unmeasurableLegs: number
  provider: string | null
  vehiclePlate: string | null
  vehicleTypeId: string | null
  vehicleTypeCode: string | null
  vehicleTypeName: string | null
  fuelType: FuelType | null
  factorKgCo2ePerKm: number | null
  basis: string
  weightKg: number | null
  gramsPerTonneKm: number | null
  unallocatedOrderCount: number
  unallocatedReason: string | null
  orders: OrderEmission[]
}

export type EmissionGroupBy = 'CUSTOMER' | 'DESTINATION' | 'ROUTE'

export interface EmissionReportView {
  from: string
  to: string
  groupBy: EmissionGroupBy
  basis: string
  totals: {
    trips: number
    tripsCalculated: number
    tripsEstimated: number
    tripsWithoutFactor: number
    tripsWithoutDistance: number
    tripsWithoutVehicle: number
    kgCo2e: number
    distanceKm: number
    unallocatedKgCo2e: number
  }
  groups: {
    key: string | null
    label: string | null
    trips: number
    orders: number
    kgCo2e: number
    weightKg: number
    kgCo2ePerTonne: number | null
  }[]
  /** El período tenía más viajes de los que lee un informe: acortar el rango. */
  truncated: boolean
}

export interface EmissionQuery {
  from: string
  to: string
  groupBy: EmissionGroupBy
}

export function fetchTripEmissions(companyId: string, tripId: string, signal?: AbortSignal): Promise<TripEmissionView> {
  return apiRequest<TripEmissionView>(`/settlement/emissions/trips/${tripId}`, { companyId, signal })
}

export function fetchEmissionReport(companyId: string, query: EmissionQuery, signal?: AbortSignal): Promise<EmissionReportView> {
  return apiRequest<EmissionReportView>('/settlement/emissions', {
    companyId, signal, query: { from: query.from, to: query.to, groupBy: query.groupBy },
  })
}

export function downloadEmissionReport(companyId: string, query: EmissionQuery, format: 'CSV' | 'JSON'): Promise<DownloadedFile> {
  return apiDownload('/settlement/emissions/export', {
    companyId, query: { from: query.from, to: query.to, groupBy: query.groupBy, format },
  })
}
