import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RouteDetailView } from '../../shared/api/routesApi'
import { RouteFormDrawer } from './RouteFormDrawer'

const routesApiMocks = vi.hoisted(() => ({ fetchRoute: vi.fn(), createRoute: vi.fn(), updateRoute: vi.fn() }))
vi.mock('../../shared/api/routesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/routesApi')>('../../shared/api/routesApi')
  return { ...actual, ...routesApiMocks }
})

const originsApiMocks = vi.hoisted(() => ({ fetchOrigins: vi.fn() }))
vi.mock('../../shared/api/originsApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/originsApi')>('../../shared/api/originsApi')
  return { ...actual, fetchOrigins: originsApiMocks.fetchOrigins }
})

const zonesApiMocks = vi.hoisted(() => ({ fetchZones: vi.fn() }))
vi.mock('../../shared/api/zonesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/zonesApi')>('../../shared/api/zonesApi')
  return { ...actual, fetchZones: zonesApiMocks.fetchZones }
})

const frequenciesApiMocks = vi.hoisted(() => ({ fetchFrequencies: vi.fn() }))
vi.mock('../../shared/api/frequenciesApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/frequenciesApi')>('../../shared/api/frequenciesApi')
  return { ...actual, fetchFrequencies: frequenciesApiMocks.fetchFrequencies }
})

const destinationsApiMocks = vi.hoisted(() => ({ fetchDestinations: vi.fn() }))
vi.mock('../../shared/api/destinationsApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/destinationsApi')>('../../shared/api/destinationsApi')
  return { ...actual, fetchDestinations: destinationsApiMocks.fetchDestinations }
})

function page<T>(content: T[]) {
  return { content, page: 0, size: 200, totalElements: content.length }
}

const ORIGIN = { id: 'origin-1', code: 'ORIGIN-A', name: 'Origin A', type: 'WAREHOUSE' as const, address: null,
  latitude: null, longitude: null, timeZone: 'America/Lima', externalReference: null, active: true,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }

const ZONE = { id: 'zone-1', code: 'ZONE-A', name: 'Zone A', description: null, active: true,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }

const FREQUENCY = { id: 'freq-1', code: 'FREQ-A', name: 'Frequency A', description: null, active: true,
  weeklyRules: [], createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }

const DESTINATION_A = { id: 'dest-1', code: 'DEST-A', name: 'Destination A', type: 'CUSTOMER' as const,
  address: null, addressReference: null, district: null, province: null, department: null, country: 'PE',
  latitude: null, longitude: null, zoneId: null, zoneCode: null, zoneName: null, serviceTimeMinutes: 0,
  externalReference: null, active: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }

const DESTINATION_B = { ...DESTINATION_A, id: 'dest-2', code: 'DEST-B', name: 'Destination B' }

const ROUTE_DETAIL: RouteDetailView = {
  id: 'route-1',
  code: 'NORTH-CORRIDOR',
  name: 'North Corridor',
  originId: 'origin-1',
  originCode: 'ORIGIN-A',
  originName: 'Origin A',
  zoneId: 'zone-1',
  zoneCode: 'ZONE-A',
  zoneName: 'Zone A',
  frequencyId: null,
  frequencyCode: null,
  frequencyName: null,
  referenceDistanceKm: 12.5,
  referenceDurationMinutes: 45,
  stops: [
    { destinationId: 'dest-1', destinationCode: 'DEST-A', destinationName: 'Destination A', sequence: 1 },
    { destinationId: 'dest-2', destinationCode: 'DEST-B', destinationName: 'Destination B', sequence: 2 },
  ],
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function mockLookups() {
  originsApiMocks.fetchOrigins.mockResolvedValue(page([ORIGIN]))
  zonesApiMocks.fetchZones.mockResolvedValue(page([ZONE]))
  frequenciesApiMocks.fetchFrequencies.mockResolvedValue(page([FREQUENCY]))
  destinationsApiMocks.fetchDestinations.mockResolvedValue(page([DESTINATION_A, DESTINATION_B]))
}

/** `Select` is a button + listbox, not a native `<select>`: open it, then click the option. */
async function pickOption(comboboxName: RegExp | string, optionName: RegExp | string) {
  await userEvent.click(screen.getByRole('combobox', { name: comboboxName }))
  await userEvent.click(await screen.findByRole('option', { name: optionName }))
}

function renderModal(props: Partial<ComponentProps<typeof RouteFormDrawer>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <RouteFormDrawer companyId="company-1" routeId={null} onClose={vi.fn()} onSaved={vi.fn()} {...props} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('RouteFormDrawer', () => {
  it('rejects an empty submission without calling the API', async () => {
    mockLookups()
    const onSaved = vi.fn()
    renderModal({ onSaved })

    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    // Code, name and origin are the three required fields.
    expect(await screen.findAllByText('Este campo es obligatorio')).toHaveLength(3)
    expect(screen.getByLabelText(/^código/i)).toHaveClass('is-invalid')
    expect(screen.getByLabelText(/^origen/i)).toHaveClass('is-invalid')
    expect(routesApiMocks.createRoute).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('requires at least one destination stop even when every other field is valid', async () => {
    mockLookups()
    renderModal()

    await userEvent.type(screen.getByLabelText(/^código/i), 'NO-STOPS')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'No Stops')
    await pickOption(/^origen/i, 'Origin A')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Agrega al menos una parada de destino.')).toBeInTheDocument()
    expect(routesApiMocks.createRoute).not.toHaveBeenCalled()
  })

  it('adds and removes a destination stop', async () => {
    mockLookups()
    renderModal()

    expect(await screen.findByText('Aún no hay paradas.')).toBeInTheDocument()

    await pickOption('Destino a agregar', 'DEST-A — Destination A')
    await userEvent.click(screen.getByRole('button', { name: 'Agregar parada' }))

    expect(await screen.findByText('DEST-A — Destination A')).toBeInTheDocument()
    expect(screen.queryByText('No stops added yet.')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Quitar la parada 1' }))
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument()
    expect(screen.getByText('Aún no hay paradas.')).toBeInTheDocument()
  })

  it('reorders stops with the move up/down controls', async () => {
    mockLookups()
    renderModal()

    await pickOption('Destino a agregar', 'DEST-A — Destination A')
    await userEvent.click(screen.getByRole('button', { name: 'Agregar parada' }))
    await pickOption('Destino a agregar', 'DEST-B — Destination B')
    await userEvent.click(screen.getByRole('button', { name: 'Agregar parada' }))

    const items = screen.getAllByRole('listitem')
    expect(items[0]).toHaveTextContent('DEST-A')
    expect(items[1]).toHaveTextContent('DEST-B')

    await userEvent.click(screen.getByRole('button', { name: 'Subir la parada 2' }))

    const reordered = screen.getAllByRole('listitem')
    expect(reordered[0]).toHaveTextContent('DEST-B')
    expect(reordered[1]).toHaveTextContent('DEST-A')
  })

  it('creates a route with the entered values and the stops in the order they were added', async () => {
    mockLookups()
    routesApiMocks.createRoute.mockResolvedValue({ ...ROUTE_DETAIL, code: 'NEW-ROUTE' })
    const onSaved = vi.fn()
    renderModal({ onSaved })

    await userEvent.type(screen.getByLabelText(/^código/i), 'new-route')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'New Route')
    await pickOption(/^origen/i, 'Origin A')
    await pickOption('Destino a agregar', 'DEST-B — Destination B')
    await userEvent.click(screen.getByRole('button', { name: 'Agregar parada' }))
    await pickOption('Destino a agregar', 'DEST-A — Destination A')
    await userEvent.click(screen.getByRole('button', { name: 'Agregar parada' }))
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(routesApiMocks.createRoute).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ code: 'new-route', name: 'New Route', originId: 'origin-1',
          destinationIds: ['dest-2', 'dest-1'] }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('loads and pre-fills an existing route, and calls updateRoute with the route id', async () => {
    mockLookups()
    routesApiMocks.fetchRoute.mockResolvedValue(ROUTE_DETAIL)
    routesApiMocks.updateRoute.mockResolvedValue(ROUTE_DETAIL)
    const onSaved = vi.fn()
    renderModal({ routeId: 'route-1', onSaved })

    expect(screen.getByText('Cargando ruta...')).toBeInTheDocument()

    expect(await screen.findByLabelText(/^código/i)).toHaveValue('NORTH-CORRIDOR')
    expect(screen.getByLabelText(/^nombre/i)).toHaveValue('North Corridor')
    expect(screen.getByText('DEST-A — Destination A')).toBeInTheDocument()
    expect(screen.getByText('DEST-B — Destination B')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(routesApiMocks.updateRoute).toHaveBeenCalledWith(
        'company-1', 'route-1', expect.objectContaining({ destinationIds: ['dest-1', 'dest-2'] }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('still shows a stop whose destination was deactivated since the route was saved', async () => {
    originsApiMocks.fetchOrigins.mockResolvedValue(page([ORIGIN]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([ZONE]))
    frequenciesApiMocks.fetchFrequencies.mockResolvedValue(page([FREQUENCY]))
    // Only DESTINATION_B is still active; DESTINATION_A (already a stop) is not in this list.
    destinationsApiMocks.fetchDestinations.mockResolvedValue(page([DESTINATION_B]))
    routesApiMocks.fetchRoute.mockResolvedValue(ROUTE_DETAIL)

    renderModal({ routeId: 'route-1' })

    expect(await screen.findByText('DEST-A — Destination A')).toBeInTheDocument()
  })

  it('maps a backend field error onto the matching input', async () => {
    mockLookups()
    routesApiMocks.createRoute.mockRejectedValue({
      fieldErrors: [{ field: 'code', message: "code 'DUP' already exists" }],
    })
    renderModal()

    await userEvent.type(screen.getByLabelText(/^código/i), 'DUP')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Duplicate')
    await pickOption(/^origen/i, 'Origin A')
    await pickOption('Destino a agregar', 'DEST-A — Destination A')
    await userEvent.click(screen.getByRole('button', { name: 'Agregar parada' }))
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText("code 'DUP' already exists")).toBeInTheDocument()
  })

  it('closes when Cancel is clicked', async () => {
    mockLookups()
    const onClose = vi.fn()
    renderModal({ onClose })

    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(onClose).toHaveBeenCalled()
  })
})
