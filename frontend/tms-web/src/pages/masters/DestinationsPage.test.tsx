import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { DestinationView } from '../../shared/api/destinationsApi'
import { DestinationsPage } from './DestinationsPage'

const destinationsApiMocks = vi.hoisted(() => ({
  fetchDestinations: vi.fn(),
  createDestination: vi.fn(),
  updateDestination: vi.fn(),
  activateDestination: vi.fn(),
  deactivateDestination: vi.fn(),
}))
vi.mock('../../shared/api/destinationsApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/destinationsApi')>('../../shared/api/destinationsApi')
  return { ...actual, ...destinationsApiMocks }
})

const zonesApiMocks = vi.hoisted(() => ({ fetchZones: vi.fn() }))
vi.mock('../../shared/api/zonesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/zonesApi')>('../../shared/api/zonesApi')
  return { ...actual, fetchZones: zonesApiMocks.fetchZones }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

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
  externalReference: null,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function page<T>(content: T[], overrides: Partial<{ page: number; size: number; totalElements: number }> = {}) {
  return { content, page: overrides.page ?? 0, size: overrides.size ?? 25, totalElements: overrides.totalElements ?? content.length }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'masterdata.destination:manage' ? canManage : true),
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <DestinationsPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('DestinationsPage', () => {
  it('shows a loading state while the first page is fetched', () => {
    mockCompany(true)
    destinationsApiMocks.fetchDestinations.mockReturnValue(new Promise(() => {}))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(screen.getByText('Loading records...')).toBeInTheDocument()
  })

  it('shows an empty state when the company has no destinations yet', async () => {
    mockCompany(true)
    destinationsApiMocks.fetchDestinations.mockResolvedValue(page([]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('No destinations found')).toBeInTheDocument()
  })

  it('shows an error state with a retry action when the request fails', async () => {
    mockCompany(true)
    destinationsApiMocks.fetchDestinations.mockRejectedValue(
      new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'),
    )
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('Something went wrong on our side. Please try again.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('lists destinations returned by the backend and shows pagination once there is more than one page', async () => {
    mockCompany(true)
    destinationsApiMocks.fetchDestinations.mockResolvedValue(page([DESTINATION], { totalElements: 60, size: 25 }))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('NORTH-STORE')).toBeInTheDocument()
    expect(screen.getByText('Zone A')).toBeInTheDocument()
    expect(screen.getByText('Miraflores / Lima')).toBeInTheDocument()
    expect(screen.getByText(/Page 1 of 3/)).toBeInTheDocument()
  })

  it('hides create and manage actions for a caller without masterdata.destination:manage', async () => {
    mockCompany(false)
    destinationsApiMocks.fetchDestinations.mockResolvedValue(page([DESTINATION]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()

    await screen.findByText('NORTH-STORE')

    expect(screen.queryByRole('button', { name: 'New destination' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deactivate' })).not.toBeInTheDocument()
  })

  it('creates a destination through the modal and refreshes the list', async () => {
    mockCompany(true)
    destinationsApiMocks.fetchDestinations.mockResolvedValue(page([]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))
    destinationsApiMocks.createDestination.mockResolvedValue({ ...DESTINATION, code: 'NEW-DEST' })

    renderPage()
    await screen.findByText('No destinations found')

    await userEvent.click(screen.getByRole('button', { name: 'New destination' }))
    const dialog = within(screen.getByRole('dialog'))
    await userEvent.type(dialog.getByLabelText(/^code/i), 'NEW-DEST')
    await userEvent.type(dialog.getByLabelText(/^name/i), 'New Destination')
    await userEvent.click(dialog.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(destinationsApiMocks.createDestination).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ code: 'NEW-DEST' }),
      ),
    )
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Destination created')
  })

  it('deactivates a destination only after the confirmation dialog is accepted', async () => {
    mockCompany(true)
    destinationsApiMocks.fetchDestinations.mockResolvedValue(page([DESTINATION]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))
    destinationsApiMocks.deactivateDestination.mockResolvedValue({ ...DESTINATION, active: false })

    alertMocks.confirmAction.mockResolvedValueOnce(false)
    renderPage()
    await screen.findByText('NORTH-STORE')

    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await waitFor(() => expect(alertMocks.confirmAction).toHaveBeenCalled())
    expect(destinationsApiMocks.deactivateDestination).not.toHaveBeenCalled()

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    await waitFor(() =>
      expect(destinationsApiMocks.deactivateDestination).toHaveBeenCalledWith('company-1', 'destination-1'),
    )
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Destination deactivated', 'North Store')
  })

  it('applies the code filter to the query', async () => {
    mockCompany(true)
    destinationsApiMocks.fetchDestinations.mockResolvedValue(page([DESTINATION]))
    zonesApiMocks.fetchZones.mockResolvedValue(page([]))

    renderPage()
    await screen.findByText('NORTH-STORE')

    await userEvent.type(screen.getByLabelText(/^code$/i), 'north')
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }))

    await waitFor(() =>
      expect(destinationsApiMocks.fetchDestinations).toHaveBeenLastCalledWith(
        expect.objectContaining({ code: 'north' }),
      ),
    )
  })
})
