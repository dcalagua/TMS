import { importLibrary, setOptions } from '@googlemaps/js-api-loader'
import { appEnv } from '../config/env'

/**
 * One place that knows the Google Maps API key and starts the bootstrap loader, so
 * `LocationPickerMap` and `StopsMap` (and anything added later) share a single script load
 * instead of racing to inject their own `<script>` tag.
 */

export function isGoogleMapsConfigured(): boolean {
  return appEnv.googleMapsApiKey !== null
}

let optionsApplied = false

function ensureOptions(): void {
  if (optionsApplied) return
  // `setOptions` must run before the first `importLibrary` call and only matters once; the
  // loader itself ignores later calls, so guarding here is purely about not repeating the key.
  setOptions({ key: appEnv.googleMapsApiKey ?? '', v: 'weekly' })
  optionsApplied = true
}

const libraryCache = new Map<string, Promise<unknown>>()

function loadLibrary<TLibraryName extends keyof google.maps.ImportLibraryMap>(
  name: TLibraryName,
): Promise<google.maps.ImportLibraryMap[TLibraryName]> {
  if (!isGoogleMapsConfigured()) {
    return Promise.reject(new Error('VITE_GOOGLE_MAPS_API_KEY is not configured'))
  }
  ensureOptions()
  const cached = libraryCache.get(name)
  if (cached) return cached as Promise<google.maps.ImportLibraryMap[TLibraryName]>

  const promise = importLibrary(name) as Promise<google.maps.ImportLibraryMap[TLibraryName]>
  libraryCache.set(name, promise)
  // A failed load (bad key, offline, blocked script) must not be cached, or the map would stay
  // broken for the rest of the session even after the operator fixes their connection.
  promise.catch(() => libraryCache.delete(name))
  return promise
}

export function loadCoreLibrary(): Promise<google.maps.CoreLibrary> {
  return loadLibrary('core')
}

export function loadMapsLibrary(): Promise<google.maps.MapsLibrary> {
  return loadLibrary('maps')
}

export function loadMarkerLibrary(): Promise<google.maps.MarkerLibrary> {
  return loadLibrary('marker')
}

export function loadGeocodingLibrary(): Promise<google.maps.GeocodingLibrary> {
  return loadLibrary('geocoding')
}
