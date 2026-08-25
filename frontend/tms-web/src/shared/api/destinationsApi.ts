import { fetchLocations, type LocationListParams, type LocationView } from './locationsApi'
import type { PageResponse } from './pageResponse'

/**
 * "Destinations" as a lens over the one physical master; the counterpart of `originsApi`, and
 * see that module for why these two exist at all.
 *
 * A destination is a `tms.location` holding the `DESTINATION` role. The same store appears here
 * and in `fetchOrigins` when it both receives deliveries and ships its own returns - one row,
 * two lenses, which is the point.
 */

/** A place usable as a destination - the canonical location row, unchanged. */
export type DestinationView = LocationView

export type DestinationListParams = Omit<LocationListParams, 'role'>

/** Locations of this company that hold the `DESTINATION` role, with the caller's other filters applied. */
export function fetchDestinations(params: DestinationListParams): Promise<PageResponse<DestinationView>> {
  return fetchLocations({ ...params, role: 'DESTINATION' })
}
