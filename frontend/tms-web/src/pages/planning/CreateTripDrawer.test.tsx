import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { TripDetailView } from '../../shared/api/planningApi'
import i18n from '../../shared/i18n'
import { DEFAULT_LANGUAGE } from '../../shared/i18n/config'
import { CreateTripDrawer } from './CreateTripDrawer'

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
      <CreateTripDrawer companyId="company-1" runId="run-1" runVersion={3} onClose={onClose} onCreated={onCreated} />
    </QueryClientProvider>,
  )
  return { ...utils, onCreated, onClose }
}

afterEach(async () => {
  vi.clearAllMocks()
  await i18n.changeLanguage(DEFAULT_LANGUAGE)
})

describe('CreateTripDrawer', () => {
  it('creates a trip with no vehicle selected, sending the run version', async () => {
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([]))
    const detail = { trip: { id: 'trip-1', tripNumber: 1 } } as unknown as TripDetailView
    planningApiMocks.createTrip.mockResolvedValue(detail)
    const { onCreated } = renderModal()

    await userEvent.click(await screen.findByRole('button', { name: 'Crear viaje' }))

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
    await userEvent.selectOptions(screen.getByLabelText('Vehículo'), 'vehicle-1')
    await userEvent.click(screen.getByRole('button', { name: 'Crear viaje' }))

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

    await userEvent.click(await screen.findByRole('button', { name: 'Crear viaje' }))

    expect(
      await screen.findByText('This planning run was changed by someone else since it was loaded. Reload and try again.'),
    ).toBeInTheDocument()
    expect(onCreated).not.toHaveBeenCalled()
  })

  it('is a modal dialog named by its title, with focus inside it', async () => {
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([]))
    renderModal()

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleName('Nuevo viaje')
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true))
  })

  it('closes on Escape', async () => {
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([]))
    const { onClose } = renderModal()
    await screen.findByRole('dialog')

    await userEvent.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes from the cancel button', async () => {
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([]))
    const { onClose } = renderModal()

    await userEvent.click(await screen.findByRole('button', { name: 'Cancelar' }))

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('renders in English when the language is switched', async () => {
    await i18n.changeLanguage('en')
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([]))
    renderModal()

    expect(await screen.findByRole('button', { name: 'Create trip' })).toBeInTheDocument()
    expect(screen.getByLabelText('Vehicle')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Decide later' })).toBeInTheDocument()
  })
})
