import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { isGoogleMapsConfigured, loadCoreLibrary, loadMapsLibrary, loadMarkerLibrary } from './googleMapsLoader'
import type { MapLoadStatus, MapStop } from './types'

export interface StopsMapProps {
  stops: MapStop[]
  /** Pixel height of the map canvas. */
  height?: number
}

const DEFAULT_CENTER: google.maps.LatLngLiteral = { lat: -12.046374, lng: -77.042793 }
const DEFAULT_ZOOM = 5

/**
 * Read-only foundation for showing a set of stops on a map: renders one marker per stop and
 * fits the view to them. Deliberately does not do sequencing, routing lines or drag-to-reorder -
 * that belongs to the shipment/stop-sequence work (see `10_shipment_maps_and_stop_sequence.md`),
 * which builds its own interaction on top of this component rather than this one guessing at it.
 */
export function StopsMap({ stops, height = 320 }: StopsMapProps) {
  const { t } = useTranslation('maps')
  const containerRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<google.maps.Map | null>(null)
  const markerCtorRef = useRef<typeof google.maps.Marker | null>(null)
  const boundsCtorRef = useRef<typeof google.maps.LatLngBounds | null>(null)
  const markersRef = useRef<google.maps.Marker[]>([])
  const [status, setStatus] = useState<MapLoadStatus>(() => (isGoogleMapsConfigured() ? 'loading' : 'unavailable'))

  useEffect(() => {
    if (!isGoogleMapsConfigured()) return
    let cancelled = false

    void Promise.all([loadMapsLibrary(), loadMarkerLibrary(), loadCoreLibrary()])
      .then(([{ Map }, { Marker }, { LatLngBounds }]) => {
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
        setStatus('ready')
      })
      .catch(() => {
        if (!cancelled) setStatus('unavailable')
      })

    return () => {
      cancelled = true
      markersRef.current.forEach((marker) => marker.setMap(null))
      markersRef.current = []
      mapRef.current = null
      markerCtorRef.current = null
      boundsCtorRef.current = null
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    const Marker = markerCtorRef.current
    const LatLngBounds = boundsCtorRef.current
    if (!map || !Marker || !LatLngBounds || status !== 'ready') return

    markersRef.current.forEach((marker) => marker.setMap(null))
    markersRef.current = stops.map(
      (stop) => new Marker({ map, position: { lat: stop.latitude, lng: stop.longitude }, title: stop.label }),
    )

    if (stops.length === 0) return
    const bounds = new LatLngBounds()
    stops.forEach((stop) => bounds.extend({ lat: stop.latitude, lng: stop.longitude }))
    map.fitBounds(bounds)
  }, [stops, status])

  if (status === 'unavailable') {
    return (
      <div className="tms-map-unavailable alert alert-secondary py-2 px-3 mb-2 small" role="status">
        {t('unavailable')}
      </div>
    )
  }

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
    </div>
  )
}
