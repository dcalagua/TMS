import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DriverView } from '../../shared/api/driversApi'
import { ApiError } from '../../shared/api/httpClient'
import type { TripDetailView, TripView } from '../../shared/api/planningApi'
import i18n from '../../shared/i18n'
import { DEFAULT_LANGUAGE } from '../../shared/i18n/config'
import { TripDriverDrawer } from './TripDriverDrawer'

const planningApiMocks = vi.hoisted(() => ({ updateTripDriver: vi.fn() }))
vi.mock('../../shared/api/planningApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/planningApi')>('../../shared/api/planningApi')
  return { ...actual, updateTripDriver: planningApiMocks.updateTripDriver }
})

const driversApiMocks = vi.hoisted(() => ({ fetchDrivers: vi.fn() }))
vi.mock('../../shared/api/driversApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/driversApi')>('../../shared/api/driversApi')
  return { ...actual, fetchDrivers: driversApiMocks.fetchDrivers }
})

function page<T>(content: T[]) {
  return { content, page: 0, size: 200, totalElements: content.length }
}

function driver(overrides: Partial<DriverView> = {}): DriverView {
  return {
    id: 'driver-1',
    code: 'DR-ANA',
    firstName: 'Ana',
    lastName: 'Quispe',
    fullName: 'Quispe, Ana',
    documentType: 'DNI',
    documentNumber: '12345678',
    phone: null,
    licenseNumber: 'Q-987654',
    licenseCategory: 'A-IIB',
    licenseExpiresOn: '2027-05-31',
    licenseStatus: 'VALID',
    carrierId: null,
    carrierCode: null,
    carrierBusinessName: null,
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function trip(overrides: Partial<TripView> = {}): TripView {
  return {
    id: 'trip-1', companyId: 'company-1', planningRunId: 'run-1', planNumber: 'PL-00000001',
    planningDate: '2026-03-01', shipmentNumber: 'SH-00000001', originId: 'origin-1',
    originCode: 'LIM-01', originName: 'Lima depot', originLatitude: null, originLongitude: null,
    vehicleTypeCode: null, routeId: null, routeCode: null, routeName: null,
    tripNumber: 1, status: 'DRAFT', vehicleId: null, vehicleCode: null,
    vehicleLicensePlate: null, carrierId: null, carrierName: null, plannedDepartureAt: null,
    driverId: null, driverCode: null, driverName: null, driverPhone: null,
    driverLicenseNumber: null, driverLicenseExpiresOn: null, driverLicenseStatus: null,
    readyAt: null, actualDepartureAt: null, actualCompletionAt: null,
    cancelledAt: null, cancelReason: null, allowedTransitions: ['CONFIRMED', 'CANCELLED'],
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

/** `Select` is a button + listbox, not a native `<select>`: open it, then click the option. */
async function pickOption(comboboxName: RegExp | string, optionName: RegExp | string) {
  await userEvent.click(screen.getByRole('combobox', { name: comboboxName }))
  await userEvent.click(await screen.findByRole('option', { name: optionName }))
}

function renderModal(tripFixture: TripView, onUpdated = vi.fn(), onClose = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <TripDriverDrawer companyId="company-1" trip={tripFixture} onClose={onClose} onUpdated={onUpdated} />
    </QueryClientProvider>,
  )
  return { ...utils, onUpdated, onClose }
}

afterEach(async () => {
  vi.clearAllMocks()
  await i18n.changeLanguage(DEFAULT_LANGUAGE)
})

describe('TripDriverDrawer', () => {
  it('sends the trip version alongside the selected driver', async () => {
    driversApiMocks.fetchDrivers.mockResolvedValue(page([driver()]))
    const detail = { trip: trip({ driverId: 'driver-1' }) } as unknown as TripDetailView
    planningApiMocks.updateTripDriver.mockResolvedValue(detail)
    const { onUpdated } = renderModal(trip())

    await pickOption(/^Conductor/, 'Quispe, Ana (DR-ANA)')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar conductor' }))

    await waitFor(() =>
      expect(planningApiMocks.updateTripDriver).toHaveBeenCalledWith('company-1', 'trip-1', {
        driverId: 'driver-1', version: 5,
      }),
    )
    expect(onUpdated).toHaveBeenCalledWith(detail)
  })

  /** Clearing is a real instruction, not the absence of one: it releases the person for that day. */
  it('sends null when the driver is cleared', async () => {
    driversApiMocks.fetchDrivers.mockResolvedValue(page([driver()]))
    planningApiMocks.updateTripDriver.mockResolvedValue({} as TripDetailView)
    renderModal(trip({ driverId: 'driver-1', driverName: 'Quispe, Ana', driverCode: 'DR-ANA' }))

    await pickOption(/^Conductor/, 'Sin conductor')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar conductor' }))

    await waitFor(() =>
      expect(planningApiMocks.updateTripDriver).toHaveBeenCalledWith('company-1', 'trip-1', {
        driverId: null, version: 5,
      }),
    )
  })

  it('warns in the option label when a licence has expired or is about to', async () => {
    driversApiMocks.fetchDrivers.mockResolvedValue(
      page([
        driver({ id: 'd-1', code: 'DR-EXP', fullName: 'Rojas, Luis', licenseStatus: 'EXPIRED' }),
        driver({ id: 'd-2', code: 'DR-SOON', fullName: 'Diaz, Eva', licenseStatus: 'EXPIRING_SOON' }),
        driver({ id: 'd-3', code: 'DR-OK', fullName: 'Quispe, Ana', licenseStatus: 'VALID' }),
      ]),
    )
    renderModal(trip())

    await userEvent.click(screen.getByRole('combobox', { name: /^Conductor/ }))

    expect(await screen.findByRole('option', { name: 'Rojas, Luis (DR-EXP) — licencia vencida' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Diaz, Eva (DR-SOON) — licencia por vencer' })).toBeInTheDocument()
    // A valid licence is the normal case and gets no suffix - a warning on every row is no warning.
    expect(screen.getByRole('option', { name: 'Quispe, Ana (DR-OK)' })).toBeInTheDocument()
  })

  it('keeps a deactivated driver selectable so the select does not silently reset', async () => {
    driversApiMocks.fetchDrivers.mockResolvedValue(page([driver({ id: 'driver-2', code: 'DR-OTHER' })]))
    renderModal(trip({ driverId: 'driver-1', driverName: 'Quispe, Ana', driverCode: 'DR-ANA' }))

    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: /^Conductor/ })).toHaveTextContent('Quispe, Ana'),
    )
  })

  it('shows the backend refusal verbatim when the licence has expired', async () => {
    driversApiMocks.fetchDrivers.mockResolvedValue(page([driver()]))
    planningApiMocks.updateTripDriver.mockRejectedValue(
      new ApiError(
        400,
        // The code the backend actually sends for an InvalidRequestException (ApiExceptionHandler
        // maps it to MALFORMED_REQUEST); 'invalid-request' is not in ProblemType at all, so this
        // fixture was asserting a pass-through the real API could never trigger.
        { code: 'malformed-request', detail: 'Driver DR-ANA has a licence that expired on 2026-02-28.' },
        'corr-1',
        'boom',
      ),
    )
    const { onUpdated } = renderModal(trip())

    await pickOption(/^Conductor/, 'Quispe, Ana (DR-ANA)')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar conductor' }))

    expect(await screen.findByText('Driver DR-ANA has a licence that expired on 2026-02-28.')).toBeInTheDocument()
    expect(onUpdated).not.toHaveBeenCalled()
  })

  it('is a modal dialog named after the trip it is changing', async () => {
    driversApiMocks.fetchDrivers.mockResolvedValue(page([]))
    renderModal(trip())

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleName('Conductor del viaje 1')
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true))
  })

  it('renders in English when the language is switched', async () => {
    await i18n.changeLanguage('en')
    driversApiMocks.fetchDrivers.mockResolvedValue(page([]))
    renderModal(trip())

    expect(await screen.findByRole('button', { name: 'Save driver' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('combobox', { name: /^Driver/ }))
    expect(await screen.findByRole('option', { name: 'No driver' })).toBeInTheDocument()
  })
})
