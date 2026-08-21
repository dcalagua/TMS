import { useMutation, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import {
  applyAutoPlan,
  previewAutoPlan,
  type AutoPlanView,
  type UnplannedOrderView,
} from '../../shared/api/planningApi'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import { TmsDrawer } from '../../shared/ui/components'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'

interface AutoPlanDrawerProps {
  companyId: string
  runId: string
  /** The run's version, sent with the write so a stale board cannot plan a confirmed run. */
  runVersion: number
  canApply: boolean
  onClose: () => void
  onApplied: () => void
}

/**
 * The automatic planning review step.
 *
 * Preview first, always. The engine is deterministic and the preview calls the same code the
 * write does, so what this drawer shows is what applying produces - and a planner who is about to
 * have nine trips created for them should see the nine trips first. There is no "just do it"
 * path, and that is the point: automatic planning proposes, a person decides.
 *
 * The unplanned list is given the same weight as the proposal. "7 trips created" next to a
 * quietly discarded backlog is how a planner learns at 6pm that forty orders never went out.
 */
export function AutoPlanDrawer({
  companyId, runId, runVersion, canApply, onClose, onApplied,
}: AutoPlanDrawerProps) {
  const { t } = useTranslation('planning')
  const { t: tc } = useTranslation('common')

  const preview = useQuery({
    queryKey: ['auto-plan-preview', companyId, runId],
    queryFn: ({ signal }) => previewAutoPlan(companyId, runId, signal),
    // A proposal is a photograph of the backlog: refetching it while the planner reads it would
    // change the thing they are deciding about.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  })

  const apply = useMutation({
    mutationFn: () => applyAutoPlan(companyId, runId, { version: runVersion }),
    onSuccess: (result) => {
      notifySuccess(t('autoPlan.appliedTitle'), t('autoPlan.appliedText', { count: result.created.length }))
      onApplied()
      onClose()
    },
    onError: (error) => notifyError(t('autoPlan.failedTitle'), describeApiError(error as ApiError)),
  })

  const plan = preview.data

  return (
    <TmsDrawer
      open
      loading={preview.isPending}
      title={t('autoPlan.title')}
      subtitle={t('autoPlan.subtitle')}
      size="lg"
      onClose={onClose}
      footer={
        <div className="d-flex justify-content-end gap-2">
          <button type="button" className="btn btn-outline-secondary" onClick={onClose}>
            {tc('actions.cancel')}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={!canApply || !plan || plan.proposed.length === 0 || apply.isPending}
            onClick={() => apply.mutate()}
          >
            {apply.isPending ? t('autoPlan.applying') : t('autoPlan.apply')}
          </button>
        </div>
      }
    >
      {preview.isError && (
        <div className="alert alert-danger py-2 small" role="alert">
          {describeApiError(preview.error as ApiError)}
        </div>
      )}

      {plan && <AutoPlanBody plan={plan} />}
    </TmsDrawer>
  )
}

/**
 * Each reason phrased as what the planner can do about it, not as what the engine concluded.
 * A literal map rather than a switch: the i18n keys are typed, so a renamed key is a compile
 * error here instead of a raw enum name rendered on the screen.
 */
const REASON_KEY = {
  EXCEEDS_LARGEST_VEHICLE: 'autoPlan.reasons.exceedsLargestVehicle',
  NO_VEHICLE_AVAILABLE: 'autoPlan.reasons.noVehicleAvailable',
  NO_FLEET: 'autoPlan.reasons.noFleet',
  TAKEN_WHILE_PLANNING: 'autoPlan.reasons.takenWhilePlanning',
  NOT_SERVICEABLE_ON_DATE: 'autoPlan.reasons.notServiceableOnDate',
} as const satisfies Record<UnplannedOrderView['reason'], string>

function AutoPlanBody({ plan }: { plan: AutoPlanView }) {
  const { t } = useTranslation('planning')
  const { t: tc } = useTranslation('common')

  const plannedOrders = plan.proposed.reduce((total, trip) => total + trip.orderNumbers.length, 0)

  return (
    <>
      <fieldset className="tms-fieldset">
        <legend className="tms-fieldset-legend">{t('autoPlan.summary')}</legend>
        <dl className="row mb-0 small">
          <dt className="col-7 fw-normal text-body-secondary">{t('autoPlan.ordersConsidered')}</dt>
          <dd className="col-5 mb-1 text-end">{plan.ordersConsidered}</dd>
          <dt className="col-7 fw-normal text-body-secondary">{t('autoPlan.vehiclesOffered')}</dt>
          <dd className="col-5 mb-1 text-end">{plan.vehiclesOffered}</dd>
          <dt className="col-7 fw-normal text-body-secondary">{t('autoPlan.tripsProposed')}</dt>
          <dd className="col-5 mb-1 text-end fw-semibold">{plan.proposed.length}</dd>
          <dt className="col-7 fw-normal text-body-secondary">{t('autoPlan.ordersPlanned')}</dt>
          <dd className="col-5 mb-0 text-end">{plannedOrders}</dd>
        </dl>
        <p className="text-body-secondary small mt-2 mb-0">{t('autoPlan.engineNote', { engine: plan.engine })}</p>
      </fieldset>

      <fieldset className="tms-fieldset">
        <legend className="tms-fieldset-legend">{t('autoPlan.proposedTrips')}</legend>
        {plan.proposed.length === 0 ? (
          <p className="text-body-secondary small mb-0">{t('autoPlan.nothingToPlan')}</p>
        ) : (
          <div className="tms-table-scroll">
            <table className="table table-sm align-middle mb-0">
              <caption className="visually-hidden">{t('autoPlan.proposedTrips')}</caption>
              <thead>
                <tr>
                  <th scope="col">#</th>
                  <th scope="col">{t('autoPlan.vehicle')}</th>
                  <th scope="col">{t('autoPlan.orders')}</th>
                  <th scope="col" className="text-end">{t('autoPlan.stops')}</th>
                </tr>
              </thead>
              <tbody>
                {plan.proposed.map((trip, index) => (
                  <tr key={`${trip.vehicleId}-${index}`}>
                    <td className="text-body-secondary">{index + 1}</td>
                    <td className="tms-code">{trip.vehicleCode ?? '—'}</td>
                    <td>
                      <span className="tms-cell-stack">
                        <span className="tms-cell-primary">
                          {t('autoPlan.orderCount', { count: trip.orderNumbers.length })}
                        </span>
                        <span className="tms-cell-sub">{trip.orderNumbers.join(', ')}</span>
                      </span>
                    </td>
                    <td className="text-end">{trip.stopCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </fieldset>

      <fieldset className="tms-fieldset">
        <legend className="tms-fieldset-legend">{t('autoPlan.unplanned')}</legend>
        {plan.unplanned.length === 0 ? (
          <p className="text-body-secondary small mb-0">{t('autoPlan.everythingPlanned')}</p>
        ) : (
          <>
            {/* Not an error panel. These orders stay in the pool and the planner decides what to
                do with them - which is only possible if they are told, per order, what happened. */}
            <p className="text-body-secondary small">{t('autoPlan.unplannedHelp')}</p>
            <div className="tms-table-scroll">
              <table className="table table-sm align-middle mb-0">
                <caption className="visually-hidden">{t('autoPlan.unplanned')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{tc('columns.code')}</th>
                    <th scope="col">{t('autoPlan.reason')}</th>
                  </tr>
                </thead>
                <tbody>
                  {plan.unplanned.map((order) => (
                    <tr key={order.orderId}>
                      <td className="tms-code">{order.orderNumber ?? '—'}</td>
                      <td className="small">{t(REASON_KEY[order.reason])}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </fieldset>
    </>
  )
}
