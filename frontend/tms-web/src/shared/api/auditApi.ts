import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/**
 * The audit trail, read-only.
 *
 * There is deliberately no `createAuditEvent`, `updateAuditEvent` or `deleteAuditEvent` here, and
 * no endpoint behind them: entries are written by the backend as a side effect of the actions
 * they describe, and `tms.audit_event` refuses UPDATE and DELETE to the runtime role (migration
 * V22). A client that could correct the trail would make it evidence of nothing.
 */

/** Mirrors the backend's `AuditAggregateType` - what kind of thing was changed. */
export type AuditAggregateType =
  | 'LOCATION'
  | 'CARRIER'
  | 'VEHICLE'
  | 'DRIVER'
  | 'TRANSPORT_ORDER'
  | 'TRIP'
  | 'PLANNING_RUN'
  | 'INTEGRATION_CLIENT'
  | 'MASTER_DATA_IMPORT_BATCH'
  | 'ORDER_IMPORT_BATCH'
  | 'SHIPMENT'
  | 'RATE_CARD'
  | 'TRIP_COST'
  | 'COMPANY'
  | 'APP_USER'
  | 'MEMBERSHIP'

/** Every value the API can send, so `enums.test.ts` can prove each one has a label. */
export const AUDIT_AGGREGATE_TYPES: AuditAggregateType[] = [
  'LOCATION',
  'CARRIER',
  'VEHICLE',
  'DRIVER',
  'TRANSPORT_ORDER',
  'TRIP',
  'PLANNING_RUN',
  'INTEGRATION_CLIENT',
  'MASTER_DATA_IMPORT_BATCH',
  'ORDER_IMPORT_BATCH',
  'SHIPMENT',
  'RATE_CARD',
  'TRIP_COST',
  'COMPANY',
  'APP_USER',
  'MEMBERSHIP',
]

/** Mirrors the backend's `AuditAction` - what was done to it. */
export type AuditAction =
  | 'CREATE'
  | 'UPDATE'
  | 'ACTIVATE'
  | 'DEACTIVATE'
  | 'ASSIGN_ORDER'
  | 'REMOVE_ORDER'
  | 'MOVE_ORDER'
  | 'VEHICLE_CHANGE'
  | 'DRIVER_CHANGE'
  | 'CONFIRM'
  | 'CANCEL'
  | 'CREDENTIAL_CREATE'
  | 'CREDENTIAL_ROTATE'
  | 'CREDENTIAL_REVOKE'
  | 'AUTO_PLAN'
  | 'IMPORT_EXECUTED'
  | 'SHIPMENT_CONFIRMED'
  | 'SHIPMENT_READY'
  | 'SHIPMENT_DISPATCHED'
  | 'SHIPMENT_COMPLETED'
  | 'SHIPMENT_CANCELLED'
  | 'DELIVERY_RESULT_RECORDED'
  | 'COST_ESTIMATED'
  | 'COST_ACTUAL_RECORDED'
  | 'COST_CLOSED'
  | 'COST_REOPENED'
  | 'TENDER_SENT'
  | 'TENDER_ACCEPTED'
  | 'TENDER_REJECTED'
  | 'TENDER_EXPIRED'
  | 'TENDER_CANCELLED'

export const AUDIT_ACTIONS: AuditAction[] = [
  'CREATE',
  'UPDATE',
  'ACTIVATE',
  'DEACTIVATE',
  'ASSIGN_ORDER',
  'REMOVE_ORDER',
  'MOVE_ORDER',
  'VEHICLE_CHANGE',
  'DRIVER_CHANGE',
  'CONFIRM',
  'CANCEL',
  'CREDENTIAL_CREATE',
  'CREDENTIAL_ROTATE',
  'CREDENTIAL_REVOKE',
  'AUTO_PLAN',
  'IMPORT_EXECUTED',
  'SHIPMENT_CONFIRMED',
  'SHIPMENT_READY',
  'SHIPMENT_DISPATCHED',
  'SHIPMENT_COMPLETED',
  'SHIPMENT_CANCELLED',
  'DELIVERY_RESULT_RECORDED',
  'COST_ESTIMATED',
  'COST_ACTUAL_RECORDED',
  'COST_CLOSED',
  'COST_REOPENED',
  'TENDER_SENT',
  'TENDER_ACCEPTED',
  'TENDER_REJECTED',
  'TENDER_EXPIRED',
  'TENDER_CANCELLED',
]

/**
 * Mirrors the backend's `AuditEventView`.
 *
 * `metadata` arrives already parsed. The backend serves an entry whose stored annotation could
 * not be read as an empty object rather than failing the page, so this is never null and never a
 * string to parse here.
 *
 * `actorEmail` and `actorMachineLabel` are alternatives, not both: an action was taken either by
 * a person or by a machine credential. Both being null means an actor was never recorded, which
 * older entries can be.
 */
export interface AuditEventView {
  id: string
  occurredAt: string
  actorAppUserId: string | null
  actorEmail: string | null
  actorMachineLabel: string | null
  aggregateType: AuditAggregateType
  aggregateId: string
  action: AuditAction
  correlationId: string | null
  metadata: Record<string, string | null>
}

export interface AuditQuery {
  companyId: string
  page?: number
  size?: number
  sort?: string
  actorAppUserId?: string
  aggregateType?: AuditAggregateType | ''
  aggregateId?: string
  action?: AuditAction | ''
  /** ISO instants. The backend refuses a window that ends before it starts. */
  from?: string
  to?: string
  correlationId?: string
  signal?: AbortSignal
}

export function fetchAuditEvents({
  companyId,
  signal,
  ...query
}: AuditQuery): Promise<PageResponse<AuditEventView>> {
  return apiRequest<PageResponse<AuditEventView>>('/audit-events', {
    companyId,
    signal,
    query,
  })
}
