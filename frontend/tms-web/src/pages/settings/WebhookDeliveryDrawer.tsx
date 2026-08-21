import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { fetchWebhookDelivery } from '../../shared/api/integrationsApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { useFormat } from '../../shared/i18n/format'
import { ErrorState, StatusBadge, type StatusTone } from '../../shared/ui/components'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'

const OUTCOME_TONE: Record<string, StatusTone> = {
  DELIVERED: 'success',
  RETRYABLE_FAILURE: 'warning',
  PERMANENT_FAILURE: 'danger',
}

/**
 * One delivery, in full: every attempt that was made and the exact bytes that were sent.
 *
 * This is the screen a "you never sent us that shipment" conversation is settled from, so it shows
 * the things that settle it - when each call went out, how long it took, what came back, and the
 * payload itself, which is stored precisely so that a retry three hours later is byte-identical to
 * the first try.
 *
 * An attempt with no status code is not a zero: it is a call that never reached a server at all - a
 * timeout, a refused connection, a name that would not resolve - and that difference is the first
 * thing an integrator needs.
 */
export function WebhookDeliveryDrawer({
  companyId,
  deliveryId,
  onClose,
}: {
  companyId: string
  deliveryId: string
  onClose: () => void
}) {
  const { t } = useTranslation('settings')
  const { t: tc } = useTranslation('common')
  const format = useFormat()

  const query = useQuery({
    queryKey: ['webhook-delivery', companyId, deliveryId],
    queryFn: ({ signal }) => fetchWebhookDelivery(companyId, deliveryId, signal),
    enabled: companyId !== '',
  })

  const detail = query.data

  return (
    <TmsDrawer
      open
      title={t('integrations.deliveries.detail.title')}
      subtitle={detail ? `${detail.delivery.eventType} → ${detail.delivery.subscriptionName}` : undefined}
      size="lg"
      onClose={onClose}
      loading={query.isPending}
      footer={
        <button type="button" className="btn btn-outline-secondary" onClick={onClose}>
          {tc('actions.close')}
        </button>
      }
    >
      {query.isError && (
        <ErrorState message={describeApiError(query.error as ApiError)} onRetry={() => void query.refetch()} />
      )}

      {detail && (
        <>
          <dl className="row small mb-3">
            <dt className="col-4">{t('integrations.deliveries.detail.eventId')}</dt>
            <dd className="col-8">
              <code>{detail.delivery.eventId}</code>
              <span className="d-block text-body-secondary">{t('integrations.deliveries.detail.eventIdHelp')}</span>
            </dd>
            <dt className="col-4">{t('integrations.deliveries.detail.occurredAt')}</dt>
            <dd className="col-8">{format.dateTime(detail.delivery.occurredAt)}</dd>
            <dt className="col-4">{tc('columns.status')}</dt>
            <dd className="col-8">{t(`integrations.deliveries.statuses.${detail.delivery.status}`)}</dd>
            {detail.delivery.completedAt && (
              <>
                <dt className="col-4">{t('integrations.deliveries.detail.completedAt')}</dt>
                <dd className="col-8">{format.dateTime(detail.delivery.completedAt)}</dd>
              </>
            )}
          </dl>

          <h3 className="tms-section-title">{t('integrations.deliveries.detail.attempts')}</h3>
          {detail.attempts.length === 0 ? (
            <p className="text-body-secondary small">{t('integrations.deliveries.detail.noAttempts')}</p>
          ) : (
            <ul className="list-unstyled d-flex flex-column gap-2 mb-3">
              {detail.attempts.map((attempt) => (
                <li key={attempt.id} className="border rounded p-2">
                  <div className="d-flex flex-wrap align-items-center gap-2">
                    <span className="fw-semibold">#{attempt.attemptNumber}</span>
                    <StatusBadge
                      label={t(`integrations.deliveries.outcomes.${attempt.outcome}`)}
                      tone={OUTCOME_TONE[attempt.outcome] ?? 'neutral'}
                    />
                    <span className="small text-body-secondary">{format.dateTime(attempt.attemptedAt)}</span>
                    <span className="small text-body-secondary">
                      {t('integrations.deliveries.detail.duration', { ms: attempt.durationMs })}
                    </span>
                  </div>
                  <div className="small">
                    {attempt.statusCode !== null ? (
                      <span>HTTP {attempt.statusCode}</span>
                    ) : (
                      <span className="text-body-secondary">{t('integrations.deliveries.detail.noResponse')}</span>
                    )}
                    {attempt.error && <span className="d-block text-danger text-break">{attempt.error}</span>}
                  </div>
                </li>
              ))}
            </ul>
          )}

          <h3 className="tms-section-title">{t('integrations.deliveries.detail.payload')}</h3>
          <p className="text-body-secondary small">{t('integrations.deliveries.detail.payloadHelp')}</p>
          <pre className="border rounded p-2 small mb-0 text-break" style={{ whiteSpace: 'pre-wrap' }}>
            {detail.payload}
          </pre>
        </>
      )}
    </TmsDrawer>
  )
}
