import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import {
  acceptTender,
  createTender,
  fetchTripTenders,
  rejectTender,
  sendTender,
  updateTenderTerms,
  withdrawTender,
  type TenderRequest,
  type TripTenderView,
} from '../../shared/api/tendersApi'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { confirmDialog, ErrorState, promptDialog, StatusBadge } from '../../shared/ui/components'
import { TenderDrawer } from './TenderDrawer'
import { TENDER_STATUS_TONE } from '../../shared/ui/statusTones'

export interface TripTenderCardProps {
  companyId: string
  tripId: string
  /** The shipment's carrier, resolved by the trip - the only party an offer can go to. */
  carrierName: string | null
  /** True while the shipment is CONFIRMED or READY_FOR_DISPATCH; the server refuses anything else. */
  offerable: boolean
  /** `planning.tender:manage` - hiding is UX only; the backend re-checks every call. */
  canManage: boolean
}

/**
 * Whether this shipment has been offered to its carrier, what they said, and every attempt before
 * this one (`docs/domain/CARRIER_TENDERING_V1.md`).
 *
 * **The whole history, newest first, not just the live attempt.** "We offered this to ACME twice and
 * they said no twice" is what somebody opening this card is looking for, and showing only the
 * current attempt would hide exactly the thing that explains why the shipment is still unplaced.
 *
 * **The buttons come from the server.** Each attempt carries `allowedTransitions`, already resolved
 * against its deadline, so the card never re-derives the lifecycle and never offers an action on an
 * offer that has quietly lapsed. That is the same contract `TripView.allowedTransitions` has with
 * the workspace's own buttons.
 *
 * Its own query, like the cost and tracking cards: a tendering failure costs this card and leaves
 * the rest of the workspace working.
 */
export function TripTenderCard({ companyId, tripId, carrierName, offerable, canManage }: TripTenderCardProps) {
  const { t } = useTranslation('trips')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const enumLabels = useEnumLabels()
  const format = useFormat()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState<TripTenderView | null>(null)
  const [creating, setCreating] = useState(false)

  const tendersQuery = useQuery({
    queryKey: ['trip-tenders', companyId, tripId],
    queryFn: ({ signal }) => fetchTripTenders(companyId, tripId, signal),
  })

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['trip-tenders', companyId, tripId] })
    // The trip's own timeline gains an entry for every tender transition, so it has to be reloaded
    // beside this card or the two would disagree until the next navigation.
    void queryClient.invalidateQueries({ queryKey: ['trip-events', companyId, tripId] })
  }

  async function run(action: () => Promise<unknown>, successTitle: string) {
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

  async function send(tender: TripTenderView) {
    const confirmed = await confirmDialog({
      title: t('tender.confirm.sendTitle'),
      text: t('tender.confirm.sendText', { carrier: tender.carrierName ?? '' }),
      confirmLabel: t('tender.actions.send'),
    })
    if (!confirmed) return
    await run(() => sendTender(companyId, tripId, tender.id), t('tender.notify.sent'))
  }

  async function accept(tender: TripTenderView) {
    const confirmed = await confirmDialog({
      title: t('tender.confirm.acceptTitle'),
      text: t('tender.confirm.acceptText', { carrier: tender.carrierName ?? '' }),
      confirmLabel: t('tender.actions.accept'),
    })
    if (!confirmed) return
    await run(() => acceptTender(companyId, tripId, tender.id, { notes: null }), t('tender.notify.accepted'))
  }

  /** The reason is mandatory server-side, so the dialog refuses an empty one before the round trip. */
  async function reject(tender: TripTenderView) {
    const reason = await promptDialog({
      title: t('tender.confirm.rejectTitle'),
      text: t('tender.confirm.rejectText'),
      inputLabel: t('tender.fields.reason'),
      required: true,
      requiredMessage: t('tender.confirm.reasonRequired'),
      maxLength: 1000,
      confirmLabel: t('tender.actions.reject'),
      dangerous: true,
    })
    if (reason === null) return
    await run(() => rejectTender(companyId, tripId, tender.id, { notes: reason }), t('tender.notify.rejected'))
  }

  async function withdraw(tender: TripTenderView) {
    const reason = await promptDialog({
      title: t('tender.confirm.withdrawTitle'),
      text: t('tender.confirm.withdrawText'),
      inputLabel: t('tender.fields.reason'),
      required: true,
      requiredMessage: t('tender.confirm.reasonRequired'),
      maxLength: 500,
      confirmLabel: t('tender.actions.withdraw'),
      dangerous: true,
    })
    if (reason === null) return
    await run(() => withdrawTender(companyId, tripId, tender.id, { reason }), t('tender.notify.withdrawn'))
  }

  async function saveTerms(request: TenderRequest) {
    if (editing) {
      await updateTenderTerms(companyId, tripId, editing.id, request)
    } else {
      await createTender(companyId, tripId, request)
    }
    setEditing(null)
    setCreating(false)
    notifySuccess(td('saved'))
    refresh()
  }

  if (tendersQuery.isPending) {
    return (
      <p className="text-secondary small mb-0" role="status">
        {tc('states.loading')}
      </p>
    )
  }
  /* Reported as a failure rather than as a grey line, for the reason the cost card gives: this
     card's other short sentences ("Sin ofertas", "No ofertable") are facts about the shipment, and
     a broken read must not join them. Offering a tender is a commercial act, so "no offers" and
     "we could not tell you" are especially not interchangeable here. */
  if (tendersQuery.isError) {
    return (
      <ErrorState
        message={describeApiError(tendersQuery.error as ApiError)}
        onRetry={() => void tendersQuery.refetch()}
      />
    )
  }

  const tenders = tendersQuery.data
  const live = tenders.find((tender) => tender.status === 'DRAFT' || tender.status === 'SENT') ?? null
  const accepted = tenders.find((tender) => tender.status === 'ACCEPTED') ?? null
  // A new attempt is possible only when nothing is live and nothing has been accepted - the two
  // rules the server enforces, mirrored here so the button is absent rather than a guaranteed 409.
  const canOffer = canManage && offerable && live === null && accepted === null

  const money = (value: number) =>
    format.number(value, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

  function amountOf(tender: TripTenderView): string | null {
    return tender.offeredAmount === null ? null : `${tender.currency ?? ''} ${money(tender.offeredAmount)}`
  }

  return (
    <div>
      {tenders.length === 0 ? (
        <p className="text-secondary small mb-2">
          {offerable ? t('tender.none') : t('tender.notOfferable')}
        </p>
      ) : (
        <ul className="list-unstyled mb-2">
          {tenders.map((tender) => (
            <li key={tender.id} className="border-bottom pb-2 mb-2">
              <div className="d-flex flex-wrap align-items-center gap-2">
                <StatusBadge tone={TENDER_STATUS_TONE[tender.status]} label={enumLabels.tenderStatus(tender.status)} />
                <span className="fw-semibold">{tender.carrierName ?? '—'}</span>
                <span className="text-secondary small">{t('tender.attempt', { number: tender.attempt })}</span>
                {amountOf(tender) && <span className="ms-auto fw-semibold">{amountOf(tender)}</span>}
              </div>

              <div className="text-secondary small mt-1">
                {tender.sentAt && <div>{t('tender.sentAt', { at: format.dateTime(tender.sentAt) })}</div>}
                {/* Shown while the offer is live, because the countdown is the actionable part; and
                    after it lapsed, because "it expired at 12:00" is the explanation. */}
                {tender.expiresAt && (tender.status === 'SENT' || tender.status === 'EXPIRED') && (
                  <div>{t('tender.expiresAt', { at: format.dateTime(tender.expiresAt) })}</div>
                )}
                {tender.respondedAt && (
                  <div>
                    {/* Two calls rather than one with a computed key: `t` is key-checked against
                        the Spanish bundle (`i18next.d.ts`), and a literal on each branch is what
                        keeps that check meaningful. */}
                    {tender.status === 'ACCEPTED'
                      ? t('tender.acceptedAt', { at: format.dateTime(tender.respondedAt) })
                      : t('tender.rejectedAt', { at: format.dateTime(tender.respondedAt) })}
                    {tender.responseSource && (
                      <span> · {enumLabels.tenderResponseSource(tender.responseSource)}</span>
                    )}
                  </div>
                )}
                {tender.responseNotes && <div className="fst-italic">{tender.responseNotes}</div>}
                {tender.cancelReason && <div className="fst-italic">{tender.cancelReason}</div>}
                {tender.notes && tender.status !== 'CANCELLED' && <div>{tender.notes}</div>}
              </div>

              {canManage && tender.allowedTransitions.length > 0 && (
                <div className="d-flex flex-wrap gap-2 mt-2">
                  {tender.status === 'DRAFT' && (
                    <>
                      <button
                        type="button"
                        className="btn btn-sm btn-outline-secondary"
                        disabled={busy}
                        onClick={() => setEditing(tender)}
                      >
                        {tc('actions.edit')}
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm btn-primary"
                        disabled={busy}
                        onClick={() => void send(tender)}
                      >
                        {t('tender.actions.send')}
                      </button>
                    </>
                  )}
                  {tender.allowedTransitions.includes('ACCEPTED') && (
                    <button
                      type="button"
                      className="btn btn-sm btn-outline-secondary"
                      disabled={busy}
                      onClick={() => void accept(tender)}
                    >
                      {t('tender.actions.accept')}
                    </button>
                  )}
                  {tender.allowedTransitions.includes('REJECTED') && (
                    <button
                      type="button"
                      className="btn btn-sm btn-outline-secondary"
                      disabled={busy}
                      onClick={() => void reject(tender)}
                    >
                      {t('tender.actions.reject')}
                    </button>
                  )}
                  {tender.allowedTransitions.includes('CANCELLED') && (
                    <button
                      type="button"
                      className="btn btn-sm btn-outline-secondary"
                      disabled={busy}
                      onClick={() => void withdraw(tender)}
                    >
                      {t('tender.actions.withdraw')}
                    </button>
                  )}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      {canOffer && (
        <button
          type="button"
          className="btn btn-sm btn-outline-secondary"
          disabled={busy}
          onClick={() => setCreating(true)}
        >
          {tenders.length === 0 ? t('tender.actions.offer') : t('tender.actions.offerAgain')}
        </button>
      )}

      {(creating || editing) && (
        <TenderDrawer
          carrierName={editing?.carrierName ?? carrierName}
          tender={editing}
          onClose={() => {
            setEditing(null)
            setCreating(false)
          }}
          onSubmit={saveTerms}
        />
      )}
    </div>
  )
}
