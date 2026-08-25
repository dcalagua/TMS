import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import {
  fetchWebhookDeliveries,
  fetchWebhookEventTypes,
  fetchWebhookSubscriptions,
  retryWebhookDelivery,
  rotateWebhookSecret,
  setWebhookSubscriptionActive,
  type WebhookDeliveryStatus,
  type WebhookDeliveryView,
  type WebhookSubscriptionSecretView,
  type WebhookSubscriptionView,
} from '../../shared/api/integrationsApi'
import { describeApiError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
import { useFormat } from '../../shared/i18n/format'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import {
  ActionMenu,
  DataTable,
  Pagination,
  SectionHeader,
  Select,
  StatusBadge,
  confirmDialog,
  type ActionMenuItem,
  type DataTableColumn,
  type StatusTone,
} from '../../shared/ui/components'
import { SecretRevealDrawer } from './SecretRevealDrawer'
import { WebhookDeliveryDrawer } from './WebhookDeliveryDrawer'
import { WebhookSubscriptionDrawer } from './WebhookSubscriptionDrawer'

const PAGE_SIZE = 10

type SubscriptionModal =
  | { mode: 'create' }
  | { mode: 'edit'; subscription: WebhookSubscriptionView }
  | null

const DELIVERY_TONE: Record<WebhookDeliveryStatus, StatusTone> = {
  PENDING: 'warning',
  PROCESSED: 'success',
  FAILED: 'danger',
}

/**
 * Outbound: where this company's events are pushed, and whether they arrived.
 *
 * The delivery log sits under the endpoint list for the same reason the inbound inbox sits under the
 * credential list - it is how the thing above it is debugged. "You never told us that shipment was
 * confirmed" is answered from the delivery, and specifically from its attempts, which is why a row
 * opens a drawer showing every call that was made and the exact bytes that were sent.
 *
 * A signing secret is never shown here. Only its last four characters, which is enough for "the one
 * ending 7fQ2" and not enough to sign anything.
 */
export function OutboundPanel({ companyId }: { companyId: string }) {
  const { t } = useTranslation('settings')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const { t: ts } = useTranslation('statuses')
  const format = useFormat()
  const { hasPermission } = useCompany()
  const canManage = hasPermission('integration.webhook:manage')
  const queryClient = useQueryClient()

  const [subscriptionPage, setSubscriptionPage] = useState(0)
  const [deliveryPage, setDeliveryPage] = useState(0)
  const [subscriptionFilter, setSubscriptionFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState<'' | WebhookDeliveryStatus>('')
  const [modal, setModal] = useState<SubscriptionModal>(null)
  const [issued, setIssued] = useState<WebhookSubscriptionSecretView | null>(null)
  const [openDelivery, setOpenDelivery] = useState<string | null>(null)

  const subscriptionsQuery = useQuery({
    queryKey: ['webhook-subscriptions', companyId, subscriptionPage],
    queryFn: ({ signal }) =>
      fetchWebhookSubscriptions(companyId, { page: subscriptionPage, size: PAGE_SIZE, sort: 'name,asc' }, signal),
    placeholderData: keepPreviousData,
    enabled: companyId !== '',
  })

  // The vocabulary changes only with a migration, so it is fetched once and kept - re-reading it
  // per drawer open would be a round trip for a constant.
  const eventTypesQuery = useQuery({
    queryKey: ['webhook-event-types', companyId],
    queryFn: ({ signal }) => fetchWebhookEventTypes(companyId, signal),
    enabled: companyId !== '',
    staleTime: Infinity,
  })

  const deliveriesQuery = useQuery({
    queryKey: ['webhook-deliveries', companyId, deliveryPage, subscriptionFilter, statusFilter],
    queryFn: ({ signal }) =>
      fetchWebhookDeliveries(
        companyId,
        {
          page: deliveryPage,
          size: PAGE_SIZE,
          sort: 'createdAt,desc',
          subscriptionId: subscriptionFilter || undefined,
          status: statusFilter || undefined,
        },
        signal,
      ),
    placeholderData: keepPreviousData,
    enabled: companyId !== '',
  })

  function refreshSubscriptions() {
    void queryClient.invalidateQueries({ queryKey: ['webhook-subscriptions', companyId] })
  }

  function refreshDeliveries() {
    void queryClient.invalidateQueries({ queryKey: ['webhook-deliveries', companyId] })
  }

  async function rotate(subscription: WebhookSubscriptionView) {
    const confirmed = await confirmDialog({
      title: t('integrations.webhooks.rotateTitle', { name: subscription.name }),
      text: t('integrations.webhooks.rotateText'),
      confirmLabel: t('integrations.webhooks.rotate'),
      dangerous: true,
    })
    if (!confirmed) return
    try {
      setIssued(await rotateWebhookSecret(companyId, subscription.id))
      refreshSubscriptions()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  async function toggleActive(subscription: WebhookSubscriptionView) {
    const activating = !subscription.active
    const confirmed = await confirmDialog({
      title: activating
        ? t('integrations.webhooks.resumeTitle', { name: subscription.name })
        : t('integrations.webhooks.pauseTitle', { name: subscription.name }),
      text: activating ? t('integrations.webhooks.resumeText') : t('integrations.webhooks.pauseText'),
      confirmLabel: activating ? t('integrations.webhooks.resume') : t('integrations.webhooks.pause'),
      dangerous: !activating,
    })
    if (!confirmed) return
    try {
      await setWebhookSubscriptionActive(companyId, subscription.id, activating)
      notifySuccess(activating ? t('integrations.webhooks.resumed') : t('integrations.webhooks.paused'))
      refreshSubscriptions()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  async function retry(delivery: WebhookDeliveryView) {
    try {
      await retryWebhookDelivery(companyId, delivery.id)
      notifySuccess(t('integrations.deliveries.requeued'))
      refreshDeliveries()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  const subscriptionColumns: DataTableColumn<WebhookSubscriptionView>[] = [
    {
      key: 'name',
      header: tc('columns.name'),
      render: (subscription) => (
        <span>
          <span className="fw-semibold d-block">{subscription.name}</span>
          <span className="small text-body-secondary text-break">{subscription.targetUrl}</span>
        </span>
      ),
    },
    {
      key: 'eventTypes',
      header: t('integrations.webhooks.events'),
      render: (subscription) => (
        <span className="d-flex flex-wrap gap-1">
          {subscription.eventTypes.map((eventType) => (
            <StatusBadge key={eventType} label={eventType} tone="info" />
          ))}
        </span>
      ),
    },
    {
      key: 'status',
      header: tc('columns.status'),
      render: (subscription) => {
        if (subscription.active) {
          return <StatusBadge label={ts('active')} tone="success" />
        }
        // A suspension is TMS switching the endpoint off, and it must not look like somebody
        // pausing it: the operator has to fix their side before reactivating.
        return subscription.suspendedReason ? (
          <StatusBadge label={t('integrations.webhooks.suspended')} tone="danger" />
        ) : (
          <StatusBadge label={t('integrations.webhooks.paused')} tone="neutral" />
        )
      },
    },
    {
      key: 'health',
      header: t('integrations.webhooks.health'),
      render: (subscription) => (
        <span className="small">
          {subscription.lastSuccessAt ? (
            <span className="d-block">
              {t('integrations.webhooks.lastSuccess', { when: format.dateTime(subscription.lastSuccessAt) })}
            </span>
          ) : (
            <span className="d-block text-body-secondary">{t('integrations.webhooks.neverDelivered')}</span>
          )}
          {subscription.consecutiveFailures > 0 && (
            <span className="d-block text-danger">
              {t('integrations.webhooks.failureStreak', { count: subscription.consecutiveFailures })}
            </span>
          )}
          <span className="d-block text-body-secondary">
            {t('integrations.webhooks.secretHint', { hint: subscription.secretHint })}
          </span>
        </span>
      ),
    },
  ]

  if (canManage) {
    subscriptionColumns.push({
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (subscription) => {
        const items: ActionMenuItem[] = [
          {
            key: 'edit',
            label: tc('actions.edit'),
            icon: 'bi-pencil',
            onSelect: () => setModal({ mode: 'edit', subscription }),
          },
          {
            key: 'rotate',
            label: t('integrations.webhooks.rotate'),
            icon: 'bi-arrow-repeat',
            onSelect: () => void rotate(subscription),
          },
          {
            key: 'active',
            label: subscription.active ? t('integrations.webhooks.pause') : t('integrations.webhooks.resume'),
            icon: subscription.active ? 'bi-pause-circle' : 'bi-play-circle',
            dangerous: subscription.active,
            onSelect: () => void toggleActive(subscription),
          },
        ]
        return <ActionMenu items={items} />
      },
    })
  }

  const deliveryColumns: DataTableColumn<WebhookDeliveryView>[] = [
    { key: 'createdAt', header: t('integrations.deliveries.queuedAt'), render: (row) => format.dateTime(row.createdAt) },
    {
      key: 'event',
      header: t('integrations.deliveries.event'),
      render: (row) => (
        <span>
          <span className="d-block">{row.eventType}</span>
          <span className="small text-body-secondary">{row.subscriptionName}</span>
        </span>
      ),
    },
    {
      key: 'status',
      header: tc('columns.status'),
      render: (row) => (
        <span className="d-flex flex-column gap-1">
          <StatusBadge label={t(`integrations.deliveries.statuses.${row.status}`)} tone={DELIVERY_TONE[row.status]} />
          <span className="small text-body-secondary">
            {t('integrations.deliveries.attempts', { count: row.attemptCount })}
          </span>
        </span>
      ),
    },
    {
      key: 'outcome',
      header: t('integrations.deliveries.outcome'),
      render: (row) => (
        <span className="small">
          {row.lastStatusCode !== null && <span className="d-block">HTTP {row.lastStatusCode}</span>}
          {row.lastError && <span className="d-block text-danger text-break">{row.lastError}</span>}
          {row.status === 'PENDING' && (
            <span className="d-block text-body-secondary">
              {t('integrations.deliveries.nextAttempt', { when: format.dateTime(row.nextAttemptAt) })}
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (row) => {
        const items: ActionMenuItem[] = [
          {
            key: 'inspect',
            label: t('integrations.deliveries.inspect'),
            icon: 'bi-search',
            onSelect: () => setOpenDelivery(row.id),
          },
        ]
        // Only a finished delivery can be re-queued; a pending one is already going to be retried
        // on a schedule designed not to hammer the receiver.
        if (canManage && row.status !== 'PENDING') {
          items.push({
            key: 'retry',
            label: t('integrations.deliveries.retry'),
            icon: 'bi-arrow-clockwise',
            onSelect: () => void retry(row),
          })
        }
        return <ActionMenu items={items} />
      },
    },
  ]

  const subscriptions = subscriptionsQuery.data
  const deliveries = deliveriesQuery.data

  return (
    <div className="d-flex flex-column gap-4">
      <section>
        <SectionHeader
          title={t('integrations.webhooks.title')}
          actions={
            canManage && (
              <button
                type="button"
                className="btn btn-primary btn-sm d-inline-flex align-items-center gap-2"
                onClick={() => setModal({ mode: 'create' })}
              >
                <i className="bi bi-broadcast" aria-hidden="true" />
                {t('integrations.webhooks.add')}
              </button>
            )
          }
        />
        <p className="text-body-secondary small">{t('integrations.webhooks.description')}</p>
        <DataTable
          columns={subscriptionColumns}
          rows={subscriptions?.content ?? []}
          total={subscriptions?.totalElements}
          rowKey={(subscription) => subscription.id}
          isLoading={subscriptionsQuery.isPending}
          error={subscriptionsQuery.isError ? describeApiError(subscriptionsQuery.error as ApiError) : null}
          onRetry={() => void subscriptionsQuery.refetch()}
          emptyTitle={t('integrations.webhooks.empty.title')}
          emptyMessage={t('integrations.webhooks.empty.message')}
          footer={subscriptions ? <Pagination page={subscriptions} onPageChange={setSubscriptionPage} /> : undefined}
        />
      </section>

      <section>
        <SectionHeader
          title={t('integrations.deliveries.title')}
          actions={
            <div className="d-flex gap-2">
              <Select
                id="webhook-delivery-subscription"
                size="sm"
                value={subscriptionFilter}
                onChange={(next) => {
                  setSubscriptionFilter(next)
                  setDeliveryPage(0)
                }}
                options={[
                  { value: '', label: t('integrations.deliveries.allEndpoints') },
                  ...(subscriptions?.content ?? []).map((subscription) => ({
                    value: subscription.id,
                    label: subscription.name,
                  })),
                ]}
              />
              <Select
                id="webhook-delivery-status"
                size="sm"
                value={statusFilter}
                onChange={(next) => {
                  setStatusFilter(next as '' | WebhookDeliveryStatus)
                  setDeliveryPage(0)
                }}
                options={[
                  { value: '', label: tc('filters.statusAll') },
                  { value: 'PENDING', label: t('integrations.deliveries.statuses.PENDING') },
                  { value: 'PROCESSED', label: t('integrations.deliveries.statuses.PROCESSED') },
                  { value: 'FAILED', label: t('integrations.deliveries.statuses.FAILED') },
                ]}
              />
            </div>
          }
        />
        <p className="text-body-secondary small">{t('integrations.deliveries.description')}</p>
        <DataTable
          columns={deliveryColumns}
          rows={deliveries?.content ?? []}
          total={deliveries?.totalElements}
          rowKey={(row) => row.id}
          isLoading={deliveriesQuery.isPending}
          error={deliveriesQuery.isError ? describeApiError(deliveriesQuery.error as ApiError) : null}
          onRetry={() => void deliveriesQuery.refetch()}
          emptyTitle={t('integrations.deliveries.empty.title')}
          emptyMessage={t('integrations.deliveries.empty.message')}
          footer={deliveries ? <Pagination page={deliveries} onPageChange={setDeliveryPage} /> : undefined}
        />
      </section>

      {modal && (
        <WebhookSubscriptionDrawer
          companyId={companyId}
          subscription={modal.mode === 'edit' ? modal.subscription : null}
          eventTypes={eventTypesQuery.data ?? []}
          onClose={() => setModal(null)}
          onSaved={(secret) => {
            setModal(null)
            if (secret) {
              setIssued(secret)
            } else {
              notifySuccess(td('updated'))
            }
            refreshSubscriptions()
          }}
        />
      )}

      {issued && (
        <SecretRevealDrawer
          title={t('integrations.webhooks.secretTitle')}
          notice={issued.notice}
          fields={[
            { label: t('integrations.webhooks.signingSecret'), value: issued.secret, secret: true },
            { label: t('integrations.webhooks.signatureHeader'), value: issued.signatureHeader, secret: false },
            { label: t('integrations.webhooks.signatureFormat'), value: issued.signedPayloadFormat, secret: false },
          ]}
          onClose={() => setIssued(null)}
        />
      )}

      {openDelivery && (
        <WebhookDeliveryDrawer
          companyId={companyId}
          deliveryId={openDelivery}
          onClose={() => setOpenDelivery(null)}
        />
      )}
    </div>
  )
}
