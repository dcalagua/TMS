import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/httpClient'
import type { AutoPlanView } from '../../shared/api/planningApi'
import i18n from '../../shared/i18n'
import { DEFAULT_LANGUAGE } from '../../shared/i18n/config'
import { AutoPlanDrawer } from './AutoPlanDrawer'

/**
 * The review step of automatic planning. Three things have to hold, and each is a way the
 * feature could quietly mislead a planner:
 *
 * - it previews before it writes, so nothing happens from opening the drawer;
 * - unplanned orders are shown with a reason, not folded into a success message;
 * - applying sends the run's version, so a stale board cannot plan a confirmed run.
 */

const planningApiMocks = vi.hoisted(() => ({ previewAutoPlan: vi.fn(), applyAutoPlan: vi.fn() }))
vi.mock('../../shared/api/planningApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/planningApi')>('../../shared/api/planningApi')
  return { ...actual, ...planningApiMocks }
})

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmDialog: vi.fn(),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const PLAN: AutoPlanView = {
  applied: false,
  engine: 'HEURISTIC_V1',
  proposed: [
    { vehicleId: 'v-1', vehicleCode: 'VEH-10T', routeId: 'r-1', orderNumbers: ['TO-1', 'TO-2'], stopCount: 2 },
    { vehicleId: 'v-2', vehicleCode: 'VEH-5T', routeId: null, orderNumbers: ['TO-3'], stopCount: 1 },
  ],
  created: [],
  unplanned: [
    { orderId: 'o-4', orderNumber: 'TO-4', reason: 'EXCEEDS_LARGEST_VEHICLE' },
    { orderId: 'o-5', orderNumber: 'TO-5', reason: 'NOT_SERVICEABLE_ON_DATE' },
  ],
  ordersConsidered: 5,
  vehiclesOffered: 4,
}

function renderDrawer(canApply = true) {
  const onClose = vi.fn()
  const onApplied = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <AutoPlanDrawer
        companyId="company-1"
        runId="run-1"
        runVersion={7}
        canApply={canApply}
        onClose={onClose}
        onApplied={onApplied}
      />
    </QueryClientProvider>,
  )
  return { onClose, onApplied }
}

afterEach(async () => {
  vi.clearAllMocks()
  await i18n.changeLanguage(DEFAULT_LANGUAGE)
})

describe('AutoPlanDrawer', () => {
  it('previews without writing anything', async () => {
    planningApiMocks.previewAutoPlan.mockResolvedValue(PLAN)

    renderDrawer()

    await screen.findByText('VEH-10T')
    expect(planningApiMocks.previewAutoPlan).toHaveBeenCalledWith('company-1', 'run-1', expect.anything())
    expect(planningApiMocks.applyAutoPlan).not.toHaveBeenCalled()
  })

  it('shows the proposal and its denominator, so the numbers can be judged', async () => {
    planningApiMocks.previewAutoPlan.mockResolvedValue(PLAN)

    renderDrawer()

    await screen.findByText('VEH-10T')
    expect(screen.getByText('VEH-5T')).toBeInTheDocument()
    expect(screen.getByText('TO-1, TO-2')).toBeInTheDocument()
    // 5 considered, 3 planned: without both, "2 trips" says nothing.
    expect(screen.getByText('Pedidos evaluados')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText(/HEURISTIC_V1/)).toBeInTheDocument()
  })

  it('names every unplanned order and what to do about it', async () => {
    planningApiMocks.previewAutoPlan.mockResolvedValue(PLAN)

    renderDrawer()

    await screen.findByText('TO-4')
    expect(screen.getByText(/Excede la capacidad de cualquier unidad/)).toBeInTheDocument()
    expect(screen.getByText('TO-5')).toBeInTheDocument()
    expect(screen.getByText(/no se atiende en esta fecha/)).toBeInTheDocument()
  })

  it('applies with the run version and reports how many trips were created', async () => {
    planningApiMocks.previewAutoPlan.mockResolvedValue(PLAN)
    planningApiMocks.applyAutoPlan.mockResolvedValue({
      ...PLAN,
      applied: true,
      created: [{}, {}],
    })

    const { onApplied, onClose } = renderDrawer()
    await screen.findByText('VEH-10T')

    await userEvent.click(screen.getByRole('button', { name: 'Aplicar propuesta' }))

    await waitFor(() =>
      expect(planningApiMocks.applyAutoPlan).toHaveBeenCalledWith('company-1', 'run-1', { version: 7 }),
    )
    expect(alertMocks.notifySuccess).toHaveBeenCalledWith('Propuesta aplicada', 'Se crearon 2 viajes en borrador.')
    expect(onApplied).toHaveBeenCalled()
    expect(onClose).toHaveBeenCalled()
  })

  it('cannot be applied by a caller who may not manage the plan', async () => {
    planningApiMocks.previewAutoPlan.mockResolvedValue(PLAN)

    renderDrawer(false)
    await screen.findByText('VEH-10T')

    expect(screen.getByRole('button', { name: 'Aplicar propuesta' })).toBeDisabled()
  })

  it('cannot be applied when there is nothing to apply', async () => {
    planningApiMocks.previewAutoPlan.mockResolvedValue({
      ...PLAN,
      proposed: [],
      unplanned: [{ orderId: 'o-1', orderNumber: 'TO-1', reason: 'NO_FLEET' }],
    })

    renderDrawer()
    await screen.findByText(/No hay nada que planificar/)

    expect(screen.getByRole('button', { name: 'Aplicar propuesta' })).toBeDisabled()
    expect(screen.getByText(/No hay unidades disponibles/)).toBeInTheDocument()
  })

  it('surfaces a failed apply instead of closing on it', async () => {
    planningApiMocks.previewAutoPlan.mockResolvedValue(PLAN)
    planningApiMocks.applyAutoPlan.mockRejectedValue(
      new ApiError(409, { title: 'Conflict', status: 409, code: 'conflict', detail: 'Run already confirmed.' },
        'test-correlation-id', 'Conflict'),
    )

    const { onClose } = renderDrawer()
    await screen.findByText('VEH-10T')

    await userEvent.click(screen.getByRole('button', { name: 'Aplicar propuesta' }))

    await waitFor(() => expect(alertMocks.notifyError).toHaveBeenCalled())
    expect(onClose).not.toHaveBeenCalled()
  })
})
