import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import {
  closeTripCost,
  estimateTripCost,
  fetchTripCost,
  recordActualTripCost,
  reopenTripCost,
  type TripCostComponentView,
  type TripCostView,
} from '../../shared/api/ratesApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { confirmDialog, ErrorState } from '../../shared/ui/components'
import { ActualCostDrawer } from './ActualCostDrawer'

export interface TripCostCardProps {
  companyId: string
  tripId: string
  /** `rates.trip_cost:manage` - hiding is UX only; the backend re-checks every call. */
  canManage: boolean
}

/**
 * What this shipment was estimated at, what it actually cost, and the gap between them
 * (`docs/domain/RATES_COSTING_V1.md`).
 *
 * The two figures are always shown side by side and neither is ever presented as the other. An
 * estimate that could not be calculated in full says so above the total rather than below it: a
 * number that is short by the entire line haul must not be read as a price and then explained
 * afterwards.
 *
 * Its own query, like the tracking card's: a costing failure costs this card and leaves the rest
 * of the workspace working.
 */
export function TripCostCard({ companyId, tripId, canManage }: TripCostCardProps) {
  const { t } = useTranslation('rates')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const enumLabels = useEnumLabels()
  const format = useFormat()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [showActualForm, setShowActualForm] = useState(false)

  const costQuery = useQuery({
    queryKey: ['trip-cost', companyId, tripId],
    queryFn: ({ signal }) => fetchTripCost(companyId, tripId, signal),
  })

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['trip-cost', companyId, tripId] })
  }

  async function run(action: () => Promise<TripCostView>, successTitle: string) {
    setBusy(true)
    try {
      await action()
      notifySuccess(successTitle)
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    } finally {
      setBusy(false)
    }
  }

  async function close() {
    const confirmed = await confirmDialog({
      title: t('tripCost.confirm.closeTitle'),
      text: t('tripCost.confirm.closeText'),
      confirmLabel: t('tripCost.actions.close'),
    })
    if (!confirmed) return
    await run(() => closeTripCost(companyId, tripId), t('tripCost.notify.closed'))
  }

  async function reopen() {
    const confirmed = await confirmDialog({
      title: t('tripCost.confirm.reopenTitle'),
      text: t('tripCost.confirm.reopenText'),
      confirmLabel: t('tripCost.actions.reopen'),
      dangerous: true,
    })
    if (!confirmed) return
    await run(() => reopenTripCost(companyId, tripId), t('tripCost.notify.reopened'))
  }

  if (costQuery.isPending) {
    return (
      <p className="text-secondary small mb-0" role="status">
        {tc('states.loading')}
      </p>
    )
  }
  /* A failure is reported as one, not as another grey caption. Every other line this card can
     render - "todavía no tiene costo estimado", a closed date - is a fact about the shipment, and
     styling a broken read the same way invites it to be read as one more of them. The retry is
     here because the alternative is reloading the whole workspace to re-run one query. */
  if (costQuery.isError) {
    return (
      <ErrorState
        message={describeApiError(costQuery.error as ApiError)}
        onRetry={() => void costQuery.refetch()}
      />
    )
  }

  const cost = costQuery.data
  const currency = cost.currency ?? ''

  /**
   * Money is always shown with both decimals, which `format.decimal` deliberately does not do -
   * it drops trailing zeros, and "PEN 180" beside "PEN 154,43" reads as a rounder number than it
   * is. Rates keep up to four, because that is what a tariff is agreed in.
   */
  const money = (value: number) =>
    format.number(value, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

  /** "40,5 KM × 0,85 · Distancia de referencia de la ruta" - or the reason the line has no figure. */
  function describeLine(line: TripCostComponentView): string {
    if (line.status === 'NOT_CALCULABLE') {
      return line.reason ? enumLabels.costComponentReason(line.reason) : ''
    }
    if (line.quantity === null || line.rate === null) {
      return ''
    }
    const rate = format.number(line.rate, { minimumFractionDigits: 2, maximumFractionDigits: 4 })
    const product = `${format.decimal(line.quantity, 2)} ${line.unit ?? ''} × ${rate}`
    // The provenance is part of the line, not a footnote: the first thing anyone disputing an
    // estimate asks is where the 40,5 km came from.
    return line.quantitySource ? `${product} · ${enumLabels.costQuantitySource(line.quantitySource)}` : product
  }

  return (
    <div>
      {!cost.priced ? (
        <p className="text-secondary small mb-2">{t('tripCost.notPriced')}</p>
      ) : (
        <>
          {!cost.estimateComplete && (
            <div className="alert alert-warning py-2 small" role="status">
              {t('tripCost.incomplete')}
            </div>
          )}

          <div className="row g-2 small mb-2">
            <div className="col-4">
              <span className="d-block text-secondary">{t('tripCost.estimated')}</span>
              <span className="fw-semibold">
                {cost.estimatedAmount === null ? '—' : `${currency} ${money(cost.estimatedAmount)}`}
              </span>
            </div>
            <div className="col-4">
              <span className="d-block text-secondary">{t('tripCost.actual')}</span>
              <span className="fw-semibold">
                {cost.actualAmount === null ? '—' : `${currency} ${money(cost.actualAmount)}`}
              </span>
            </div>
            <div className="col-4">
              <span className="d-block text-secondary">{t('tripCost.variance')}</span>
              <span className={`fw-semibold${cost.variance !== null && cost.variance > 0 ? ' text-danger' : ''}`}>
                {cost.variance === null ? '—' : `${currency} ${money(cost.variance)}`}
              </span>
            </div>
          </div>

          {cost.rateCardCode && (
            <p className="text-secondary small mb-2">
              {t('tripCost.rateCard', { code: cost.rateCardCode, name: cost.rateCardName ?? '' })}
              {cost.estimatedAt && <span className="d-block">{format.dateTime(cost.estimatedAt)}</span>}
            </p>
          )}

          {cost.components.length > 0 && (
            <table className="table table-sm small mb-2">
              <caption className="visually-hidden">{t('tripCost.breakdown.title')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('tripCost.breakdown.component')}</th>
                  <th scope="col">{t('tripCost.breakdown.detail')}</th>
                  <th scope="col" className="text-end">
                    {t('tripCost.breakdown.amount')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {cost.components.map((line) => (
                  <tr key={line.component} className={line.status === 'NOT_CALCULABLE' ? 'text-secondary' : undefined}>
                    <td>{enumLabels.rateComponent(line.component)}</td>
                    <td>{describeLine(line)}</td>
                    <td className="text-end">
                      {line.status === 'NOT_CALCULABLE' ? t('tripCost.breakdown.notCalculable') : money(line.amount)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {cost.actualReference && (
            <p className="text-secondary small mb-2">
              {tc('fields.reference')}: <span className="tms-code">{cost.actualReference}</span>
            </p>
          )}

          {cost.closed && (
            <p className="text-secondary small mb-2">
              {t('tripCost.closedOn', { date: cost.closedAt ? format.dateTime(cost.closedAt) : '' })}
            </p>
          )}
        </>
      )}

      {canManage && (
        <div className="d-flex flex-wrap gap-2">
          {!cost.closed && (
            <button
              type="button"
              className="btn btn-sm btn-outline-secondary"
              disabled={busy}
              onClick={() => void run(() => estimateTripCost(companyId, tripId), t('tripCost.notify.estimated'))}
            >
              {cost.priced ? t('tripCost.actions.reEstimate') : t('tripCost.actions.estimate')}
            </button>
          )}
          {!cost.closed && (
            <button
              type="button"
              className="btn btn-sm btn-outline-secondary"
              disabled={busy}
              onClick={() => setShowActualForm(true)}
            >
              {t('tripCost.actions.recordActual')}
            </button>
          )}
          {!cost.closed && cost.actualAmount !== null && (
            <button
              type="button"
              className="btn btn-sm btn-outline-secondary"
              disabled={busy}
              onClick={() => void close()}
            >
              {t('tripCost.actions.close')}
            </button>
          )}
          {cost.closed && (
            <button
              type="button"
              className="btn btn-sm btn-outline-secondary"
              disabled={busy}
              onClick={() => void reopen()}
            >
              {t('tripCost.actions.reopen')}
            </button>
          )}
        </div>
      )}

      {showActualForm && (
        <ActualCostDrawer
          currency={cost.currency}
          amount={cost.actualAmount}
          reference={cost.actualReference}
          notes={cost.actualNotes}
          onClose={() => setShowActualForm(false)}
          onSubmit={async (request) => {
            await recordActualTripCost(companyId, tripId, request)
            setShowActualForm(false)
            notifySuccess(td('updated'))
            refresh()
          }}
        />
      )}
    </div>
  )
}
