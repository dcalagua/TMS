/** A validated coordinate pair, as opposed to the raw, possibly-empty form strings. */
export interface LatLngValue {
  lat: number
  lng: number
}

/** One marker for `StopsMap`: a shipment stop or a location, already geocoded. */
export interface MapStop {
  id: string
  latitude: number
  longitude: number
  label?: string
}

/** Loading lifecycle shared by every map component, so each renders the same three states. */
export type MapLoadStatus = 'idle' | 'loading' | 'ready' | 'unavailable'
