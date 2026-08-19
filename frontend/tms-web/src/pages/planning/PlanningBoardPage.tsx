import { useEnumLabels } from '../../shared/i18n/enums'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import type { ApiError } from '../../shared/api/httpClient'
import { cancelPlanningRun, confirmPlanningRun, fetchPlanningRun } from '../../shared/api/planningApi'
import { describeApiError, describePlanningError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
import { confirmDialog, ErrorState, PageHeader, StatusBadge, type StatusTone } from '../../shared/ui/components'
import { LoadingState } from '../../shared/ui/components/LoadingState'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { CreateTripModal } from './CreateTripModal'
import { EligibleOrdersPanel } from './EligibleOrdersPanel'
import { TripCard } from './TripCard'
import { TripDetailDrawer } from './TripDetailDrawer'

const STATUS_TONE: Record<'DRAFT' | 'CONFIRMED' | 'CANCELLED', StatusTone> = {
  DRAFT: 'info',
  CONFIRMED: 'success',
  CANCELLED: 'danger',
}

/**
 * The planning board (`docs/domain/PLANNING_MANUAL_V1.md`, "The flow" steps 3-8): one call opens
 * the run with every trip's capacity summary already attached
 * (`docs/overnight/10_MANUAL_PLANNING_BACKEND.md` section 8 point 1), so this page never loops
 * over trips to build itself. Every mutation below re-syncs the board by invalidating this one
 * query rather than hand-merging partial responses into local state.
 */
export function PlanningBoardPage() {
  const enumLabels = useEnumLabels()
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

  function refreshBoard() {
    void queryClient.invalidateQueries({ queryKey })
  }

  async function confirmPlan() {
    if (!runQuery.data) return
    const { run } = runQuery.data
    const confirmed = await confirmDialog({
      title: 'Confirm this plan?',
      text: `Every trip in ${run.planNumber} will be revalidated and its capacity frozen. This cannot be undone.`,
      confirmLabel: 'Confirm plan',
    })
    if (!confirmed) return

    try {
      await confirmPlanningRun(companyId, run.id, { version: run.version })
      notifySuccess('Plan confirmed', run.planNumber)
      refreshBoard()
    } catch (error) {
      notifyError('Could not confirm the plan', describePlanningError(error as ApiError))
    }
  }

  async function cancelPlan() {
    if (!runQuery.data) return
    const { run } = runQuery.data
    const confirmed = await confirmDialog({
      title: 'Cancel this plan?',
      text: `${run.planNumber} will be discarded, every trip cancelled and every assigned order returned to the eligible pool.`,
      confirmLabel: 'Cancel plan',
      dangerous: true,
    })
    if (!confirmed) return

    try {
      await cancelPlanningRun(companyId, run.id, { version: run.version })
      notifySuccess('Plan cancelled', run.planNumber)
      refreshBoard()
    } catch (error) {
      notifyError('Could not cancel the plan', describePlanningError(error as ApiError))
    }
  }

  if (runQuery.isPending) {
    return <LoadingState label="Loading planning run..." />
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

  return (
    <div>
      <Link to="/planning" className="text-decoration-none small d-inline-block mb-1">
        ← Planning runs
      </Link>
      <PageHeader
        title={run.planNumber}
        description={`${run.originName ?? run.originCode} · ${run.planningDate}`}
        actions={
          <div className="d-flex align-items-center gap-2">
            <StatusBadge label={enumLabels.planningRunStatus(run.status)} tone={STATUS_TONE[run.status]} />
            {isDraft && canManageTrips && (
              <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => setShowCreateTrip(true)}>
                New trip
              </button>
            )}
            {isDraft && canManageRun && (
              <>
                <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => void cancelPlan()}>
                  Cancel plan
                </button>
                <button type="button" className="btn btn-sm btn-primary" onClick={() => void confirmPlan()}>
                  Confirm plan
                </button>
              </>
            )}
          </div>
        }
      />

      <div className="row g-3">
        <div className="col-lg-4">
          <EligibleOrdersPanel
            companyId={companyId}
            run={run}
            trips={trips}
            canManage={isDraft && canManageTrips}
            onAssigned={refreshBoard}
          />
        </div>
        <div className="col-lg-8">
          {trips.length === 0 ? (
            <div className="card shadow-sm">
              <div className="card-body text-center py-5 text-body-secondary">
                <p className="mb-1 fw-semibold text-body">No trips yet</p>
                <p className="small mb-0">Create a trip to start assigning orders.</p>
              </div>
            </div>
          ) : (
            <div className="row row-cols-1 row-cols-xl-2 g-3">
              {trips.map((trip) => (
                <div key={trip.id} className="col">
                  <TripCard trip={trip} onOpen={() => setOpenTripId(trip.id)} />
                </div>
              ))}
            </div>
          )}
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
        <CreateTripModal
          companyId={companyId}
          runId={run.id}
          runVersion={run.version}
          onClose={() => setShowCreateTrip(false)}
          onCreated={() => {
            setShowCreateTrip(false)
            notifySuccess('Trip created')
            refreshBoard()
          }}
        />
      )}
    </div>
  )
}
