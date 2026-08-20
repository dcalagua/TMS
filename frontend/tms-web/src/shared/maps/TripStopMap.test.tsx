import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TripStopMap } from './TripStopMap'

const loaderMocks = vi.hoisted(() => ({
  isGoogleMapsConfigured: vi.fn(),
  loadCoreLibrary: vi.fn(),
  loadMapsLibrary: vi.fn(),
  loadMarkerLibrary: vi.fn(),
}))
vi.mock('./googleMapsLoader', () => loaderMocks)

function installReadyGoogleMaps() {
  const createdMarkers: Array<{
    position: { lat: number; lng: number }
    title?: string
    label?: { text: string }
    click?: () => void
  }> = []
  const removedMarkers: unknown[] = []
  const createdPolylines: Array<{ path: Array<{ lat: number; lng: number }> }> = []

  class FakeMap {
    fitBounds = vi.fn()
  }

  class FakeMarker {
    #entry: { position: { lat: number; lng: number }; title?: string; label?: { text: string }; click?: () => void }
    constructor(options: {
      position: { lat: number; lng: number }
      title?: string
      label?: { text: string }
    }) {
      this.#entry = { position: options.position, title: options.title, label: options.label }
      createdMarkers.push(this.#entry)
    }
    setMap(map: unknown) {
      if (map === null) removedMarkers.push(this.#entry)
    }
    addListener(event: string, handler: () => void) {
      if (event === 'click') this.#entry.click = handler
    }
  }

  class FakePolyline {
    constructor(options: { path: Array<{ lat: number; lng: number }> }) {
      createdPolylines.push({ path: options.path })
    }
    setMap = vi.fn()
  }

  class FakeLatLngBounds {
    extend = vi.fn()
  }

  loaderMocks.isGoogleMapsConfigured.mockReturnValue(true)
  loaderMocks.loadMapsLibrary.mockResolvedValue({ Map: FakeMap, Polyline: FakePolyline })
  loaderMocks.loadMarkerLibrary.mockResolvedValue({ Marker: FakeMarker })
  loaderMocks.loadCoreLibrary.mockResolvedValue({ LatLngBounds: FakeLatLngBounds })

  return { createdMarkers, removedMarkers, createdPolylines }
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('TripStopMap', () => {
  it('degrades to a non-blocking notice when no API key is configured', () => {
    loaderMocks.isGoogleMapsConfigured.mockReturnValue(false)
    render(<TripStopMap origin={null} stops={[]} />)

    expect(
      screen.getByText('El mapa no está disponible. Puedes ingresar la latitud y la longitud manualmente.'),
    ).toBeInTheDocument()
    expect(loaderMocks.loadMapsLibrary).not.toHaveBeenCalled()
  })

  it('draws the origin as marker 0 and each stop numbered by its actual sequence', async () => {
    const { createdMarkers } = installReadyGoogleMaps()
    render(
      <TripStopMap
        origin={{ latitude: -12.05, longitude: -77.03, label: 'Lima depot' }}
        stops={[
          { id: 's1', sequence: 1, latitude: -12.1, longitude: -77.02, label: 'Store A' },
          { id: 's2', sequence: 2, latitude: -12.2, longitude: -77.01, label: 'Store B' },
        ]}
      />,
    )

    await waitFor(() => expect(createdMarkers).toHaveLength(3))
    expect(createdMarkers[0]).toMatchObject({ title: 'Lima depot', label: { text: '0' } })
    expect(createdMarkers[1]).toMatchObject({ title: 'Store A', label: { text: '1' } })
    expect(createdMarkers[2]).toMatchObject({ title: 'Store B', label: { text: '2' } })
  })

  it('skips a stop with no coordinates instead of inventing a position, and says so', async () => {
    installReadyGoogleMaps()
    render(
      <TripStopMap
        origin={null}
        stops={[
          { id: 's1', sequence: 1, latitude: -12.1, longitude: -77.02, label: 'Store A' },
          { id: 's2', sequence: 2, latitude: null, longitude: null, label: 'Store B' },
        ]}
      />,
    )

    await screen.findByText('1 destino no tiene coordenadas y no aparece en el mapa.')
  })

  it('draws a straight polyline through the known coordinates in sequence order', async () => {
    const { createdPolylines } = installReadyGoogleMaps()
    render(
      <TripStopMap
        origin={{ latitude: -12.0, longitude: -77.0, label: 'Origin' }}
        stops={[
          { id: 's1', sequence: 1, latitude: -12.1, longitude: -77.02, label: 'Store A' },
          { id: 's2', sequence: 2, latitude: -12.2, longitude: -77.01, label: 'Store B' },
        ]}
      />,
    )

    await waitFor(() => expect(createdPolylines).toHaveLength(1))
    expect(createdPolylines[0]?.path).toEqual([
      { lat: -12.0, lng: -77.0 },
      { lat: -12.1, lng: -77.02 },
      { lat: -12.2, lng: -77.01 },
    ])
  })

  it('does not draw a polyline for a single point', async () => {
    const { createdPolylines } = installReadyGoogleMaps()
    render(<TripStopMap origin={null} stops={[{ id: 's1', sequence: 1, latitude: -12.1, longitude: -77.02, label: 'Store A' }]} />)

    await waitFor(() => expect(screen.getByRole('application')).toBeInTheDocument())
    expect(createdPolylines).toHaveLength(0)
  })

  it('lets a marker click drive the selection callback', async () => {
    const { createdMarkers } = installReadyGoogleMaps()
    const onSelectStop = vi.fn()
    render(
      <TripStopMap
        origin={null}
        stops={[{ id: 's1', sequence: 1, latitude: -12.1, longitude: -77.02, label: 'Store A' }]}
        onSelectStop={onSelectStop}
      />,
    )

    await waitFor(() => expect(createdMarkers).toHaveLength(1))
    createdMarkers[0]?.click?.()

    expect(onSelectStop).toHaveBeenCalledWith('s1')
  })

  it('highlights the selected stop with a filled marker and a bold label', async () => {
    const { createdMarkers } = installReadyGoogleMaps()
    render(
      <TripStopMap
        origin={null}
        stops={[
          { id: 's1', sequence: 1, latitude: -12.1, longitude: -77.02, label: 'Store A' },
          { id: 's2', sequence: 2, latitude: -12.2, longitude: -77.01, label: 'Store B' },
        ]}
        selectedStopId="s2"
      />,
    )

    await waitFor(() => expect(createdMarkers).toHaveLength(2))
    expect(createdMarkers[0]?.label).toMatchObject({ color: '#1a1a1a' })
    expect(createdMarkers[1]?.label).toMatchObject({ color: '#ffffff' })
  })
})
