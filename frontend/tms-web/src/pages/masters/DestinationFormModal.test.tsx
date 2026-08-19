import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DestinationView } from '../../shared/api/destinationsApi'
import { DestinationFormModal } from './DestinationFormModal'

const destinationsApiMocks = vi.hoisted(() => ({ createDestination: vi.fn(), updateDestination: vi.fn() }))
vi.mock('../../shared/api/destinationsApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/destinationsApi')>('../../shared/api/destinationsApi')
  return { ...actual, createDestination: destinationsApiMocks.createDestination, updateDestination: destinationsApiMocks.updateDestination }
})

const zonesApiMocks = vi.hoisted(() => ({ fetchZones: vi.fn() }))
vi.mock('../../shared/api/zonesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/zonesApi')>('../../shared/api/zonesApi')
  return { ...actual, fetchZones: zonesApiMocks.fetchZones }
})

const DESTINATION: DestinationView = {
  id: 'destination-1',
  code: 'NORTH-STORE',
  name: 'North Store',
  type: 'STORE',
  address: '123 Main St',
  addressReference: 'Blue gate',
  district: 'Miraflores',
  province: 'Lima',
  department: 'Lima',
  country: 'PE',
  latitude: -12.046374,
  longitude: -77.042793,
  zoneId: 'zone-1',
  zoneCode: 'ZONE-A',
  zoneName: 'Zone A',
  serviceTimeMinutes: 15,
  externalReference: 'EWM-1',
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function emptyZonesPage() {
  return { content: [], page: 0, size: 200, totalElements: 0 }
}

function renderModal(props: Partial<ComponentProps<typeof DestinationFormModal>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <DestinationFormModal
        companyId="company-1"
        destination={null}
        onClose={vi.fn()}
        onSaved={vi.fn()}
        {...props}
      />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('DestinationFormModal', () => {
  it('rejects an empty submission without calling the API', async () => {
    zonesApiMocks.fetchZones.mockResolvedValue(emptyZonesPage())
    const onSaved = vi.fn()
    renderModal({ onSaved })

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Code is required')).toBeInTheDocument()
    expect(screen.getByText('Name is required')).toBeInTheDocument()
    expect(destinationsApiMocks.createDestination).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('rejects a code with characters outside the allowed shape', async () => {
    zonesApiMocks.fetchZones.mockResolvedValue(emptyZonesPage())
    renderModal()

    await userEvent.type(screen.getByLabelText(/^code/i), 'has space')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Letters, digits, underscore or hyphen only')).toBeInTheDocument()
    expect(destinationsApiMocks.createDestination).not.toHaveBeenCalled()
  })

  it('requires both latitude and longitude, or neither', async () => {
    zonesApiMocks.fetchZones.mockResolvedValue(emptyZonesPage())
    renderModal()

    await userEvent.type(screen.getByLabelText(/^code/i), 'PARTIAL')
    await userEvent.type(screen.getByLabelText(/^name/i), 'Partial')
    await userEvent.type(screen.getByLabelText(/^latitude/i), '10.5')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Provide both latitude and longitude, or leave both blank')).toBeInTheDocument()
    expect(destinationsApiMocks.createDestination).not.toHaveBeenCalled()
  })

  it('lists zones fetched from the backend in the zone select', async () => {
    zonesApiMocks.fetchZones.mockResolvedValue({
      content: [{ id: 'zone-1', code: 'ZONE-A', name: 'Zone A', description: null, active: true, createdAt: '', updatedAt: '' }],
      page: 0,
      size: 200,
      totalElements: 1,
    })
    renderModal()

    expect(await screen.findByRole('option', { name: 'Zone A' })).toBeInTheDocument()
  })

  it('creates a destination with the entered values and reports success', async () => {
    zonesApiMocks.fetchZones.mockResolvedValue(emptyZonesPage())
    destinationsApiMocks.createDestination.mockResolvedValue({ ...DESTINATION, code: 'SOUTH-STORE' })
    const onSaved = vi.fn()
    renderModal({ onSaved })

    await userEvent.type(screen.getByLabelText(/^code/i), 'south-store')
    await userEvent.type(screen.getByLabelText(/^name/i), 'South Store')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(destinationsApiMocks.createDestination).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ code: 'south-store', name: 'South Store', country: 'PE', serviceTimeMinutes: 0, zoneId: null }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('pre-fills the form for an edit and calls updateDestination with the destination id', async () => {
    zonesApiMocks.fetchZones.mockResolvedValue(emptyZonesPage())
    destinationsApiMocks.updateDestination.mockResolvedValue(DESTINATION)
    const onSaved = vi.fn()
    renderModal({ destination: DESTINATION, onSaved })

    expect(screen.getByLabelText(/^code/i)).toHaveValue('NORTH-STORE')
    expect(screen.getByLabelText(/^name/i)).toHaveValue('North Store')
    expect(screen.getByLabelText(/^district/i)).toHaveValue('Miraflores')

    await userEvent.clear(screen.getByLabelText(/^name/i))
    await userEvent.type(screen.getByLabelText(/^name/i), 'North Store Renamed')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(destinationsApiMocks.updateDestination).toHaveBeenCalledWith(
        'company-1',
        'destination-1',
        expect.objectContaining({ name: 'North Store Renamed' }),
      ),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('maps a backend field error onto the matching input instead of a generic message', async () => {
    zonesApiMocks.fetchZones.mockResolvedValue(emptyZonesPage())
    destinationsApiMocks.createDestination.mockRejectedValue({
      fieldErrors: [{ field: 'code', message: "code 'DUP' already exists" }],
    })
    renderModal()

    await userEvent.type(screen.getByLabelText(/^code/i), 'DUP')
    await userEvent.type(screen.getByLabelText(/^name/i), 'Duplicate')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText("code 'DUP' already exists")).toBeInTheDocument()
  })

  it('closes when Cancel is clicked', async () => {
    zonesApiMocks.fetchZones.mockResolvedValue(emptyZonesPage())
    const onClose = vi.fn()
    renderModal({ onClose })

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onClose).toHaveBeenCalled()
  })
})
