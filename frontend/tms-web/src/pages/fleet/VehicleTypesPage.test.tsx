import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { VehicleTypeView } from '../../shared/api/vehicleTypesApi'
import { VehicleTypesPage } from './VehicleTypesPage'

const vehicleTypesApiMocks = vi.hoisted(() => ({
  fetchVehicleTypes: vi.fn(),
  createVehicleType: vi.fn(),
  updateVehicleType: vi.fn(),
  activateVehicleType: vi.fn(),
  deactivateVehicleType: vi.fn(),
}))
vi.mock('../../shared/api/vehicleTypesApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../shared/api/vehicleTypesApi')>('../../shared/api/vehicleTypesApi')
  return { ...actual, ...vehicleTypesApiMocks }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const VEHICLE_TYPE: VehicleTypeView = {
  id: 'type-1',
  code: 'TRUCK-10T',
  name: '10 ton truck',
  maxWeightKg: 10000,
  maxVolumeM3: 40,
  maxPallets: 20,
  lengthM: null,
  widthM: null,
  heightM: null,
  bodyType: null,
  temperatureControlled: false,
  minTemperatureCelsius: null,
  maxTemperatureCelsius: null,
  axles: null,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function page(content: VehicleTypeView[], overrides: Partial<{ page: number; size: number; totalElements: number }> = {}) {
  return { content, page: overrides.page ?? 0, size: overrides.size ?? 25, totalElements: overrides.totalElements ?? content.length }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'fleet.vehicle_type:manage' ? canManage : true),
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <VehicleTypesPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('VehicleTypesPage', () => {
  it('shows a loading state while the first page is fetched', () => {
    mockCompany(true)
    vehicleTypesApiMocks.fetchVehicleTypes.mockReturnValue(new Promise(() => {}))

    renderPage()

    expect(screen.getByText('Loading records...')).toBeInTheDocument()
  })

  it('shows an empty state when the company has no vehicle types yet', async () => {
    mockCompany(true)
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('No vehicle types found')).toBeInTheDocument()
  })

  it('shows an error state with a retry action when the request fails', async () => {
    mockCompany(true)
    vehicleTypesApiMocks.fetchVehicleTypes.mockRejectedValue(
      new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'),
    )

    renderPage()

    expect(await screen.findByText('Something went wrong on our side. Please try again.')).toBeInTheDocument()
  })

  it('lists vehicle types returned by the backend', async () => {
    mockCompany(true)
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(page([VEHICLE_TYPE], { totalElements: 60, size: 25 }))

    renderPage()

    expect(await screen.findByText('TRUCK-10T')).toBeInTheDocument()
    expect(screen.getByText('10 ton truck')).toBeInTheDocument()
    expect(screen.getByText(/Page 1 of 3/)).toBeInTheDocument()
  })

  it('hides create and manage actions for a caller without fleet.vehicle_type:manage', async () => {
    mockCompany(false)
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(page([VEHICLE_TYPE]))

    renderPage()

    await screen.findByText('TRUCK-10T')

    expect(screen.queryByRole('button', { name: 'New vehicle type' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
  })

  it('creates a vehicle type through the modal and refreshes the list', async () => {
    mockCompany(true)
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(page([]))
    vehicleTypesApiMocks.createVehicleType.mockResolvedValue({ ...VEHICLE_TYPE, code: 'TANKER' })

    renderPage()
    await screen.findByText('No vehicle types found')

    await userEvent.click(screen.getByRole('button', { name: 'New vehicle type' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText(/^code/i), 'TANKER')
    await userEvent.type(within(dialog).getByLabelText(/^name/i), 'Tanker')
    await userEvent.type(within(dialog).getByLabelText(/max weight/i), '15000')
    await userEvent.type(within(dialog).getByLabelText(/max volume/i), '20')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(vehicleTypesApiMocks.createVehicleType).toHaveBeenCalledWith(
        'company-1', expect.objectContaining({ code: 'TANKER' }),
      ),
    )
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Vehicle type created')
  })

  it('deactivates a vehicle type only after the confirmation dialog is accepted', async () => {
    mockCompany(true)
    vehicleTypesApiMocks.fetchVehicleTypes.mockResolvedValue(page([VEHICLE_TYPE]))
    vehicleTypesApiMocks.deactivateVehicleType.mockResolvedValue({ ...VEHICLE_TYPE, active: false })

    alertMocks.confirmAction.mockResolvedValueOnce(true)
    renderPage()
    await screen.findByText('TRUCK-10T')

    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }))

    await waitFor(() => expect(vehicleTypesApiMocks.deactivateVehicleType).toHaveBeenCalledWith('company-1', 'type-1'))
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Vehicle type deactivated', '10 ton truck')
  })
})
