import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RateCardView } from '../../shared/api/ratesApi'
import { RateCardsPage } from './RateCardsPage'

const ratesApiMocks = vi.hoisted(() => ({
  fetchRateCards: vi.fn(),
  createRateCard: vi.fn(),
  updateRateCard: vi.fn(),
  activateRateCard: vi.fn(),
  deactivateRateCard: vi.fn(),
}))
vi.mock('../../shared/api/ratesApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/ratesApi')>('../../shared/api/ratesApi')
  return { ...actual, ...ratesApiMocks }
})

const carriersApiMocks = vi.hoisted(() => ({ fetchCarriers: vi.fn() }))
vi.mock('../../shared/api/carriersApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/carriersApi')>('../../shared/api/carriersApi')
  return { ...actual, ...carriersApiMocks }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const CARD: RateCardView = {
  id: 'card-1',
  code: 'NORTE-2026',
  name: 'Corredor norte 2026',
  carrierId: 'carrier-1',
  carrierCode: 'ACME',
  carrierName: 'Acme Transport S.A.',
  scope: 'ROUTE',
  scopeTargetId: 'route-1',
  scopeTargetCode: 'RT-NORTE',
  scopeTargetName: 'Norte',
  vehicleTypeId: null,
  vehicleTypeCode: null,
  vehicleTypeName: null,
  currency: 'PEN',
  validFrom: '2026-01-01',
  validTo: null,
  baseAmount: 120,
  amountPerKm: 0.85,
  amountPerKg: null,
  amountPerM3: null,
  amountPerPallet: null,
  minimumAmount: null,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function page(content: RateCardView[]) {
  return { content, page: 0, size: 25, totalElements: content.length }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'rates.rate_card:manage' ? canManage : true),
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <RateCardsPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('RateCardsPage', () => {
  it('shows an empty state when the company has no rate cards yet', async () => {
    mockCompany(true)
    ratesApiMocks.fetchRateCards.mockResolvedValue(page([]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('Sin tarifas')).toBeInTheDocument()
  })

  it('renders the whole agreement in one row: scope, validity and what it charges', async () => {
    mockCompany(true)
    ratesApiMocks.fetchRateCards.mockResolvedValue(page([CARD]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('NORTE-2026')).toBeInTheDocument()
    expect(screen.getByText('Ruta')).toBeInTheDocument()
    expect(screen.getByText('RT-NORTE')).toBeInTheDocument()
    // An open-ended card says so rather than showing an empty second date.
    expect(screen.getByText(/sin fin/)).toBeInTheDocument()
    expect(screen.getByText(/Base/)).toBeInTheDocument()
    expect(screen.getByText(/\/km/)).toBeInTheDocument()
  })

  it('says "any type" for a card that names no vehicle type', async () => {
    mockCompany(true)
    ratesApiMocks.fetchRateCards.mockResolvedValue(page([CARD]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('Cualquier tipo')).toBeInTheDocument()
  })

  it('offers no row actions and no create button without rates.rate_card:manage', async () => {
    mockCompany(false)
    ratesApiMocks.fetchRateCards.mockResolvedValue(page([CARD]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))

    renderPage()

    await screen.findByText('NORTE-2026')
    expect(screen.queryByRole('button', { name: 'Nueva tarifa' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Abrir menú de acciones' })).not.toBeInTheDocument()
  })

  it('sends the in-force filter as a date the backend can apply', async () => {
    mockCompany(true)
    ratesApiMocks.fetchRateCards.mockResolvedValue(page([CARD]))
    carriersApiMocks.fetchCarriers.mockResolvedValue(page([]))

    renderPage()
    await screen.findByText('NORTE-2026')

    const onDate = screen.getByLabelText('Vigentes el día')
    await userEvent.clear(onDate)
    await userEvent.type(onDate, '2026-08-20')
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar filtros' }))

    await waitFor(() => {
      expect(ratesApiMocks.fetchRateCards).toHaveBeenLastCalledWith(
        expect.objectContaining({ onDate: '2026-08-20' }),
      )
    })
  })
})
