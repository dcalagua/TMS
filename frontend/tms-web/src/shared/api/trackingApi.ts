import { apiRequest } from './httpClient'

//
// Where a shipment is (`docs/domain/TRACKING_V1.md`).
//
// Its own client rather than another section of `planningApi`, because it is another module behind
// another permission: a role holding `planning.trip:read` does not necessarily hold
// `monitoring.transport:read`, and a screen that imported both from one file would make that easy
// to forget.

/** Mirrors the backend's `TrackingPositionView` record.
 *
 * `id` and `receivedAt` are null for a position pulled live from a provider rather than stored -
 * see `TrackingQueryService`. Nothing in the UI branches on that today; it is here so that a
 * position which has no row cannot be mistaken for one that has. */
export interface TrackingPositionView {
  id: string | null
  /** When the device was at this point - the business time, as everywhere else in execution. */
  occurredAt: string
  /** When TMS stored it. The gap to `occurredAt` is feed latency. */
  receivedAt: string | null
  latitude: number
  longitude: number
  speedKph: number | null
  headingDegrees: number | null
  provider: string
  externalVehicleReference: string | null
}

/** Mirrors the backend's `TripTrackingView` record.
 *
 * The three "no position" cases are deliberately distinguishable from this one document:
 *
 *   * `trackable` false          - the shipment has not left, or it was cancelled;
 *   * `providerConfigured` false - this deployment has no feed at all;
 *   * both true, `lastPosition` null - there is a feed and it has said nothing about this
 *     shipment, which is the only one of the three worth a phone call.
 *
 * A single empty field would tell a dispatcher which of the three it is: nothing. */
export interface TripTrackingView {
  shipmentNumber: string
  status: string
  trackable: boolean
  providerConfigured: boolean
  vehicleCode: string | null
  vehicleLicensePlate: string | null
  lastPosition: TrackingPositionView | null
  /** The recent trail, oldest first so a map can draw it directly. Bounded server-side. */
  track: TrackingPositionView[]
}

export function fetchTripTracking(
  companyId: string, tripId: string, signal?: AbortSignal,
): Promise<TripTrackingView> {
  return apiRequest<TripTrackingView>(`/tracking/trips/${tripId}`, { companyId, signal })
}
