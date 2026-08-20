import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { isGoogleMapsConfigured, loadCoreLibrary, loadMapsLibrary, loadMarkerLibrary } from './googleMapsLoader'
import type { MapLoadStatus } from './types'

/** The origin marker's own shape - a shipment has at most one, drawn as stop "0". */
export interface TripStopMapOrigin {
  latitude: number | null
  longitude: number | null
  label: string
}

/** One numbered marker: mirrors the fields of `TripStopView` a map actually needs. */
export interface TripStopMapStop {
  id: string
  sequence: number
  latitude: number | null
  longitude: number | null
  label: string
}

export interface TripStopMapProps {
  origin: TripStopMapOrigin | null
  stops: TripStopMapStop[]
  /** The stop list's current selection, so the map and the list always agree on it. */
  selectedStopId?: string | null
  /** Fired when a marker is clicked, so the list can select and scroll to the same stop. */
  onSelectStop?: (stopId: string) => void
  /** Pixel height of the map canvas. */
  height?: number
}

const DEFAULT_CENTER: google.maps.LatLngLiteral = { lat: -12.046374, lng: -77.042793 }
const DEFAULT_ZOOM = 5
const INK = '#1a1a1a'
const MUTED = '#6c757d'

function markerIcon(filled: boolean): google.maps.Icon {
  const fill = filled ? INK : '#ffffff'
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="30" height="30">` +
    `<circle cx="15" cy="15" r="12.5" fill="${fill}" stroke="${INK}" stroke-width="2"/></svg>`
  return { url: `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}` }
}

function markerLabel(text: string, filled: boolean): google.maps.MarkerLabel {
  return { text, color: filled ? '#ffffff' : INK, fontWeight: '700', fontSize: '12px' }
}

/**
 * A planned trip's stop sequence on a map: the origin as marker "0", one numbered marker per stop
 * in its actual `trip_stop` sequence, and a plain straight-line polyline between the known
 * coordinates - never a routed path (`docs/integrations/GOOGLE_MAPS.md` section 7 - Directions/
 * Routes stay opt-in and unused by default). A stop without coordinates is simply not drawn; it is
 * the caller's job to still list it, with a "cannot be mapped" note.
 *
 * This is a sibling of `StopsMap`, not a wrapper around it: `StopsMap`'s own doc comment says
 * sequencing, selection and routing lines belong to "the shipment/stop-sequence work", which
 * "builds its own interaction on top of this component rather than this one guessing at it" - so
 * numbering, the selection highlight and the polyline live here instead.
 */
export function TripStopMap({ origin, stops, selectedStopId = null, onSelectStop, height = 320 }: TripStopMapProps) {
  const { t } = useTranslation('maps')
  const containerRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<google.maps.Map | null>(null)
  const markerCtorRef = useRef<typeof google.maps.Marker | null>(null)
  const boundsCtorRef = useRef<typeof google.maps.LatLngBounds | null>(null)
  const polylineCtorRef = useRef<typeof google.maps.Polyline | null>(null)
  const markersRef = useRef<google.maps.Marker[]>([])
  const polylineRef = useRef<google.maps.Polyline | null>(null)
  const [status, setStatus] = useState<MapLoadStatus>(() => (isGoogleMapsConfigured() ? 'loading' : 'unavailable'))

  useEffect(() => {
    if (!isGoogleMapsConfigured()) return
    let cancelled = false

    void Promise.all([loadMapsLibrary(), loadMarkerLibrary(), loadCoreLibrary()])
      .then(([{ Map, Polyline }, { Marker }, { LatLngBounds }]) => {
        if (cancelled || !containerRef.current) return
        mapRef.current = new Map(containerRef.current, {
          center: DEFAULT_CENTER,
          zoom: DEFAULT_ZOOM,
          streetViewControl: false,
          mapTypeControl: false,
          fullscreenControl: false,
        })
        markerCtorRef.current = Marker
        boundsCtorRef.current = LatLngBounds
        polylineCtorRef.current = Polyline
        setStatus('ready')
      })
      .catch(() => {
        if (!cancelled) setStatus('unavailable')
      })

    return () => {
      cancelled = true
      markersRef.current.forEach((marker) => marker.setMap(null))
      markersRef.current = []
      polylineRef.current?.setMap(null)
      polylineRef.current = null
      mapRef.current = null
      markerCtorRef.current = null
      boundsCtorRef.current = null
      polylineCtorRef.current = null
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    const Marker = markerCtorRef.current
    const LatLngBounds = boundsCtorRef.current
    const Polyline = polylineCtorRef.current
    if (!map || !Marker || !LatLngBounds || !Polyline || status !== 'ready') return

    markersRef.current.forEach((marker) => marker.setMap(null))
    markersRef.current = []
    polylineRef.current?.setMap(null)
    polylineRef.current = null

    const path: google.maps.LatLngLiteral[] = []

    if (origin && origin.latitude !== null && origin.longitude !== null) {
      const position = { lat: origin.latitude, lng: origin.longitude }
      path.push(position)
      markersRef.current.push(
        new Marker({ map, position, title: origin.label, label: markerLabel('0', true), icon: markerIcon(true), zIndex: 500 }),
      )
    }

    stops.forEach((stop) => {
      if (stop.latitude === null || stop.longitude === null) return
      const position = { lat: stop.latitude, lng: stop.longitude }
      path.push(position)
      const selected = stop.id === selectedStopId
      const marker = new Marker({
        map,
        position,
        title: stop.label,
        label: markerLabel(String(stop.sequence), selected),
        icon: markerIcon(selected),
        zIndex: selected ? 400 : 100 + stop.sequence,
      })
      if (onSelectStop) {
        marker.addListener('click', () => onSelectStop(stop.id))
      }
      markersRef.current.push(marker)
    })

    if (path.length >= 2) {
      polylineRef.current = new Polyline({ map, path, strokeColor: MUTED, strokeOpacity: 0.8, strokeWeight: 2, geodesic: true })
    }

    if (path.length === 0) return
    const bounds = new LatLngBounds()
    path.forEach((position) => bounds.extend(position))
    map.fitBounds(bounds)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [origin, stops, selectedStopId, status])

  if (status === 'unavailable') {
    return (
      <div className="tms-map-unavailable alert alert-secondary py-2 px-3 mb-2 small" role="status">
        {t('unavailable')}
      </div>
    )
  }

  const unmappedCount = stops.filter((stop) => stop.latitude === null || stop.longitude === null).length

  return (
    <div className="tms-stops-map mb-2">
      <div
        ref={containerRef}
        className="tms-stops-map-canvas rounded border"
        role="application"
        aria-label={t('stopsAriaLabel')}
        style={{ height }}
      />
      {status === 'loading' && <div className="text-muted small mt-1">{t('loading')}</div>}
      {status === 'ready' && unmappedCount > 0 && (
        <div className="text-body-secondary small mt-1">{t('someStopsNotMapped', { count: unmappedCount })}</div>
      )}
    </div>
  )
}
