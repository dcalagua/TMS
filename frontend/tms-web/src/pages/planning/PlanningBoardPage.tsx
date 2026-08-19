import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import type { ApiError } from '../../shared/api/httpClient'
import { cancelPlanningRun, confirmPlanningRun, fetchPlanningRun } from '../../shared/api/planningApi'
import { describeApiError, describePlanningError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import { confirmDialog, EmptyState, ErrorState, PageHeader, StatusBadge, type StatusTone } from '../../shared/ui/components'
import { LoadingState } from '../../shared/ui/components/LoadingState'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { CreateTripDrawer } from './CreateTripDrawer'
import { EligibleOrdersPanel } from './EligibleOrdersPanel'
import { TripCard } from './TripCard'
import { TripDetailDrawer } from './TripDetailDrawer'

const STATUS_TONE: Record<'DRAFT' | 'CONFIRMED' | 'CANCELLED', StatusTone> = {
  DRAFT: 'info',
  CONFIRMED: 'success',
  CANCELLED: 'danger',
}

/** Which half of the board a phone is showing. Two narrow columns side by side is unusable at
 * 360px, so below `lg` the panels become tabs instead of shrinking. */
type MobilePanel = 'orders' | 'trips'

/**
 * The planning board (`docs/domain/PLANNING_MANUAL_V1.md`, "The flow" steps 3-8): one call opens
 * the run with every trip's capacity summary already attached
 * (`docs/overnight/10_MANUAL_PLANNING_BACKEND.md` section 8 point 1), so this page never loops
 * over trips to build itself. Every mutation below re-syncs the board by invalidating this one
 * query rather than hand-merging partial responses into local state.
 */
export function PlanningBoardPage() {
  const { t } = useTranslation('planning')
  const enumLabels = useEnumLabels()
  const format = useFormat()
  const { runId } = useParams<{ runId: string }>()
  const { selected, hasPermission } = useCompany()
  const companyId = selected?.id ?? ''
  const canManageRun = hasPermission('planning.plan:manage') && hasPermission('planning.trip:manage')
  const canManageTrips = hasPermission('planning.trip:manage')
  const queryClient = useQueryClient()

  const queryKey = ['planning-run', companyId, runId]
  const runQuery = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchPlanningRun(companyId, runId as string, signal),
    enabled: runId !== undefined,
  })

  const [openTripId, setOpenTripId] = useState<string | null>(null)
  const [showCreateTrip, setShowCreateTrip] = useState(false)
  const [mobilePanel, setMobilePanel] = useState<MobilePanel>('orders')

  /**
   * Re-syncs both halves of the board. The eligible pool has to be invalidated too: taking an
   * order off a trip - or moving it - returns it to that pool, and without this the left panel
   * kept showing a stale list until the page was reloaded.
   */
  function refreshBoard() {
    void queryClient.invalidateQueries({ queryKey })
    void queryClient.invalidateQueries({ queryKey: ['eligible-orders', companyId, runId] })
  }

  async function confirmPlan() {
    if (!runQuery.data) return
    const { run } = runQuery.data
    const confirmed = await confirmDialog({
      title: t('boardScreen.confirmTitle'),
      text: t('boardScreen.confirmText', { number: run.planNumber }),
      confirmLabel: t('boardScreen.confirmPlan'),
    })
    if (!confirmed) return

    try {
      await confirmPlanningRun(companyId, run.id, { version: run.version })
      notifySuccess(t('boardScreen.confirmed'), run.planNumber)
      refreshBoard()
    } catch (error) {
      notifyError(t('boardScreen.confirmError'), describePlanningError(error as ApiError))
    }
  }

  async function cancelPlan() {
    if (!runQuery.data) return
    const { run } = runQuery.data
    const confirmed = await confirmDialog({
      title: t('boardScreen.cancelTitle'),
      text: t('boardScreen.cancelText', { number: run.planNumber }),
      confirmLabel: t('boardScreen.cancelPlan'),
      dangerous: true,
    })
    if (!confirmed) return

    try {
      await cancelPlanningRun(companyId, run.id, { version: run.version })
      notifySuccess(t('boardScreen.cancelled'), run.planNumber)
      refreshBoard()
    } catch (error) {
      notifyError(t('boardScreen.cancelError'), describePlanningError(error as ApiError))
    }
  }

  if (runQuery.isPending) {
    return <LoadingState label={t('boardScreen.loading')} />
  }

  if (runQuery.isError) {
    return (
      <ErrorState
        message={describeApiError(runQuery.error as ApiError)}
        onRetry={() => void runQuery.refetch()}
      />
    )
  }

  const { run, trips } = runQuery.data
  const isDraft = run.status === 'DRAFT'

  const ordersPanel = (
    <EligibleOrdersPanel
      companyId={companyId}
      run={run}
      trips={trips}
      canManage={isDraft && canManageTrips}
      onAssigned={refreshBoard}
    />
  )

  const tripsPanel =
    trips.length === 0 ? (
      <div className="tms-card">
        <EmptyState icon="bi-truck" title={t('boardScreen.noTrips')} message={t('boardScreen.noTripsHint')} />
      </div>
    ) : (
      <div className="row row-cols-1 row-cols-md-2 row-cols-xxl-3 g-3">
        {trips.map((trip) => (
          <div key={trip.id} className="col">
            <TripCard trip={trip} onOpen={() => setOpenTripId(trip.id)} />
          </div>
        ))}
      </div>
    )

  return (
    <div>
      <Link to="/planning" className="text-decoration-none small d-inline-flex align-items-center gap-1 mb-2">
        <i className="bi bi-arrow-left" aria-hidden="true" />
        {t('boardScreen.backToRuns')}
      </Link>

      <PageHeader
        icon="columns-gap"
        title={run.planNumber}
        meta={
          <>
            <StatusBadge label={enumLabels.planningRunStatus(run.status)} tone={STATUS_TONE[run.status]} />
            <span className="tms-badge tms-badge-neutral">
              {t('boardScreen.tripsCount', { count: trips.length })}
            </span>
          </>
        }
        description={`${run.originName ?? run.originCode} · ${format.date(run.planningDate)}`}
        actions={
          <div className="d-flex flex-wrap align-items-center gap-2">
            {isDraft && canManageTrips && (
              <button
                type="button"
                className="btn btn-sm btn-outline-primary d-inline-flex align-items-center gap-2"
                onClick={() => setShowCreateTrip(true)}
              >
                <i className="bi bi-plus-lg" aria-hidden="true" />
                {t('boardScreen.newTrip')}
              </button>
            )}
            {isDraft && canManageRun && (
              <>
                <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => void cancelPlan()}>
                  {t('boardScreen.cancelPlan')}
                </button>
                <button type="button" className="btn btn-sm btn-primary" onClick={() => void confirmPlan()}>
                  {t('boardScreen.confirmPlan')}
                </button>
              </>
            )}
          </div>
        }
      />

      {/* Below `lg` the two panels become tabs; above it they are a split view. */}
      <div className="btn-group w-100 mb-3 d-lg-none" role="group" aria-label={t('boardScreen.panels')}>
        {(['orders', 'trips'] as const).map((panel) => (
          <button
            key={panel}
            type="button"
            className={`btn btn-sm ${mobilePanel === panel ? 'btn-secondary' : 'btn-outline-secondary'}`}
            aria-pressed={mobilePanel === panel}
            onClick={() => setMobilePanel(panel)}
          >
            {panel === 'orders' ? t('boardScreen.tabOrders') : t('boardScreen.tabTrips')}
          </button>
        ))}
      </div>

      <div className="row g-3">
        <div className={`col-12 col-lg-4 ${mobilePanel === 'orders' ? '' : 'd-none d-lg-block'}`}>
          <h2 className="tms-section-title mb-2 d-none d-lg-block">{t('board.orders')}</h2>
          {ordersPanel}
        </div>
        <div className={`col-12 col-lg-8 tms-min-w-0 ${mobilePanel === 'trips' ? '' : 'd-none d-lg-block'}`}>
          <h2 className="tms-section-title mb-2 d-none d-lg-block">{t('board.trips')}</h2>
          {tripsPanel}
        </div>
      </div>

      {openTripId && (
        <TripDetailDrawer
          companyId={companyId}
          tripId={openTripId}
          siblingTrips={trips}
          canManage={canManageTrips}
          onClose={() => setOpenTripId(null)}
          onChanged={refreshBoard}
        />
      )}

      {showCreateTrip && (
        <CreateTripDrawer
          companyId={companyId}
          runId={run.id}
          runVersion={run.version}
          onClose={() => setShowCreateTrip(false)}
          onCreated={() => {
            setShowCreateTrip(false)
            notifySuccess(t('boardScreen.tripCreated'))
            refreshBoard()
          }}
        />
      )}

    </div>
  )
}
