import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TripTrackingView } from '../../shared/api/trackingApi'
import { TripTrackingCard } from './TripTrackingCard'

/**
 * The card that answers "where is it".
 *
 * <p>What is worth testing here is that the *absences* stay distinguishable. Any of them could be
 * rendered as an empty card and the page would still look fine, which is exactly why they get
 * assertions: a dispatcher does something different about "it has not left", "we have no feed" and
 * "the feed has gone quiet", and the whole design of `TripTrackingView` exists to keep the three
 * apart. The position case is then checked for the one thing a stale card must not do - claim a
 * measurement is current.
 */

const NOW = new Date('2026-08-21T10:00:00Z')

function tracking(overrides: Partial<TripTrackingView> = {}): TripTrackingView {
  return {
    shipmentNumber: 'SH-00000042',
    status: 'IN_TRANSIT',
    trackable: true,
    providerConfigured: true,
    vehicleCode: 'VH-001',
    vehicleLicensePlate: 'ABC-123',
    lastPosition: null,
    track: [],
    ...overrides,
  }
}

function position(occurredAt: string, overrides: Record<string, unknown> = {}) {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    occurredAt,
    receivedAt: occurredAt,
    latitude: -12.046374,
    longitude: -77.042793,
    speedKph: null,
    headingDegrees: null,
    provider: 'acme-telematics',
    externalVehicleReference: null,
    ...overrides,
  }
}

afterEach(() => {
  vi.useRealTimers()
})

describe('TripTrackingCard', () => {
  it('says the trip has not left rather than showing an empty card', () => {
    render(
      <TripTrackingCard
        tracking={tracking({ trackable: false, status: 'CONFIRMED' })}
        loading={false}
        failed={false}
      />,
    )

    expect(screen.getByText(/todavía no ha salido/)).toBeInTheDocument()
  })

  it('separates "no provider configured" from "the provider has said nothing"', () => {
    const { rerender } = render(
      <TripTrackingCard tracking={tracking({ providerConfigured: false })} loading={false} failed={false} />,
    )
    expect(screen.getByText(/no tiene un proveedor de seguimiento/)).toBeInTheDocument()

    rerender(<TripTrackingCard tracking={tracking()} loading={false} failed={false} />)
    expect(screen.getByText(/no ha reportado ninguna posición/)).toBeInTheDocument()
  })

  it('reports a tracking failure without implying anything about the trip', () => {
    render(<TripTrackingCard tracking={undefined} loading={false} failed />)

    // The rest of the workspace has its own query and is unaffected - the copy says so, because a
    // dispatcher seeing one broken card must not assume the times beside it are broken too.
    expect(screen.getByText(/El resto del viaje sí está actualizado/)).toBeInTheDocument()
  })

  it('shows the position, its age and which feed reported it', () => {
    vi.useFakeTimers().setSystemTime(NOW)
    render(
      <TripTrackingCard
        tracking={tracking({ lastPosition: position('2026-08-21T09:56:00Z', { speedKph: 62.5 }) })}
        loading={false}
        failed={false}
      />,
    )

    expect(screen.getByText(/-12\.04637, -77\.04279/)).toBeInTheDocument()
    expect(screen.getByText(/hace 4 min/)).toBeInTheDocument()
    expect(screen.getByText(/62[.,]5 km\/h/)).toBeInTheDocument()
    // Attribution is not decoration: a position is a claim by a vendor, not a statement by TMS.
    expect(screen.getByText(/acme-telematics/)).toBeInTheDocument()
  })

  it('links out to a map with a decimal point, whatever the locale writes', () => {
    vi.useFakeTimers().setSystemTime(NOW)
    render(
      <TripTrackingCard
        tracking={tracking({ lastPosition: position('2026-08-21T09:59:30Z') })}
        loading={false}
        failed={false}
      />,
    )

    // A Spanish-formatted "-12,046374" would open a different place entirely.
    expect(screen.getByRole('link', { name: /Ver en el mapa/ })).toHaveAttribute(
      'href',
      'https://www.google.com/maps?q=-12.046374,-77.042793',
    )
  })

  it('marks a position nobody has updated in a quarter of an hour', () => {
    vi.useFakeTimers().setSystemTime(NOW)
    render(
      <TripTrackingCard
        tracking={tracking({ lastPosition: position('2026-08-21T09:20:00Z') })}
        loading={false}
        failed={false}
      />,
    )

    // Still shown - an old position is better than none - but never as a current fact.
    expect(screen.getByText(/hace 40 min/)).toHaveClass('text-warning-emphasis')
  })
})
