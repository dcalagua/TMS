import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TripTenderView } from '../../shared/api/tendersApi'
import { TripTenderCard } from './TripTenderCard'

const tendersApiMocks = vi.hoisted(() => ({
  fetchTripTenders: vi.fn(),
  createTender: vi.fn(),
  updateTenderTerms: vi.fn(),
  sendTender: vi.fn(),
  acceptTender: vi.fn(),
  rejectTender: vi.fn(),
  withdrawTender: vi.fn(),
}))
vi.mock('../../shared/api/tendersApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/tendersApi')>(
    '../../shared/api/tendersApi',
  )
  return { ...actual, ...tendersApiMocks }
})

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
  promptForText: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const BASE: TripTenderView = {
  id: 'tender-1',
  tripId: 'trip-1',
  attempt: 1,
  status: 'DRAFT',
  carrierId: 'carrier-1',
  carrierName: 'Transportes ACME',
  // Deliberately under a thousand: the assertion below is about the card rendering the offer, not
  // about which grouping separator this build's ICU data picks for es.
  offeredAmount: 240,
  currency: 'PEN',
  notes: 'Carga 06:00, puerta B',
  expiresAt: null,
  sentAt: null,
  respondedAt: null,
  responseSource: null,
  respondedByClient: null,
  responseNotes: null,
  expiredAt: null,
  cancelledAt: null,
  cancelReason: null,
  allowedTransitions: ['SENT', 'CANCELLED'],
  createdAt: '2026-08-21T09:00:00Z',
  updatedAt: '2026-08-21T09:00:00Z',
}

const SENT: TripTenderView = {
  ...BASE,
  status: 'SENT',
  sentAt: '2026-08-21T14:10:00Z',
  expiresAt: '2026-08-22T12:00:00Z',
  allowedTransitions: ['ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED'],
}

const REJECTED: TripTenderView = {
  ...SENT,
  status: 'REJECTED',
  respondedAt: '2026-08-21T15:00:00Z',
  responseSource: 'INTEGRATION',
  responseNotes: 'No hay 12t disponible el 24',
  allowedTransitions: [],
}

const ACCEPTED: TripTenderView = {
  ...SENT,
  id: 'tender-2',
  attempt: 2,
  status: 'ACCEPTED',
  respondedAt: '2026-08-21T16:00:00Z',
  responseSource: 'OPERATOR',
  responseNotes: null,
  allowedTransitions: [],
}

function renderCard(
  tenders: TripTenderView[],
  { canManage = true, offerable = true } = {},
) {
  tendersApiMocks.fetchTripTenders.mockResolvedValue(tenders)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <TripTenderCard
        companyId="company-1"
        tripId="trip-1"
        carrierName="Transportes ACME"
        offerable={offerable}
        canManage={canManage}
      />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('TripTenderCard', () => {
  it('offers the shipment when nobody has been asked yet', async () => {
    renderCard([])

    expect(
      await screen.findByText('Este viaje todavía no se ha ofertado a su transportista.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ofertar al transportista' })).toBeInTheDocument()
  })

  it('explains the window instead of offering a button the server would refuse', async () => {
    renderCard([], { offerable: false })

    expect(
      await screen.findByText('Un viaje solo se puede ofertar mientras está confirmado y no ha salido.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ofertar al transportista' })).not.toBeInTheDocument()
  })

  it('shows an offer that is waiting with its carrier, price and deadline', async () => {
    renderCard([SENT])

    expect(await screen.findByText('Esperando respuesta')).toBeInTheDocument()
    expect(screen.getByText('Transportes ACME')).toBeInTheDocument()
    expect(screen.getByText('PEN 240.00')).toBeInTheDocument()
    expect(screen.getByText(/Vence el/)).toBeInTheDocument()
  })

  it('keeps every attempt, so a rejection is still visible under the one that replaced it', async () => {
    renderCard([ACCEPTED, REJECTED])

    expect(await screen.findByText('Aceptada')).toBeInTheDocument()
    expect(screen.getByText('Rechazada')).toBeInTheDocument()
    expect(screen.getByText('No hay 12t disponible el 24')).toBeInTheDocument()
    expect(screen.getByText('Intento 1')).toBeInTheDocument()
    expect(screen.getByText('Intento 2')).toBeInTheDocument()
  })

  it('says whether the answer came from us or from the carrier', async () => {
    renderCard([REJECTED])

    expect(await screen.findByText(/Confirmado por el transportista/)).toBeInTheDocument()
  })

  it('will not offer a shipment again once its carrier has accepted', async () => {
    renderCard([ACCEPTED])

    await screen.findByText('Aceptada')
    expect(screen.queryByRole('button', { name: 'Volver a ofertar' })).not.toBeInTheDocument()
  })

  it('lets a rejected shipment be offered again', async () => {
    renderCard([REJECTED])

    await screen.findByText('Rechazada')
    expect(screen.getByRole('button', { name: 'Volver a ofertar' })).toBeInTheDocument()
  })

  it('renders only the actions the server said are possible', async () => {
    renderCard([SENT])

    await screen.findByText('Esperando respuesta')
    expect(screen.getByRole('button', { name: 'Registrar aceptación' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Registrar rechazo' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retirar oferta' })).toBeInTheDocument()
    // Sent offers are frozen: no edit, and no second send.
    expect(screen.queryByRole('button', { name: 'Enviar oferta' })).not.toBeInTheDocument()
  })

  it('confirms before sending, because the terms freeze at that moment', async () => {
    alertMocks.confirmAction.mockResolvedValue(true)
    tendersApiMocks.sendTender.mockResolvedValue([SENT])
    renderCard([BASE])

    await screen.findByText('Borrador')
    await userEvent.click(screen.getByRole('button', { name: 'Enviar oferta' }))

    await waitFor(() => {
      expect(tendersApiMocks.sendTender).toHaveBeenCalledWith('company-1', 'trip-1', 'tender-1')
    })
    expect(alertMocks.notifySuccess).toHaveBeenCalled()
  })

  it('does not send when the confirmation is dismissed', async () => {
    alertMocks.confirmAction.mockResolvedValue(false)
    renderCard([BASE])

    await screen.findByText('Borrador')
    await userEvent.click(screen.getByRole('button', { name: 'Enviar oferta' }))

    await waitFor(() => {
      expect(alertMocks.confirmAction).toHaveBeenCalled()
    })
    expect(tendersApiMocks.sendTender).not.toHaveBeenCalled()
  })

  it('asks for the carrier’s reason before recording a refusal', async () => {
    alertMocks.promptForText.mockResolvedValue('No hay 12t disponible el 24')
    tendersApiMocks.rejectTender.mockResolvedValue([REJECTED])
    renderCard([SENT])

    await screen.findByText('Esperando respuesta')
    await userEvent.click(screen.getByRole('button', { name: 'Registrar rechazo' }))

    await waitFor(() => {
      expect(tendersApiMocks.rejectTender).toHaveBeenCalledWith('company-1', 'trip-1', 'tender-1', {
        notes: 'No hay 12t disponible el 24',
      })
    })
  })

  it('records nothing when the reason prompt is dismissed', async () => {
    alertMocks.promptForText.mockResolvedValue(null)
    renderCard([SENT])

    await screen.findByText('Esperando respuesta')
    await userEvent.click(screen.getByRole('button', { name: 'Registrar rechazo' }))

    await waitFor(() => {
      expect(alertMocks.promptForText).toHaveBeenCalled()
    })
    expect(tendersApiMocks.rejectTender).not.toHaveBeenCalled()
  })

  it('shows the history but no actions without planning.tender:manage', async () => {
    renderCard([SENT], { canManage: false })

    expect(await screen.findByText('Esperando respuesta')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Registrar aceptación' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ofertar al transportista' })).not.toBeInTheDocument()
  })
})
