import type { DispatchConfirmationMode } from './administrationApi'
import { apiRequest } from './httpClient'

//
// The trip workspace's warehouse card (ADR-013, `docs/integrations/WAREHOUSE_EXECUTION_V1.md`):
// how a shipment departed, what the warehouse system (WMS) reported, and how the two compare.
//
// Plan and reality side by side, never merged: nothing here rewrites the plan, and nothing a
// warehouse sends moves `TripStatus` except a dispatch the company's mode allows.
//

/** Who moved the trip to `IN_TRANSIT`. Null while it has not departed. */
export type DispatchSource = 'OPERATOR' | 'INTEGRATION' | 'OPERATOR_OVERRIDE'

/**
 * How the warehouse's dispatch compares with the plan. `UNVERIFIED` means no current document;
 * `OVERRIDDEN` means a person dispatched past a required confirmation.
 */
export type DispatchVerificationStatus = 'UNVERIFIED' | 'MATCHED' | 'MISMATCH' | 'OVERRIDDEN'

/** What TMS did with one dispatch document (WAREHOUSE_EXECUTION_V1 §4.1). */
export type DispatchDocumentOutcome = 'APPLIED' | 'RECONCILED' | 'UNAPPLIED' | 'RECORDED_UNMATCHED'

export type DiscrepancySeverity = 'ERROR' | 'WARNING' | 'INFO'

/** How one order of the document compares with the trip's assignments. */
export type DispatchOrderMatch = 'MATCHED' | 'VARIANCE' | 'UNCOMPARABLE' | 'EXTRA' | 'UNKNOWN'

export type WarehouseMilestoneType = 'LOADING_STARTED' | 'LOAD_READY' | 'LOAD_CANCELLED'

/**
 * One difference between the plan and what left. `code` is kept a string: the catalogue
 * (ADR-013 §5/§13) grows, and an unknown code must still render with its raw value.
 */
export interface DispatchDiscrepancyView {
  code: string
  severity: DiscrepancySeverity | string
  orderReference: string | null
  lineNumber: number | null
  planned: number | null
  dispatched: number | null
  uom: string | null
  detail: string | null
}

export interface DispatchDocumentOrderView {
  orderId: string | null
  externalSource: string | null
  externalReference: string | null
  warehouseOrderNumber: string | null
  status: string | null
  handlingUnits: number | null
  weightKg: number | null
  volumeM3: number | null
  matchResult: DispatchOrderMatch | string
}

/** Mirrors `TripWarehouseView.Document` - one dispatch document (SLS) as the warehouse sent it. */
export interface DispatchDocumentView {
  id: string
  sourceSystem: string
  dispatchReference: string
  revision: number
  /** Only the current revision of each reference counts toward the trip's verification. */
  current: boolean
  outcome: DispatchDocumentOutcome | string
  verificationStatus: DispatchVerificationStatus | string
  loadReference: string | null
  warehouseCode: string | null
  actualDispatchAt: string | null
  carrierCode: string | null
  carrierName: string | null
  vehicleLicensePlate: string | null
  driverName: string | null
  driverDocumentNumber: string | null
  sealNumber: string | null
  transportDocumentNumber: string | null
  totalHandlingUnits: number | null
  totalWeightKg: number | null
  totalVolumeM3: number | null
  receivedAt: string
  discrepancies: DispatchDiscrepancyView[]
  orders: DispatchDocumentOrderView[]
}

export interface WarehouseMilestoneView {
  type: WarehouseMilestoneType | string
  eventId: string
  loadReference: string | null
  warehouseCode: string | null
  occurredAt: string
  receivedAt: string
}

/** Mirrors the backend's `TripWarehouseView`. Requires `planning.trip:read`. */
export interface TripWarehouseView {
  tripId: string
  shipmentNumber: string
  dispatchConfirmationMode: DispatchConfirmationMode
  dispatchSource: DispatchSource | null
  actualDepartureAt: string | null
  verificationStatus: DispatchVerificationStatus
  /** Newest first is not promised: the screen sorts by current revision, then by `receivedAt`. */
  documents: DispatchDocumentView[]
  milestones: WarehouseMilestoneView[]
}

export function fetchTripWarehouse(
  companyId: string, tripId: string, signal?: AbortSignal,
): Promise<TripWarehouseView> {
  return apiRequest<TripWarehouseView>(`/planning/trips/${tripId}/warehouse`, { companyId, signal })
}

/**
 * The dispatch document exactly as the warehouse sent it, as text. The endpoint produces
 * `text/plain`, so the request must accept it or the server answers 406; problem+json stays
 * acceptable so that a failure still arrives as Problem Details.
 */
export function fetchTripWarehouseRawDocument(
  companyId: string, tripId: string, documentId: string, signal?: AbortSignal,
): Promise<string> {
  return apiRequest<string>(`/planning/trips/${tripId}/warehouse/documents/${documentId}/raw`, {
    companyId,
    signal,
    accept: 'text/plain, application/problem+json;q=0.9, application/json;q=0.8',
  })
}
