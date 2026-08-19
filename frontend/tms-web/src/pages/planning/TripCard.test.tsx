import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { TripView } from '../../shared/api/planningApi'
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

describe('TripCard', () => {
  it('shows "no vehicle assigned" when the trip has none yet', () => {
    render(<TripCard trip={trip()} onOpen={vi.fn()} />)

    expect(screen.getByText('Trip 3')).toBeInTheDocument()
    expect(screen.getByText('No vehicle assigned')).toBeInTheDocument()
  })

  it('shows the vehicle, carrier, and order/stop counts once assigned', () => {
    render(
      <TripCard
        trip={trip({
          vehicleCode: 'VH-1', vehicleLicensePlate: 'ABC-123', carrierName: 'Acme Carriers', orderCount: 4, stopCount: 2,
        })}
        onOpen={vi.fn()}
      />,
    )

    expect(screen.getByText('VH-1')).toBeInTheDocument()
    expect(screen.getByText('ABC-123', { exact: false })).toBeInTheDocument()
    expect(screen.getByText('Acme Carriers')).toBeInTheDocument()
    expect(screen.getByText('4 orders · 2 stops')).toBeInTheDocument()
  })

  it('calls onOpen when Open is clicked', async () => {
    const onOpen = vi.fn()
    render(<TripCard trip={trip()} onOpen={onOpen} />)

    await userEvent.click(screen.getByRole('button', { name: 'Open' }))

    expect(onOpen).toHaveBeenCalled()
  })
})
