import { apiDownload, apiRequest, type DownloadedFile } from './httpClient'

//
// Costo para servir (migración V68, ADR-026): el costo de cada viaje repartido entre sus pedidos
// por la base que eligió la empresa, versionado (recalcular crea una versión nueva, no pisa), y el
// reporte por cliente, destino o ruta. **Sin margen**: TMS no tiene tarifa de venta.
//

/** Mirrors `CostToServeQueries.GroupBy`. */
export const COST_TO_SERVE_GROUPS = ['CUSTOMER', 'DESTINATION', 'ROUTE'] as const
export type CostToServeGroupBy = (typeof COST_TO_SERVE_GROUPS)[number]

/** Mirrors `CostAllocationBasis`. */
export const COST_ALLOCATION_BASES = ['WEIGHT', 'EFFECTIVE_VOLUME', 'FLOOR_AREA', 'PALLETS', 'STOPS', 'EQUAL'] as const
export type CostAllocationBasis = (typeof COST_ALLOCATION_BASES)[number]

/** Mirrors `AllocationCostSource`. */
export type AllocationCostSource = 'INVOICED' | 'ACTUAL' | 'ESTIMATED'

/** Mirrors `CostToServeView.Group`: un grupo en una moneda. `key`/`label` null = «sin cliente/destino/ruta». */
export interface CostToServeGroup {
  key: string | null
  label: string | null
  currency: string
  trips: number
  orders: number
  unallocatedOrders: number
  cost: number
  weightKg: number
  volumeM3: number
  costPerKg: number | null
  costPerM3: number | null
  costPerOrder: number | null
}

/** Mirrors `CostToServeView.Totals`: el rango en una moneda, por viaje. */
export interface CostToServeTotals {
  currency: string
  trips: number
  cost: number
  allocated: number
  tripsNotAllocated: number
  unallocatedOrders: number
  invoicedTrips: number
  actualTrips: number
  estimatedTrips: number
  costPerKg: number | null
}

/** Mirrors `CostToServeView`. `marginAvailable` es siempre false: no hay tarifa de venta. */
export interface CostToServeView {
  from: string
  to: string
  groupBy: CostToServeGroupBy
  basis: CostAllocationBasis
  marginAvailable: boolean
  marginNote: string
  totals: CostToServeTotals[]
  groups: CostToServeGroup[]
}

export interface CostToServeQuery {
  from: string
  to: string
  groupBy: CostToServeGroupBy
}

export function fetchCostToServe(companyId: string, query: CostToServeQuery, signal?: AbortSignal): Promise<CostToServeView> {
  return apiRequest<CostToServeView>('/settlement/cost-to-serve', {
    companyId, signal, query: { from: query.from, to: query.to, groupBy: query.groupBy },
  })
}

export function downloadCostToServe(companyId: string, query: CostToServeQuery, format: 'CSV' | 'JSON'): Promise<DownloadedFile> {
  return apiDownload('/settlement/cost-to-serve/export', {
    companyId, query: { from: query.from, to: query.to, groupBy: query.groupBy, format },
  })
}

/** Mirrors `CostAllocationView.LineView`. `allocatedAmount` null con `reason`: no se pudo medir. */
export interface CostAllocationLineView {
  orderId: string
  orderNumber: string
  customerLabel: string | null
  destinationLabel: string | null
  weightKg: number
  volumeM3: number
  pallets: number
  floorAreaM2: number
  basisQuantity: number | null
  share: number | null
  allocatedAmount: number | null
  reason: string | null
}

/** Mirrors `CostAllocationView.RunView`. */
export interface CostAllocationRunView {
  id: string
  version: number
  basis: CostAllocationBasis
  costSource: AllocationCostSource
  currency: string
  costAmount: number
  allocatedAmount: number
  orderCount: number
  unallocatedOrderCount: number
  unallocatedReason: string | null
  serviceDate: string
  shipmentNumber: string | null
  routeLabel: string | null
  trigger: string
  computedAt: string
  computedBy: string | null
  supersededAt: string | null
  lines: CostAllocationLineView[]
}

export interface CostAllocationView {
  tripId: string
  current: CostAllocationRunView | null
  versions: CostAllocationRunView[]
}

export function fetchTripAllocation(companyId: string, tripId: string, signal?: AbortSignal): Promise<CostAllocationView> {
  return apiRequest<CostAllocationView>(`/settlement/cost-allocation/trips/${tripId}`, { companyId, signal })
}

export function recalculateTripAllocation(companyId: string, tripId: string): Promise<CostAllocationView> {
  return apiRequest<CostAllocationView>(`/settlement/cost-allocation/trips/${tripId}/recalculate`, {
    method: 'POST', companyId,
  })
}
