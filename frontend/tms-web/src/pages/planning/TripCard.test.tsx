import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TripView } from '../../shared/api/planningApi'
import i18n from '../../shared/i18n'
import { DEFAULT_LANGUAGE } from '../../shared/i18n/config'
import { TripCard } from './TripCard'

function trip(overrides: Partial<TripView> = {}): TripView {
  return {
    id: 'trip-1', planningRunId: 'run-1', tripNumber: 3, status: 'DRAFT', vehicleId: null, vehicleCode: null,
    vehicleLicensePlate: null, carrierId: null, carrierName: null, plannedDepartureAt: null,
    capacity: {
      tripId: 'trip-1', source: 'NONE', orderCount: 0,
      weight: { used: 0, limit: null, remaining: null, percentUsed: null, exceeded: false, unlimited: true },
      volume: { used: 0, limit: null, remaining: null, percentUsed: null, exceeded: false, unlimited: true },
      pallets: { used: 0, limit: null, remaining: null, percentUsed: null, exceeded: false, unlimited: true },
      withinCapacity: true,
    },
    stopCount: 0, orderCount: 0, version: 0, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

/** A vehicle with real limits, so the card renders three measurable capacity bars. */
function loadedTrip(): TripView {
  return trip({
    vehicleCode: 'VH-1',
    vehicleLicensePlate: 'ABC-123',
    carrierName: 'Acme Carriers',
    orderCount: 4,
    stopCount: 2,
    plannedDepartureAt: '2026-03-01T08:30:00Z',
    capacity: {
      tripId: 'trip-1', source: 'LIVE', orderCount: 4,
      weight: { used: 8850, limit: 10000, remaining: 1150, percentUsed: 88.5, exceeded: false, unlimited: false },
      volume: { used: 18, limit: 20, remaining: 2, percentUsed: 90, exceeded: false, unlimited: false },
      pallets: { used: 18, limit: 20, remaining: 2, percentUsed: 90, exceeded: false, unlimited: false },
      withinCapacity: true,
    },
  })
}

afterEach(async () => {
  await i18n.changeLanguage(DEFAULT_LANGUAGE)
})

describe('TripCard', () => {
  it('shows "no vehicle assigned" when the trip has none yet', () => {
    render(<TripCard trip={trip()} onOpen={vi.fn()} />)

    expect(screen.getByText('Viaje 3')).toBeInTheDocument()
    expect(screen.getByText('Sin vehículo asignado')).toBeInTheDocument()
    expect(screen.getByText('Sin transportista')).toBeInTheDocument()
  })

  it('shows the vehicle, carrier, departure and the order/destination counts once assigned', () => {
    render(<TripCard trip={loadedTrip()} onOpen={vi.fn()} />)

    expect(screen.getByText('VH-1')).toBeInTheDocument()
    expect(screen.getByText('ABC-123', { exact: false })).toBeInTheDocument()
    expect(screen.getByText('Acme Carriers')).toBeInTheDocument()
    expect(screen.getByText('Pedidos')).toBeInTheDocument()
    expect(screen.getByText('4')).toBeInTheDocument()
    expect(screen.getByText('Destinos')).toBeInTheDocument()
  })

  it('renders all three capacity dimensions with used, limit and percentage', () => {
    render(<TripCard trip={loadedTrip()} onOpen={vi.fn()} />)

    expect(screen.getByText('8,850 kg / 10,000 kg')).toBeInTheDocument()
    expect(screen.getByText('(88.5%)')).toBeInTheDocument()
    expect(screen.getByText('18 m³ / 20 m³')).toBeInTheDocument()
    expect(screen.getByText('18 plt / 20 plt')).toBeInTheDocument()
    expect(screen.getAllByRole('progressbar')).toHaveLength(3)
  })

  it('marks an over-capacity trip in words, not only by colour', () => {
    const over = loadedTrip()
    over.capacity.weight = { used: 12000, limit: 10000, remaining: -2000, percentUsed: 120, exceeded: true, unlimited: false }
    over.capacity.withinCapacity = false

    render(<TripCard trip={over} onOpen={vi.fn()} />)

    expect(screen.getByText('Excede la capacidad')).toBeInTheDocument()
  })

  it('names its open action after the trip it opens', async () => {
    const onOpen = vi.fn()
    render(<TripCard trip={trip()} onOpen={onOpen} />)

    await userEvent.click(screen.getByRole('button', { name: 'Abrir el viaje 3' }))

    expect(onOpen).toHaveBeenCalled()
  })

  it('follows a language switch', async () => {
    await i18n.changeLanguage('en')

    render(<TripCard trip={trip()} onOpen={vi.fn()} />)

    expect(screen.getByText('Trip 3')).toBeInTheDocument()
    expect(screen.getByText('No vehicle assigned')).toBeInTheDocument()
  })
})
