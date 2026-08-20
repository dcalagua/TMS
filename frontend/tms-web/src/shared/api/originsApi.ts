import { fetchLocations, type LocationListParams, type LocationView } from './locationsApi'
import type { PageResponse } from './pageResponse'

/**
 * "Origins" as a lens over the one physical master, not as a master of its own.
 *
 * There is no `/masterdata/origins` endpoint any anymore and no `tms.origin` table behind one:
 * an origin is a `tms.location` that holds the `ORIGIN` operational role, so this module is a
 * single preset filter. Everything else a caller might want - creating, editing, deactivating -
 * belongs to `locationsApi`, because it happens to the place, not to its role.
 *
 * Kept as its own module rather than folded into `locationsApi` for one reason: it names what
 * the caller means. An order's origin selector wants "the places this company may ship from",
 * and `fetchOrigins` says that where `fetchLocations({ role: 'ORIGIN' })` repeated at nine call
 * sites would only imply it.
 */

/** A place usable as an origin - the canonical location row, unchanged. */
export type OriginView = LocationView

export type OriginListParams = Omit<LocationListParams, 'role'>

/** Locations of this company that hold the `ORIGIN` role, with the caller's other filters applied. */
export function fetchOrigins(params: OriginListParams): Promise<PageResponse<OriginView>> {
  return fetchLocations({ ...params, role: 'ORIGIN' })
}
