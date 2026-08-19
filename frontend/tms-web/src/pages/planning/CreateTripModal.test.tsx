import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { TripDetailView } from '../../shared/api/planningApi'
import { CreateTripModal } from './CreateTripModal'

const planningApiMocks = vi.hoisted(() => ({ createTrip: vi.fn() }))
vi.mock('../../shared/api/planningApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/planningApi')>('../../shared/api/planningApi')
  return { ...actual, createTrip: planningApiMocks.createTrip }
})

const vehiclesApiMocks = vi.hoisted(() => ({ fetchVehicles: vi.fn() }))
vi.mock('../../shared/api/vehiclesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/vehiclesApi')>('../../shared/api/vehiclesApi')
  return { ...actual, fetchVehicles: vehiclesApiMocks.fetchVehicles }
})

function page<T>(content: T[]) {
  return { content, page: 0, size: 200, totalElements: content.length }
}

function renderModal(onCreated = vi.fn(), onClose = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <CreateTripModal companyId="company-1" runId="run-1" runVersion={3} onClose={onClose} onCreated={onCreated} />
    </QueryClientProvider>,
  )
  return { ...utils, onCreated, onClose }
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('CreateTripModal', () => {
  it('creates a trip with no vehicle selected, sending the run version', async () => {
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([]))
    const detail = { trip: { id: 'trip-1', tripNumber: 1 } } as unknown as TripDetailView
    planningApiMocks.createTrip.mockResolvedValue(detail)
    const { onCreated } = renderModal()

    await userEvent.click(await screen.findByRole('button', { name: 'Create trip' }))

    await waitFor(() =>
      expect(planningApiMocks.createTrip).toHaveBeenCalledWith('company-1', 'run-1', {
        vehicleId: null, plannedDepartureAt: null, version: 3,
      }),
    )
    expect(onCreated).toHaveBeenCalledWith(detail)
  })

  it('sends the selected vehicle id', async () => {
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(
      page([{ id: 'vehicle-1', code: 'VH-1', licensePlate: 'ABC-123' }]),
    )
    planningApiMocks.createTrip.mockResolvedValue({ trip: { id: 'trip-1' } } as unknown as TripDetailView)
    renderModal()

    await screen.findByRole('option', { name: 'VH-1 — ABC-123' })
    await userEvent.selectOptions(screen.getByLabelText('Vehicle'), 'vehicle-1')
    await userEvent.click(screen.getByRole('button', { name: 'Create trip' }))

    await waitFor(() =>
      expect(planningApiMocks.createTrip).toHaveBeenCalledWith(
        'company-1', 'run-1', expect.objectContaining({ vehicleId: 'vehicle-1' }),
      ),
    )
  })

  it('shows the backend refusal verbatim and does not report a created trip', async () => {
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([]))
    planningApiMocks.createTrip.mockRejectedValue(
      new ApiError(409, { code: 'conflict', detail: 'This planning run was changed by someone else since it was loaded. Reload and try again.' }, 'corr-1', 'boom'),
    )
    const { onCreated } = renderModal()

    await userEvent.click(await screen.findByRole('button', { name: 'Create trip' }))

    expect(
      await screen.findByText('This planning run was changed by someone else since it was loaded. Reload and try again.'),
    ).toBeInTheDocument()
    expect(onCreated).not.toHaveBeenCalled()
  })
})
