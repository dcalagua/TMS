import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { TripDetailView, TripView } from '../../shared/api/planningApi'
import i18n from '../../shared/i18n'
import { DEFAULT_LANGUAGE } from '../../shared/i18n/config'
import { TripVehicleModal } from './TripVehicleModal'

const planningApiMocks = vi.hoisted(() => ({ updateTripVehicle: vi.fn() }))
vi.mock('../../shared/api/planningApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/planningApi')>('../../shared/api/planningApi')
  return { ...actual, updateTripVehicle: planningApiMocks.updateTripVehicle }
})

const vehiclesApiMocks = vi.hoisted(() => ({ fetchVehicles: vi.fn() }))
vi.mock('../../shared/api/vehiclesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/vehiclesApi')>('../../shared/api/vehiclesApi')
  return { ...actual, fetchVehicles: vehiclesApiMocks.fetchVehicles }
})

function page<T>(content: T[]) {
  return { content, page: 0, size: 200, totalElements: content.length }
}

function trip(overrides: Partial<TripView> = {}): TripView {
  return {
    id: 'trip-1', planningRunId: 'run-1', tripNumber: 1, status: 'DRAFT', vehicleId: null, vehicleCode: null,
    vehicleLicensePlate: null, carrierId: null, carrierName: null, plannedDepartureAt: null,
    capacity: {
      tripId: 'trip-1', source: 'NONE', orderCount: 0,
      weight: { used: 0, limit: null, remaining: null, percentUsed: null, exceeded: false, unlimited: true },
      volume: { used: 0, limit: null, remaining: null, percentUsed: null, exceeded: false, unlimited: true },
      pallets: { used: 0, limit: null, remaining: null, percentUsed: null, exceeded: false, unlimited: true },
      withinCapacity: true,
    },
    stopCount: 0, orderCount: 0, version: 5, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function renderModal(tripFixture: TripView, onUpdated = vi.fn(), onClose = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <TripVehicleModal companyId="company-1" trip={tripFixture} onClose={onClose} onUpdated={onUpdated} />
    </QueryClientProvider>,
  )
  return { ...utils, onUpdated, onClose }
}

afterEach(async () => {
  vi.clearAllMocks()
  await i18n.changeLanguage(DEFAULT_LANGUAGE)
})

describe('TripVehicleModal', () => {
  it('sends the trip version alongside the selected vehicle', async () => {
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([{ id: 'vehicle-1', code: 'VH-1', licensePlate: 'ABC-123' }]))
    const detail = { trip: trip({ vehicleId: 'vehicle-1', vehicleCode: 'VH-1' }) } as unknown as TripDetailView
    planningApiMocks.updateTripVehicle.mockResolvedValue(detail)
    const { onUpdated } = renderModal(trip())

    await screen.findByRole('option', { name: 'VH-1 — ABC-123' })
    await userEvent.selectOptions(screen.getByRole('combobox', { name: /^Vehículo/ }), 'vehicle-1')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar vehículo' }))

    await waitFor(() =>
      expect(planningApiMocks.updateTripVehicle).toHaveBeenCalledWith('company-1', 'trip-1', {
        vehicleId: 'vehicle-1', plannedDepartureAt: null, version: 5,
      }),
    )
    expect(onUpdated).toHaveBeenCalledWith(detail)
  })

  it('pre-selects the trip\'s current vehicle when swapping', async () => {
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(
      page([
        { id: 'vehicle-1', code: 'VH-1', licensePlate: 'ABC-123' },
        { id: 'vehicle-2', code: 'VH-2', licensePlate: 'XYZ-999' },
      ]),
    )
    renderModal(trip({ vehicleId: 'vehicle-2', vehicleCode: 'VH-2' }))

    await screen.findByRole('option', { name: 'VH-2 — XYZ-999' })
    expect(screen.getByRole('combobox', { name: /^Vehículo/ })).toHaveValue('vehicle-2')
  })

  it('shows the backend refusal verbatim when a downgrade no longer fits the current load', async () => {
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([{ id: 'vehicle-1', code: 'VH-1', licensePlate: 'ABC-123' }]))
    planningApiMocks.updateTripVehicle.mockRejectedValue(
      new ApiError(409, { code: 'conflict', detail: 'Trip 1 would exceed capacity: weight 1200.00/1000.00 kg.' }, 'corr-1', 'boom'),
    )
    const { onUpdated } = renderModal(trip())

    await screen.findByRole('option', { name: 'VH-1 — ABC-123' })
    await userEvent.selectOptions(screen.getByRole('combobox', { name: /^Vehículo/ }), 'vehicle-1')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar vehículo' }))

    expect(await screen.findByText('Trip 1 would exceed capacity: weight 1200.00/1000.00 kg.')).toBeInTheDocument()
    expect(onUpdated).not.toHaveBeenCalled()
  })

  it('is a modal dialog named after the trip it is changing', async () => {
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([]))
    renderModal(trip())

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleName('Vehiculo del viaje 1'.replace('Vehiculo', 'Vehículo'))
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true))
  })

  it('closes on Escape and from the cancel button', async () => {
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([]))
    const { onClose } = renderModal(trip())
    await screen.findByRole('dialog')

    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('renders in English when the language is switched', async () => {
    await i18n.changeLanguage('en')
    vehiclesApiMocks.fetchVehicles.mockResolvedValue(page([]))
    renderModal(trip())

    expect(await screen.findByRole('button', { name: 'Save vehicle' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Select a vehicle' })).toBeInTheDocument()
  })
})
