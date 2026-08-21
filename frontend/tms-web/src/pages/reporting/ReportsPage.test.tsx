import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { KpiDailyRow, KpiReportView } from '../../shared/api/reportingApi'
import { ReportsPage } from './ReportsPage'

const reportingMocks = vi.hoisted(() => ({
  fetchKpiReport: vi.fn(),
  downloadKpiCsv: vi.fn(),
}))
vi.mock('../../shared/api/reportingApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/reportingApi')>(
    '../../shared/api/reportingApi',
  )
  return { ...actual, ...reportingMocks }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

function day(date: string, overrides: Partial<KpiDailyRow> = {}): KpiDailyRow {
  return {
    date,
    trips: 4,
    tripsCancelled: 1,
    tripsCompleted: 3,
    departuresMeasured: 3,
    departuresLate: 1,
    onTimeDeparturePercent: 66.7,
    deliveriesRecorded: 10,
    deliveriesDelivered: 8,
    deliverySuccessPercent: 80,
    exceptions: 3,
    exceptionsOpen: 2,
    ...overrides,
  }
}

/**
 * Every figure is a different number on purpose: `getByText('5')` proving one card is right would
 * prove nothing if two cards showed a 5.
 *
 * The assertions below deliberately check the <b>hints</b> rather than the formatted percentages.
 * A hint is i18next output and is exactly what the file says it is; a percentage goes through
 * `Intl.NumberFormat`, whose separator between the number and the `%` is a non-breaking space in
 * some ICU versions and nothing in others. Asserting on that would make this file fail on a Node
 * upgrade for a reason that has nothing to do with the screen.
 */
function report(overrides: Partial<KpiReportView> = {}): KpiReportView {
  return {
    from: '2026-03-01',
    to: '2026-03-02',
    days: 2,
    generatedAt: '2026-03-03T09:00:00Z',
    shipments: {
      trips: 41,
      tripsRun: 38,
      tripsCancelled: 3,
      tripsCompleted: 35,
      byStatus: {
        DRAFT: 1,
        CONFIRMED: 2,
        READY_FOR_DISPATCH: 0,
        IN_TRANSIT: 0,
        COMPLETED: 35,
        CANCELLED: 3,
      },
      departuresMeasured: 37,
      departuresLate: 4,
      onTimeDeparturePercent: 89.2,
      completionPercent: 92.1,
    },
    service: {
      stops: 210,
      stopsCompleted: 198,
      stopsSkipped: 5,
      stopsFailed: 7,
      serviceWindowsMeasured: 175,
      serviceWindowsMissed: 21,
      onTimeServicePercent: 88,
      deliveriesRecorded: 320,
      deliveriesDelivered: 301,
      deliveriesShort: 14,
      deliveriesNotAttempted: 5,
      deliverySuccessPercent: 94.1,
    },
    exceptions: { exceptions: 26, open: 6, resolved: 20, per100Trips: 68.4 },
    utilization: {
      trips: 33,
      weightUsedKg: 48000,
      weightCapacityKg: 60000,
      weightPercent: 80,
      volumeUsedM3: 210,
      volumeCapacityM3: 300,
      volumePercent: 70,
      palletsUsed: 120,
      palletCapacity: 150,
      palletsPercent: 79.5,
    },
    orders: {
      inputOrders: 500,
      planned: 465,
      unplanned: 35,
      readyToPlan: 30,
      notReady: 5,
      cancelled: 12,
      plannedPercent: 93,
    },
    tenders: {
      attempts: 44,
      accepted: 31,
      rejected: 6,
      expired: 2,
      cancelled: 1,
      awaitingResponse: 4,
      draft: 0,
      answered: 37,
      acceptancePercent: 83.8,
      rejectionPercent: 16.2,
    },
    cost: [
      {
        currency: 'PEN',
        tripsEstimated: 30,
        estimatedAmount: 90000,
        tripsWithActual: 12,
        actualAmount: 39000,
        tripsComparable: 12,
        comparableEstimated: 36000,
        comparableActual: 39000,
        variance: 3000,
        variancePercent: 8.3,
      },
    ],
    daily: [day('2026-03-01'), day('2026-03-02', { trips: 0 })],
    ...overrides,
  }
}

function mockCompany() {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics', timeZone: 'America/Lima' },
    status: 'ready',
    hasPermission: () => true,
    hasCapability: () => true,
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/reporting']}>
        <ReportsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('ReportsPage', () => {
  it('shows the percentages the server sent, with the denominator each one is over', async () => {
    mockCompany()
    reportingMocks.fetchKpiReport.mockResolvedValue(report())

    renderPage()

    // The count the percentage is about travels with it: 89% over 37 departures and 89% over four
    // is not the same claim, and the card is where that is said.
    expect(await screen.findByText('Sobre 37 salidas registradas')).toBeInTheDocument()
    expect(screen.getByText('301 de 320 entregas registradas')).toBeInTheDocument()
    expect(screen.getByText('Sobre 175 paradas con llegada y ventana')).toBeInTheDocument()
    expect(screen.getByText('38 realizados, 3 anulados')).toBeInTheDocument()
  })

  it('says what the utilisation figure covers, which is not every shipment in the range', async () => {
    mockCompany()
    reportingMocks.fetchKpiReport.mockResolvedValue(report())

    renderPage()

    // 33, not the 41 shipments the range holds: drafts and cancellations have no frozen limit.
    expect(await screen.findByText('Sobre 33 envíos con capacidad congelada')).toBeInTheDocument()
  })

  it('renders a dash, not a zero, for a range in which nothing was measured', async () => {
    mockCompany()
    reportingMocks.fetchKpiReport.mockResolvedValue(
      report({
        shipments: {
          ...report().shipments,
          departuresMeasured: 0,
          departuresLate: 0,
          onTimeDeparturePercent: null,
        },
      }),
    )

    renderPage()

    // "Nothing was measured" is a different statement from "nothing was punctual", and the hint is
    // what keeps the dash from being read as the second one.
    expect(await screen.findByText('Ninguna salida registrada en el rango')).toBeInTheDocument()
    expect(screen.queryByText('Sobre 0 salidas registradas')).not.toBeInTheDocument()
  })

  it('says a denied section is not permitted rather than showing it as zero', async () => {
    mockCompany()
    reportingMocks.fetchKpiReport.mockResolvedValue(report({ orders: null, tenders: null, cost: null }))

    renderPage()

    // Null means "you may not read this", which is not the same claim as "there is none of it".
    expect(await screen.findAllByText('No disponible con tus permisos')).toHaveLength(2)
    expect(screen.queryByText('Costo estimado contra real')).not.toBeInTheDocument()
  })

  it('keeps the cost of each currency apart and shows the difference with its sign', async () => {
    mockCompany()
    reportingMocks.fetchKpiReport.mockResolvedValue(report())

    renderPage()

    expect(await screen.findByText('PEN')).toBeInTheDocument()
    // The heading, not any text: the table repeats the same words in its caption for screen
    // readers, so a bare getByText matches two nodes.
    expect(screen.getByRole('heading', { name: 'Costo estimado contra real' })).toBeInTheDocument()
    // The comparable count sits in its own column: the difference is about twelve shipments, not
    // the thirty that were estimated, and the table has to be able to say which.
    expect(screen.getByText('Comparables')).toBeInTheDocument()
  })

  it('renders one detail row per day in the range, including the day nothing happened on', async () => {
    mockCompany()
    reportingMocks.fetchKpiReport.mockResolvedValue(report())

    renderPage()

    // Two rows for two days, the second of which had no shipment at all - a series that skipped it
    // would draw a quiet day and a busy one at the same spacing.
    const table = await screen.findByRole('table', { name: 'Detalle diario' })
    // One header row plus one row per day.
    expect(within(table).getAllByRole('row')).toHaveLength(3)
  })

  it('asks the server for the CSV rather than building one in the browser', async () => {
    mockCompany()
    reportingMocks.fetchKpiReport.mockResolvedValue(report())
    reportingMocks.downloadKpiCsv.mockResolvedValue({
      blob: new Blob(['date,trips\r\n'], { type: 'text/csv' }),
      fileName: 'tms-kpis-2026-03-01-to-2026-03-02.csv',
    })
    // jsdom implements neither, and the click is what the assertion is about anyway.
    URL.createObjectURL = vi.fn(() => 'blob:kpis')
    URL.revokeObjectURL = vi.fn()

    renderPage()

    // Waited for on purpose: the button is present from the first render and disabled until the
    // report answers, and clicking a disabled button proves nothing.
    await screen.findByText('38 realizados, 3 anulados')
    await userEvent.click(screen.getByRole('button', { name: /Exportar CSV/ }))

    // The file is the server's, not one assembled here: the screen and the export have to cover
    // the same days, and a CSV built in the browser would be a second opinion about that.
    expect(reportingMocks.downloadKpiCsv).toHaveBeenCalledWith(
      expect.objectContaining({ companyId: 'company-1' }),
    )
  })
})
