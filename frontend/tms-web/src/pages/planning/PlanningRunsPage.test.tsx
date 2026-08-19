import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { PlanningRunDetailView, PlanningRunView } from '../../shared/api/planningApi'
import { PlanningRunsPage } from './PlanningRunsPage'

const planningApiMocks = vi.hoisted(() => ({
  fetchPlanningRuns: vi.fn(),
  createPlanningRun: vi.fn(),
}))
vi.mock('../../shared/api/planningApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/planningApi')>('../../shared/api/planningApi')
  return { ...actual, ...planningApiMocks }
})

const originsApiMocks = vi.hoisted(() => ({ fetchOrigins: vi.fn() }))
vi.mock('../../shared/api/originsApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/originsApi')>('../../shared/api/originsApi')
  return { ...actual, fetchOrigins: originsApiMocks.fetchOrigins }
})

const companyMocks = vi.hoisted(() => ({ useCompany: vi.fn() }))
vi.mock('../../shared/company/CompanyContext', () => ({ useCompany: companyMocks.useCompany }))

const RUN: PlanningRunView = {
  id: 'run-1',
  planNumber: 'PLN-00000001',
  originId: 'origin-1',
  originCode: 'ORIGIN-A',
  originName: 'Origin A',
  planningDate: '2026-03-01',
  mode: 'MANUAL',
  status: 'DRAFT',
  notes: null,
  tripCount: 2,
  assignedOrderCount: 5,
  confirmedAt: null,
  cancelledAt: null,
  cancelReason: null,
  version: 0,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function page<T>(content: T[], overrides: Partial<{ page: number; size: number; totalElements: number }> = {}) {
  return { content, page: overrides.page ?? 0, size: overrides.size ?? 20, totalElements: overrides.totalElements ?? content.length }
}

function mockCompany(canManage: boolean) {
  companyMocks.useCompany.mockReturnValue({
    selected: { id: 'company-1', name: 'Acme Logistics' },
    hasPermission: (permission: string) => (permission === 'planning.plan:manage' ? canManage : true),
  })
}

function BoardStub() {
  const { runId } = useParams()
  return <div>Board for {runId}</div>
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/planning']}>
        <Routes>
          <Route path="/planning" element={<PlanningRunsPage />} />
          <Route path="/planning/:runId" element={<BoardStub />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('PlanningRunsPage', () => {
  it('shows a loading state while the first page is fetched', () => {
    mockCompany(true)
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    planningApiMocks.fetchPlanningRuns.mockReturnValue(new Promise(() => {}))

    renderPage()

    expect(screen.getByText('Cargando registros...')).toBeInTheDocument()
  })

  it('shows an empty state when the company has no planning runs yet', async () => {
    mockCompany(true)
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    planningApiMocks.fetchPlanningRuns.mockResolvedValue(page([]))

    renderPage()

    expect(await screen.findByText('No planning runs found')).toBeInTheDocument()
  })

  it('shows an error state with a retry action when the request fails', async () => {
    mockCompany(true)
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    planningApiMocks.fetchPlanningRuns.mockRejectedValue(new ApiError(500, { code: 'internal-error' }, 'corr-1', 'boom'))

    renderPage()

    expect(await screen.findByText('Ocurrió un error de nuestro lado. Vuelve a intentarlo.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })

  it('lists runs with origin, status and counts', async () => {
    mockCompany(true)
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    planningApiMocks.fetchPlanningRuns.mockResolvedValue(page([RUN]))

    renderPage()

    expect(await screen.findByText('PLN-00000001')).toBeInTheDocument()
    expect(screen.getByText('Origin A')).toBeInTheDocument()
    expect(screen.getByText('Draft', { selector: 'span' })).toBeInTheDocument()
  })

  it('hides the New run action for a caller without planning.plan:manage', async () => {
    mockCompany(false)
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    planningApiMocks.fetchPlanningRuns.mockResolvedValue(page([RUN]))

    renderPage()
    await screen.findByText('PLN-00000001')

    expect(screen.queryByRole('button', { name: 'Nueva corrida' })).not.toBeInTheDocument()
  })

  it('navigates to the board when Open is clicked', async () => {
    mockCompany(true)
    originsApiMocks.fetchOrigins.mockResolvedValue(page([]))
    planningApiMocks.fetchPlanningRuns.mockResolvedValue(page([RUN]))

    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Open' }))

    expect(await screen.findByText('Board for run-1')).toBeInTheDocument()
  })

  it('creates a run and navigates straight to its board', async () => {
    mockCompany(true)
    originsApiMocks.fetchOrigins.mockResolvedValue(page([{ id: 'origin-1', code: 'ORIGIN-A', name: 'Origin A' }]))
    planningApiMocks.fetchPlanningRuns.mockResolvedValue(page([]))
    const created: PlanningRunDetailView = { run: RUN, trips: [] }
    planningApiMocks.createPlanningRun.mockResolvedValue(created)

    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Nueva corrida' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.selectOptions(within(dialog).getByLabelText(/origin/i), 'origin-1')
    await userEvent.type(within(dialog).getByLabelText(/planning date/i), '2026-03-01')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create run' }))

    await waitFor(() =>
      expect(planningApiMocks.createPlanningRun).toHaveBeenCalledWith('company-1', {
        originId: 'origin-1',
        planningDate: '2026-03-01',
        notes: null,
      }),
    )
    expect(await screen.findByText('Board for run-1')).toBeInTheDocument()
  })
})
