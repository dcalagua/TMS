import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LocationPickerMap } from './LocationPickerMap'

const loaderMocks = vi.hoisted(() => ({
  isGoogleMapsConfigured: vi.fn(),
  loadMapsLibrary: vi.fn(),
  loadMarkerLibrary: vi.fn(),
  loadGeocodingLibrary: vi.fn(),
}))
vi.mock('./googleMapsLoader', () => loaderMocks)

/** Records every listener a fake map/marker instance was given, so a test can fire one. */
type Listeners = Record<string, (...args: unknown[]) => void>

function fakeLatLng(lat: number, lng: number) {
  return { lat: () => lat, lng: () => lng }
}

function installReadyGoogleMaps() {
  const mapListeners: Listeners = {}
  const markerListeners: Listeners = {}
  let markerPosition = fakeLatLng(0, 0)
  let markerVisible = false

  class FakeMap {
    panTo = vi.fn()
    setZoom = vi.fn()
    addListener(event: string, handler: (...args: unknown[]) => void) {
      mapListeners[event] = handler
      return { remove: vi.fn() }
    }
  }

  class FakeMarker {
    setPosition = vi.fn((position: { lat: number; lng: number } | ReturnType<typeof fakeLatLng>) => {
      markerPosition = 'lat' in position && typeof position.lat === 'function'
        ? (position as ReturnType<typeof fakeLatLng>)
        : fakeLatLng((position as { lat: number }).lat, (position as { lng: number }).lng)
    })
    setVisible = vi.fn((visible: boolean) => {
      markerVisible = visible
    })
    getPosition = vi.fn(() => markerPosition)
    addListener(event: string, handler: (...args: unknown[]) => void) {
      markerListeners[event] = handler
      return { remove: vi.fn() }
    }
  }

  class FakeGeocoder {
    geocode = vi.fn()
  }

  loaderMocks.isGoogleMapsConfigured.mockReturnValue(true)
  loaderMocks.loadMapsLibrary.mockResolvedValue({ Map: FakeMap })
  loaderMocks.loadMarkerLibrary.mockResolvedValue({ Marker: FakeMarker })
  loaderMocks.loadGeocodingLibrary.mockResolvedValue({ Geocoder: FakeGeocoder })

  return { mapListeners, markerListeners, isMarkerVisible: () => markerVisible }
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('LocationPickerMap', () => {
  it('degrades to a non-blocking notice when no API key is configured', () => {
    loaderMocks.isGoogleMapsConfigured.mockReturnValue(false)
    render(<LocationPickerMap latitude={null} longitude={null} onChange={vi.fn()} />)

    expect(
      screen.getByText('El mapa no está disponible. Puedes ingresar la latitud y la longitud manualmente.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('application')).not.toBeInTheDocument()
    expect(loaderMocks.loadMapsLibrary).not.toHaveBeenCalled()
  })

  it('degrades gracefully when the script fails to load instead of throwing', async () => {
    loaderMocks.isGoogleMapsConfigured.mockReturnValue(true)
    loaderMocks.loadMapsLibrary.mockRejectedValue(new Error('blocked by an extension'))
    loaderMocks.loadMarkerLibrary.mockResolvedValue({ Marker: vi.fn() })
    loaderMocks.loadGeocodingLibrary.mockResolvedValue({ Geocoder: vi.fn() })

    render(<LocationPickerMap latitude={null} longitude={null} onChange={vi.fn()} />)

    expect(await screen.findByRole('status')).toHaveTextContent('El mapa no está disponible')
  })

  it('reports the clicked point once the map is ready', async () => {
    const { mapListeners } = installReadyGoogleMaps()
    const onChange = vi.fn()
    render(<LocationPickerMap latitude={null} longitude={null} onChange={onChange} />)

    await waitFor(() => expect(mapListeners.click).toBeDefined())
    mapListeners.click!({ latLng: fakeLatLng(-12.05, -77.03) })

    expect(onChange).toHaveBeenCalledWith(-12.05, -77.03)
  })

  it('reports the dragged marker position', async () => {
    const { markerListeners } = installReadyGoogleMaps()
    const onChange = vi.fn()
    render(<LocationPickerMap latitude={-12} longitude={-77} onChange={onChange} />)

    await waitFor(() => expect(markerListeners.dragend).toBeDefined())
    markerListeners.dragend!()

    expect(onChange).toHaveBeenCalled()
  })

  it('searches an address and reports the geocoded coordinates', async () => {
    installReadyGoogleMaps()
    const onChange = vi.fn()
    const geocodeResult = { results: [{ geometry: { location: fakeLatLng(-12.1, -77.1) } }] }
    loaderMocks.loadGeocodingLibrary.mockResolvedValue({
      Geocoder: class {
        geocode = vi.fn().mockResolvedValue(geocodeResult)
      },
    })

    render(<LocationPickerMap latitude={null} longitude={null} onChange={onChange} />)
    const searchBox = await screen.findByRole('textbox', { name: 'Buscar una dirección' })
    await waitFor(() => expect(searchBox).toBeEnabled())

    await userEvent.type(searchBox, 'Av. Argentina 1234')
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }))

    await waitFor(() => expect(onChange).toHaveBeenCalledWith(-12.1, -77.1))
  })

  it('surfaces a message when the address search finds nothing', async () => {
    installReadyGoogleMaps()
    loaderMocks.loadGeocodingLibrary.mockResolvedValue({
      Geocoder: class {
        geocode = vi.fn().mockResolvedValue({ results: [] })
      },
    })

    render(<LocationPickerMap latitude={null} longitude={null} onChange={vi.fn()} />)
    const searchBox = await screen.findByRole('textbox', { name: 'Buscar una dirección' })
    await waitFor(() => expect(searchBox).toBeEnabled())

    await userEvent.type(searchBox, 'Nowhere at all')
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }))

    expect(await screen.findByText('No se encontraron resultados para esa dirección.')).toBeInTheDocument()
  })

  it('also reads a ZERO_RESULTS rejection as "nothing found", not a generic failure', async () => {
    installReadyGoogleMaps()
    loaderMocks.loadGeocodingLibrary.mockResolvedValue({
      Geocoder: class {
        geocode = vi.fn().mockRejectedValue(Object.assign(new Error('zero results'), { code: 'ZERO_RESULTS' }))
      },
    })

    render(<LocationPickerMap latitude={null} longitude={null} onChange={vi.fn()} />)
    const searchBox = await screen.findByRole('textbox', { name: 'Buscar una dirección' })
    await waitFor(() => expect(searchBox).toBeEnabled())

    await userEvent.type(searchBox, 'Nowhere at all')
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }))

    expect(await screen.findByText('No se encontraron resultados para esa dirección.')).toBeInTheDocument()
  })

  it('surfaces a generic message for a real geocoding failure', async () => {
    installReadyGoogleMaps()
    loaderMocks.loadGeocodingLibrary.mockResolvedValue({
      Geocoder: class {
        geocode = vi.fn().mockRejectedValue(Object.assign(new Error('offline'), { code: 'ERROR' }))
      },
    })

    render(<LocationPickerMap latitude={null} longitude={null} onChange={vi.fn()} />)
    const searchBox = await screen.findByRole('textbox', { name: 'Buscar una dirección' })
    await waitFor(() => expect(searchBox).toBeEnabled())

    await userEvent.type(searchBox, 'Av. Argentina 1234')
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }))

    expect(await screen.findByText('No se pudo buscar la dirección. Inténtalo de nuevo.')).toBeInTheDocument()
  })
})
