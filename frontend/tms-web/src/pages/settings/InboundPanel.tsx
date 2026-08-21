import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import {
  fetchIntegrationClients,
  fetchIntegrationRequests,
  revokeIntegrationClient,
  rotateIntegrationClient,
  type IntegrationClientSecretView,
  type IntegrationClientView,
  type IntegrationRequestView,
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
} from '../../shared/ui/components'
import { IntegrationClientDrawer } from './IntegrationClientDrawer'
import { SecretRevealDrawer } from './SecretRevealDrawer'

const PAGE_SIZE = 10

type ClientModal = { mode: 'create' } | { mode: 'edit'; client: IntegrationClientView } | null

/**
 * Inbound: who may write into this company, and what they have sent.
 *
 * The two tables belong together because the second is how the first is debugged. "Our WMS says it
 * posted that order yesterday" is answered by looking at the credential's traffic, not by looking at
 * the credential.
 *
 * Nothing here ever shows a secret except the one response that creates or rotates it - see
 * `SecretRevealDrawer`. The client id is not a secret and is shown in full, because a partner
 * quoting it is how a support conversation starts.
 */
export function InboundPanel({ companyId }: { companyId: string }) {
  const { t } = useTranslation('settings')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const { t: ts } = useTranslation('statuses')
  const format = useFormat()
  const { hasPermission } = useCompany()
  const canManage = hasPermission('integration.client:manage')
  const queryClient = useQueryClient()

  const [clientPage, setClientPage] = useState(0)
  const [requestPage, setRequestPage] = useState(0)
  const [clientFilter, setClientFilter] = useState('')
  const [modal, setModal] = useState<ClientModal>(null)
  const [issued, setIssued] = useState<IntegrationClientSecretView | null>(null)

  const clientsQuery = useQuery({
    queryKey: ['integration-clients', companyId, clientPage],
    queryFn: ({ signal }) =>
      fetchIntegrationClients(companyId, { page: clientPage, size: PAGE_SIZE, sort: 'name,asc' }, signal),
    placeholderData: keepPreviousData,
    enabled: companyId !== '',
  })

  const requestsQuery = useQuery({
    queryKey: ['integration-requests', companyId, requestPage, clientFilter],
    queryFn: ({ signal }) =>
      fetchIntegrationRequests(
        companyId,
        {
          page: requestPage,
          size: PAGE_SIZE,
          sort: 'receivedAt,desc',
          clientId: clientFilter || undefined,
        },
        signal,
      ),
    placeholderData: keepPreviousData,
    enabled: companyId !== '',
  })

  function refreshClients() {
    void queryClient.invalidateQueries({ queryKey: ['integration-clients', companyId] })
  }

  async function rotate(client: IntegrationClientView) {
    const confirmed = await confirmDialog({
      title: t('integrations.clients.rotateTitle', { name: client.name }),
      text: t('integrations.clients.rotateText'),
      confirmLabel: t('integrations.clients.rotate'),
    })
    if (!confirmed) return
    try {
      // No graceHours: the deployment's configured window applies, which is what a planned
      // rotation wants. A leaked secret is handled by revoking, which is immediate.
      setIssued(await rotateIntegrationClient(companyId, client.id))
      refreshClients()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  async function revoke(client: IntegrationClientView) {
    const confirmed = await confirmDialog({
      title: t('integrations.clients.revokeTitle', { name: client.name }),
      text: t('integrations.clients.revokeText'),
      confirmLabel: t('integrations.clients.revoke'),
      dangerous: true,
    })
    if (!confirmed) return
    try {
      await revokeIntegrationClient(companyId, client.id)
      notifySuccess(t('integrations.clients.revoked'), client.name)
      refreshClients()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  const clientColumns: DataTableColumn<IntegrationClientView>[] = [
    {
      key: 'name',
      header: tc('columns.name'),
      render: (client) => (
        <span>
          <span className="fw-semibold d-block">{client.name}</span>
          <code className="small text-body-secondary">{client.clientId}</code>
        </span>
      ),
    },
    {
      key: 'scopes',
      header: t('integrations.clients.scopes'),
      render: (client) => (
        <span className="d-flex flex-wrap gap-1">
          {client.scopes.map((scope) => (
            <StatusBadge key={scope} label={scope} tone="info" />
          ))}
        </span>
      ),
    },
    {
      key: 'status',
      header: tc('columns.status'),
      render: (client) => {
        if (client.revokedAt) return <StatusBadge label={t('integrations.clients.revoked')} tone="danger" />
        if (!client.active) return <StatusBadge label={ts('inactive')} tone="neutral" />
        // A rotation in flight is worth surfacing: two secrets are accepted until the window ends,
        // and somebody has to redeploy before it does.
        if (client.rotationGraceEndsAt) {
          return <StatusBadge label={t('integrations.clients.rotating')} tone="warning" />
        }
        return <StatusBadge label={ts('active')} tone="success" />
      },
    },
    {
      key: 'lastUsedAt',
      header: t('integrations.clients.lastUsed'),
      render: (client) =>
        client.lastUsedAt ? format.dateTime(client.lastUsedAt) : t('integrations.clients.neverUsed'),
    },
  ]

  if (canManage) {
    clientColumns.push({
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (client) => {
        if (client.revokedAt) {
          return <span className="text-body-secondary small">{t('integrations.clients.terminal')}</span>
        }
        const items: ActionMenuItem[] = [
          {
            key: 'edit',
            label: tc('actions.edit'),
            icon: 'bi-pencil',
            onSelect: () => setModal({ mode: 'edit', client }),
          },
          {
            key: 'rotate',
            label: t('integrations.clients.rotate'),
            icon: 'bi-arrow-repeat',
            onSelect: () => void rotate(client),
          },
          {
            key: 'revoke',
            label: t('integrations.clients.revoke'),
            icon: 'bi-slash-circle',
            dangerous: true,
            onSelect: () => void revoke(client),
          },
        ]
        return <ActionMenu items={items} />
      },
    })
  }

  const requestColumns: DataTableColumn<IntegrationRequestView>[] = [
    { key: 'receivedAt', header: t('integrations.inbox.receivedAt'), render: (row) => format.dateTime(row.receivedAt) },
    { key: 'operation', header: t('integrations.inbox.operation'), render: (row) => <code>{row.operation}</code> },
    {
      key: 'reference',
      header: t('integrations.inbox.reference'),
      render: (row) =>
        row.externalReference ? (
          <span>
            {row.externalReference}
            {row.externalSystem && <span className="d-block small text-body-secondary">{row.externalSystem}</span>}
          </span>
        ) : (
          '—'
        ),
    },
    {
      key: 'status',
      header: tc('columns.status'),
      render: (row) => (
        <span className="d-flex flex-column gap-1">
          <StatusBadge
            label={t(`integrations.inbox.statuses.${row.status}`)}
            tone={
              row.status === 'SUCCEEDED'
                ? 'success'
                : row.status === 'PARTIAL'
                  ? 'warning'
                  : 'danger'
            }
          />
          {row.itemCount > 1 && (
            <span className="small text-body-secondary">
              {t('integrations.inbox.counts', { succeeded: row.succeededCount, total: row.itemCount })}
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'outcome',
      header: t('integrations.inbox.outcome'),
      render: (row) => (
        <span>
          <span className="small">HTTP {row.httpStatus}</span>
          {row.errorSummary && <span className="d-block small text-danger">{row.errorSummary}</span>}
          {row.correlationId && (
            <code className="d-block small text-body-secondary">{row.correlationId}</code>
          )}
        </span>
      ),
    },
  ]

  const clients = clientsQuery.data
  const requests = requestsQuery.data

  return (
    <div className="d-flex flex-column gap-4">
      <section>
        <SectionHeader
          title={t('integrations.clients.title')}
          actions={
            canManage && (
              <button
                type="button"
                className="btn btn-primary btn-sm d-inline-flex align-items-center gap-2"
                onClick={() => setModal({ mode: 'create' })}
              >
                <i className="bi bi-key" aria-hidden="true" />
                {t('integrations.clients.issue')}
              </button>
            )
          }
        />
        <p className="text-body-secondary small">{t('integrations.clients.description')}</p>
        <DataTable
          columns={clientColumns}
          rows={clients?.content ?? []}
          total={clients?.totalElements}
          rowKey={(client) => client.id}
          isLoading={clientsQuery.isPending}
          error={clientsQuery.isError ? describeApiError(clientsQuery.error as ApiError) : null}
          onRetry={() => void clientsQuery.refetch()}
          emptyTitle={t('integrations.clients.empty.title')}
          emptyMessage={t('integrations.clients.empty.message')}
          footer={clients ? <Pagination page={clients} onPageChange={setClientPage} /> : undefined}
        />
      </section>

      <section>
        <SectionHeader
          title={t('integrations.inbox.title')}
          actions={
            <Select
              id="integration-inbox-client"
              size="sm"
              value={clientFilter}
              onChange={(next) => {
                setClientFilter(next)
                setRequestPage(0)
              }}
              options={[
                { value: '', label: t('integrations.inbox.allClients') },
                ...(clients?.content ?? []).map((client) => ({ value: client.id, label: client.name })),
              ]}
            />
          }
        />
        <p className="text-body-secondary small">{t('integrations.inbox.description')}</p>
        <DataTable
          columns={requestColumns}
          rows={requests?.content ?? []}
          total={requests?.totalElements}
          rowKey={(row) => row.id}
          isLoading={requestsQuery.isPending}
          error={requestsQuery.isError ? describeApiError(requestsQuery.error as ApiError) : null}
          onRetry={() => void requestsQuery.refetch()}
          emptyTitle={t('integrations.inbox.empty.title')}
          emptyMessage={t('integrations.inbox.empty.message')}
          footer={requests ? <Pagination page={requests} onPageChange={setRequestPage} /> : undefined}
        />
      </section>

      {modal && (
        <IntegrationClientDrawer
          companyId={companyId}
          client={modal.mode === 'edit' ? modal.client : null}
          onClose={() => setModal(null)}
          onSaved={(secret) => {
            setModal(null)
            if (secret) {
              setIssued(secret)
            } else {
              notifySuccess(td('updated'))
            }
            refreshClients()
          }}
        />
      )}

      {issued && (
        <SecretRevealDrawer
          title={t('integrations.clients.secretTitle')}
          notice={issued.notice}
          fields={[
            { label: t('integrations.clients.clientId'), value: issued.clientId, secret: false },
            { label: t('integrations.clients.secret'), value: issued.secret, secret: true },
            { label: t('integrations.clients.bearerToken'), value: issued.bearerToken, secret: true },
          ]}
          onClose={() => setIssued(null)}
        />
      )}
    </div>
  )
}
