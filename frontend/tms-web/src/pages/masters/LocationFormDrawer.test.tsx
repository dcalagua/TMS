import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { LocationView } from '../../shared/api/locationsApi'
import { LocationFormDrawer } from './LocationFormDrawer'

const locationsApiMocks = vi.hoisted(() => ({
  createLocation: vi.fn(),
  updateLocation: vi.fn(),
}))
vi.mock('../../shared/api/locationsApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/locationsApi')>('../../shared/api/locationsApi')
  return { ...actual, ...locationsApiMocks }
})

const zonesApiMocks = vi.hoisted(() => ({ fetchZones: vi.fn() }))
vi.mock('../../shared/api/zonesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/zonesApi')>('../../shared/api/zonesApi')
  return { ...actual, fetchZones: zonesApiMocks.fetchZones }
})

const LOCATION: LocationView = {
  id: 'location-1',
  code: 'LIM-DC',
  name: 'Lima Distribution Centre',
  type: 'DISTRIBUTION_CENTER',
  roles: ['ORIGIN', 'SHIP_TO'],
  address: 'Av. Argentina 1234',
  addressReference: 'Puerta azul',
  district: 'Callao',
  province: 'Callao',
  department: 'Callao',
  country: 'PE',
  timeZone: 'America/Lima',
  latitude: -12.046374,
  longitude: -77.042793,
  zoneId: null,
  zoneCode: null,
  zoneName: null,
  serviceTimeMinutes: 25,
  externalSystem: 'EWM',
  externalReference: 'DC-77',
  originId: 'origin-1',
  destinationId: 'destination-1',
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function renderDrawer(location: LocationView | null, onSaved = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  zonesApiMocks.fetchZones.mockResolvedValue({ content: [], page: 0, size: 200, totalElements: 0 })
  render(
    <QueryClientProvider client={queryClient}>
      <LocationFormDrawer companyId="company-1" location={location} onClose={vi.fn()} onSaved={onSaved} />
    </QueryClientProvider>,
  )
  return { onSaved }
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('LocationFormDrawer', () => {
  it('opens in create mode with SHIP_TO preselected, the role a new place usually plays', () => {
    renderDrawer(null)

    expect(screen.getByRole('heading', { name: 'Nueva ubicación' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Destino' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Origen' })).not.toBeChecked()
  })

  it('pre-fills every field when editing, including the roles the location holds', () => {
    renderDrawer(LOCATION)

    expect(screen.getByRole('heading', { name: 'Editar ubicación' })).toBeInTheDocument()
    expect(screen.getByLabelText(/^código/i)).toHaveValue('LIM-DC')
    expect(screen.getByLabelText(/^zona horaria/i)).toHaveValue('America/Lima')
    expect(screen.getByLabelText(/tiempo de atención/i)).toHaveValue(25)
    expect(screen.getByRole('checkbox', { name: 'Origen' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Destino' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Tienda' })).not.toBeChecked()
  })

  it('refuses to submit a location with no role at all', async () => {
    renderDrawer(null)

    await userEvent.click(screen.getByRole('checkbox', { name: 'Destino' }))
    await userEvent.type(screen.getByLabelText(/^código/i), 'NO-ROLE')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'No Role')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Selecciona al menos un rol')).toBeInTheDocument()
    expect(locationsApiMocks.createLocation).not.toHaveBeenCalled()
  })

  it('refuses half an external identity, because a reference with no system deduplicates nothing', async () => {
    renderDrawer(null)

    await userEvent.type(screen.getByLabelText(/^código/i), 'HALF-EXT')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Half')
    await userEvent.type(screen.getByLabelText(/referencia externa/i), 'ORPHAN')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    // Both halves carry the same rule, so both flag it - the pair is what is wrong, not one field.
    expect(
      await screen.findAllByText('Indica el sistema y la referencia externa, o deja ambos en blanco'),
    ).toHaveLength(2)
    expect(locationsApiMocks.createLocation).not.toHaveBeenCalled()
  })

  it('refuses half a coordinate pair', async () => {
    renderDrawer(null)

    await userEvent.type(screen.getByLabelText(/^código/i), 'HALF-GEO')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Half')
    await userEvent.type(screen.getByLabelText(/^latitud/i), '-12.04')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Indica latitud y longitud, o deja ambas en blanco')).toBeInTheDocument()
    expect(locationsApiMocks.createLocation).not.toHaveBeenCalled()
  })

  it('sends the selected roles and blanks the optional fields it did not collect', async () => {
    const { onSaved } = renderDrawer(null)
    locationsApiMocks.createLocation.mockResolvedValue(LOCATION)

    await userEvent.click(screen.getByRole('checkbox', { name: 'Origen' }))
    await userEvent.type(screen.getByLabelText(/^código/i), 'DUAL')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Dual Site')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(locationsApiMocks.createLocation).toHaveBeenCalledWith('company-1', {
        code: 'DUAL',
        name: 'Dual Site',
        type: 'STORE',
        roles: ['SHIP_TO', 'ORIGIN'],
        zoneId: null,
        address: null,
        addressReference: null,
        district: null,
        province: null,
        department: null,
        country: 'PE',
        timeZone: 'America/Lima',
        latitude: null,
        longitude: null,
        serviceTimeMinutes: 0,
        externalSystem: null,
        externalReference: null,
      }),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('surfaces a backend conflict at form level, described from its code rather than its detail', async () => {
    renderDrawer(null)
    locationsApiMocks.createLocation.mockRejectedValue(
      new ApiError(409, { code: 'conflict', detail: 'A location with code DUP already exists in this company.' },
        'corr-1', 'conflict'),
    )

    await userEvent.type(screen.getByLabelText(/^código/i), 'DUP')
    await userEvent.type(screen.getByLabelText(/^nombre/i), 'Duplicate')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    // `describeApiError` deliberately renders the stable code, not the server's English detail,
    // so a wording change server-side cannot leak untranslated copy into the UI.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Este cambio entra en conflicto con otra actualización. Recarga e inténtalo de nuevo.',
    )
  })
})
