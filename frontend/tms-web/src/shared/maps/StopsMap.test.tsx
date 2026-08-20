import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { StopsMap } from './StopsMap'

const loaderMocks = vi.hoisted(() => ({
  isGoogleMapsConfigured: vi.fn(),
  loadCoreLibrary: vi.fn(),
  loadMapsLibrary: vi.fn(),
  loadMarkerLibrary: vi.fn(),
}))
vi.mock('./googleMapsLoader', () => loaderMocks)

function installReadyGoogleMaps() {
  const createdMarkers: Array<{ position: { lat: number; lng: number }; title?: string }> = []
  const removedMarkers: unknown[] = []

  class FakeMap {
    fitBounds = vi.fn()
  }

  class FakeMarker {
    #entry: { position: { lat: number; lng: number }; title?: string }
    constructor(options: { position: { lat: number; lng: number }; title?: string }) {
      this.#entry = { position: options.position, title: options.title }
      createdMarkers.push(this.#entry)
    }
    setMap(map: unknown) {
      if (map === null) removedMarkers.push(this.#entry)
    }
  }

  class FakeLatLngBounds {
    extend = vi.fn()
  }

  loaderMocks.isGoogleMapsConfigured.mockReturnValue(true)
  loaderMocks.loadMapsLibrary.mockResolvedValue({ Map: FakeMap })
  loaderMocks.loadMarkerLibrary.mockResolvedValue({ Marker: FakeMarker })
  loaderMocks.loadCoreLibrary.mockResolvedValue({ LatLngBounds: FakeLatLngBounds })

  return { createdMarkers, removedMarkers }
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('StopsMap', () => {
  it('degrades to a non-blocking notice when no API key is configured', () => {
    loaderMocks.isGoogleMapsConfigured.mockReturnValue(false)
    render(<StopsMap stops={[]} />)

    expect(
      screen.getByText('El mapa no está disponible. Puedes ingresar la latitud y la longitud manualmente.'),
    ).toBeInTheDocument()
    expect(loaderMocks.loadMapsLibrary).not.toHaveBeenCalled()
  })

  it('renders one marker per stop once the map is ready', async () => {
    const { createdMarkers } = installReadyGoogleMaps()
    render(
      <StopsMap
        stops={[
          { id: 's1', latitude: -12.05, longitude: -77.03, label: 'Origen' },
          { id: 's2', latitude: -12.1, longitude: -77.02, label: 'Destino' },
        ]}
      />,
    )

    await waitFor(() => expect(createdMarkers).toHaveLength(2))
    expect(createdMarkers[0]).toMatchObject({ position: { lat: -12.05, lng: -77.03 }, title: 'Origen' })
    expect(createdMarkers[1]).toMatchObject({ position: { lat: -12.1, lng: -77.02 }, title: 'Destino' })
  })

  it('replaces markers instead of accumulating them when the stop list changes', async () => {
    const { createdMarkers, removedMarkers } = installReadyGoogleMaps()
    const { rerender } = render(<StopsMap stops={[{ id: 's1', latitude: -12, longitude: -77 }]} />)
    await waitFor(() => expect(createdMarkers).toHaveLength(1))

    rerender(<StopsMap stops={[{ id: 's2', latitude: -13, longitude: -78 }]} />)

    await waitFor(() => expect(createdMarkers).toHaveLength(2))
    expect(removedMarkers).toHaveLength(1)
  })
})
