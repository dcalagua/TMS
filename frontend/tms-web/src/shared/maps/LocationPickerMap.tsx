import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { isGoogleMapsConfigured, loadGeocodingLibrary, loadMapsLibrary, loadMarkerLibrary } from './googleMapsLoader'
import type { MapLoadStatus } from './types'

export interface LocationPickerMapProps {
  /** `null` means no coordinates chosen yet: the map centers on a default view with no marker. */
  latitude: number | null
  longitude: number | null
  /** Fired when the operator clicks the map, drags the marker, or resolves an address search. */
  onChange: (lat: number, lng: number) => void
  /** Prefills the search box, e.g. from the location's address fields. Read once, on mount. */
  initialSearchValue?: string
}

// Lima, the product's current market (see `shared/i18n/config.ts`), so an operator without
// coordinates yet still opens a map centered on the country the business operates in.
const DEFAULT_CENTER: google.maps.LatLngLiteral = { lat: -12.046374, lng: -77.042793 }
const ZOOM_NO_MARKER = 5
const ZOOM_WITH_MARKER = 15

/**
 * Address search + click-to-place + draggable marker, wired to plain `latitude`/`longitude`
 * numbers so it drops into any form's existing coordinate fields (see `LocationFormDrawer`).
 * Degrades to a non-blocking notice, and leaves manual entry untouched, when no API key is
 * configured or the script fails to load - it never throws and never blocks the surrounding form.
 */
export function LocationPickerMap({ latitude, longitude, onChange, initialSearchValue }: LocationPickerMapProps) {
  const { t } = useTranslation('maps')
  const containerRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<google.maps.Map | null>(null)
  const markerRef = useRef<google.maps.Marker | null>(null)
  const geocoderRef = useRef<google.maps.Geocoder | null>(null)
  const dragListenerRef = useRef<google.maps.MapsEventListener | null>(null)
  const clickListenerRef = useRef<google.maps.MapsEventListener | null>(null)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  const [status, setStatus] = useState<MapLoadStatus>(() => (isGoogleMapsConfigured() ? 'loading' : 'unavailable'))
  const [searchValue, setSearchValue] = useState(initialSearchValue ?? '')
  const [searchError, setSearchError] = useState<string | null>(null)

  useEffect(() => {
    if (!isGoogleMapsConfigured()) return
    let cancelled = false

    void Promise.all([loadMapsLibrary(), loadMarkerLibrary(), loadGeocodingLibrary()])
      .then(([{ Map }, { Marker }, { Geocoder }]) => {
        if (cancelled || !containerRef.current) return
        const hasCoordinates = latitude !== null && longitude !== null
        const center = hasCoordinates ? { lat: latitude, lng: longitude } : DEFAULT_CENTER

        const map = new Map(containerRef.current, {
          center,
          zoom: hasCoordinates ? ZOOM_WITH_MARKER : ZOOM_NO_MARKER,
          streetViewControl: false,
          mapTypeControl: false,
          fullscreenControl: false,
        })
        const marker = new Marker({ map, position: center, draggable: true, visible: hasCoordinates })

        dragListenerRef.current = marker.addListener('dragend', () => {
          const position = marker.getPosition()
          if (position) onChangeRef.current(position.lat(), position.lng())
        })
        clickListenerRef.current = map.addListener('click', (event: google.maps.MapMouseEvent) => {
          if (!event.latLng) return
          marker.setPosition(event.latLng)
          marker.setVisible(true)
          onChangeRef.current(event.latLng.lat(), event.latLng.lng())
        })

        mapRef.current = map
        markerRef.current = marker
        geocoderRef.current = new Geocoder()
        setStatus('ready')
      })
      .catch(() => {
        if (!cancelled) setStatus('unavailable')
      })

    return () => {
      cancelled = true
      dragListenerRef.current?.remove()
      clickListenerRef.current?.remove()
      dragListenerRef.current = null
      clickListenerRef.current = null
      mapRef.current = null
      markerRef.current = null
      geocoderRef.current = null
    }
    // The map is created once; coordinate updates afterwards are synced by the effect below
    // instead of recreating the map, so panning/zooming the operator already did is preserved.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const marker = markerRef.current
    const map = mapRef.current
    if (!marker || !map) return
    if (latitude === null || longitude === null) {
      marker.setVisible(false)
      return
    }
    const position = { lat: latitude, lng: longitude }
    marker.setPosition(position)
    marker.setVisible(true)
    map.panTo(position)
  }, [latitude, longitude])

  async function handleSearch() {
    const geocoder = geocoderRef.current
    const map = mapRef.current
    if (!geocoder || !map || searchValue.trim() === '') return
    setSearchError(null)
    try {
      const { results } = await geocoder.geocode({ address: searchValue })
      const first = results[0]
      if (!first) {
        setSearchError(t('searchNoResults'))
        return
      }
      const location = first.geometry.location
      onChangeRef.current(location.lat(), location.lng())
      map.panTo(location)
      map.setZoom(ZOOM_WITH_MARKER)
    } catch (error) {
      // A no-match address rejects the promise with a `ZERO_RESULTS` status rather than
      // resolving with an empty array - both read as "nothing found" to the operator.
      const code = error && typeof error === 'object' && 'code' in error ? (error as { code: unknown }).code : null
      setSearchError(code === 'ZERO_RESULTS' ? t('searchNoResults') : t('searchFailed'))
    }
  }

  if (status === 'unavailable') {
    return (
      <div className="tms-map-unavailable alert alert-secondary py-2 px-3 mb-2 small" role="status">
        {t('unavailable')}
      </div>
    )
  }

  return (
    <div className="tms-map-picker mb-2">
      <div className="input-group input-group-sm mb-2">
        <input
          type="text"
          className="form-control"
          placeholder={t('searchPlaceholder')}
          aria-label={t('searchPlaceholder')}
          value={searchValue}
          onChange={(event) => setSearchValue(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              void handleSearch()
            }
          }}
          disabled={status !== 'ready'}
        />
        <button
          type="button"
          className="btn btn-outline-secondary"
          onClick={() => void handleSearch()}
          disabled={status !== 'ready' || searchValue.trim() === ''}
        >
          {t('search')}
        </button>
      </div>
      {searchError && (
        <div className="text-danger small mb-2" role="alert">
          {searchError}
        </div>
      )}
      <div
        ref={containerRef}
        className="tms-map-picker-canvas rounded border"
        role="application"
        aria-label={t('mapAriaLabel')}
        style={{ height: 260 }}
      />
      {status === 'loading' && <div className="text-muted small mt-1">{t('loading')}</div>}
      <p className="text-muted small mt-2 mb-0">{t('helpText')}</p>
    </div>
  )
}
