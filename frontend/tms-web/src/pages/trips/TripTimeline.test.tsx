import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { TransportEventView } from '../../shared/api/planningApi'
import { TripTimeline } from './TripTimeline'

/**
 * The trip's execution log.
 *
 * <p>What is worth testing here is the same thing the tracking card's suite protects: that the
 * *absences* stay distinguishable. The timeline is append-only, so "nothing has been recorded" is a
 * claim about the trip rather than about the screen - and until job 14 the component had no way to
 * say anything else, because it was handed `events` and `loading` and nothing more. A failed read
 * arrived as `[]` and was rendered as an empty day, which told a supervisor checking whether a
 * driver had reported an arrival that no such report existed.
 */

function event(overrides: Partial<TransportEventView> = {}): TransportEventView {
  return {
    id: 'event-1',
    tripId: 'trip-1',
    tripStopId: null,
    stopSequence: null,
    stopDestinationCode: null,
    stopDestinationName: null,
    eventType: 'TRIP_DISPATCHED',
    eventTime: '2026-08-20T08:12:00Z',
    recordedAt: '2026-08-20T08:12:00Z',
    source: 'OPERATOR',
    actorName: 'ana@ebim.test',
    notes: null,
    metadata: null,
    ...overrides,
  }
}

describe('TripTimeline', () => {
  it('reports a failed read as a failure and never as an empty day', () => {
    render(<TripTimeline events={[]} loading={false} failed />)

    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByText(/No se pudo cargar la línea de tiempo/)).toBeInTheDocument()
    // The distinction this component exists to keep: the empty copy must be absent, or the screen
    // is still asserting that the trip has no history.
    expect(screen.queryByText('Todavía no se ha registrado nada en este viaje.')).not.toBeInTheDocument()
  })

  it('says the log itself survived, so one broken card does not discredit the rest', () => {
    render(<TripTimeline events={[]} loading={false} failed />)

    expect(screen.getByText(/sigue registrado/)).toBeInTheDocument()
  })

  it('offers the read again rather than making the operator reload the workspace', async () => {
    const onRetry = vi.fn()
    render(<TripTimeline events={[]} loading={false} failed onRetry={onRetry} />)

    await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))

    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('still says plainly that nothing has happened when the read succeeded and was empty', () => {
    render(<TripTimeline events={[]} loading={false} />)

    expect(screen.getByText('Todavía no se ha registrado nada en este viaje.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('announces loading to a screen reader instead of only showing grey text', () => {
    render(<TripTimeline events={[]} loading />)

    expect(screen.getByRole('status')).toHaveTextContent('Cargando la línea de tiempo')
  })

  it('renders an entry with its label, actor and stop once the events arrive', () => {
    render(
      <TripTimeline
        events={[
          event({
            eventType: 'ARRIVED_AT_STOP',
            tripStopId: 'stop-1',
            stopSequence: 1,
            stopDestinationName: 'Tienda Uno',
          }),
        ]}
        loading={false}
      />,
    )

    expect(screen.getByText('Llegada a la parada')).toBeInTheDocument()
    expect(screen.getByText(/ana@ebim.test/)).toBeInTheDocument()
    expect(screen.getByText(/Parada 1 · Tienda Uno/)).toBeInTheDocument()
  })
})
