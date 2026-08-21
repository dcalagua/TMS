import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  AUDIT_ACTIONS,
  AUDIT_AGGREGATE_TYPES,
  fetchAuditEvents,
  type AuditAction,
  type AuditAggregateType,
  type AuditEventView,
} from '../../shared/api/auditApi'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import { useCompany } from '../../shared/company/CompanyContext'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import {
  DataTable,
  FilterBar,
  PageHeader,
  Pagination,
  Select,
  TmsDrawer,
  type DataTableColumn,
} from '../../shared/ui/components'

const PAGE_SIZE = 50

interface AppliedFilters {
  aggregateType: AuditAggregateType | ''
  action: AuditAction | ''
  aggregateId: string
  correlationId: string
  from: string
  to: string
}

const DEFAULT_FILTERS: AppliedFilters = {
  aggregateType: '',
  action: '',
  aggregateId: '',
  correlationId: '',
  from: '',
  to: '',
}

/**
 * Who changed what, and when.
 *
 * Read-only, and visibly so: there is no action column, no row menu and no button anywhere on
 * this screen. That is not a permission the UI is hiding - `tms.audit_event` refuses UPDATE and
 * DELETE to the runtime role (migration V22), and there is no endpoint to call. A trail that
 * could be corrected would answer a different question from the one somebody opens it to ask.
 *
 * Behind `AUDIT_VIEW` (`audit.log:read`), which the two administrator roles hold and `PLANNER`
 * does not. Hiding the menu entry is UX; the endpoint answers 403 either way.
 *
 * Newest first, always. The dates are `datetime-local` inputs read in the browser's zone and sent
 * as instants, because "what happened this morning" is a question asked in the time zone the
 * person is standing in.
 *
 * The detail drawer exists because `metadata` is the part that says *what* changed - the shipment
 * number, the reason a trip was cancelled - and it is a different shape per action, so it cannot
 * be a column. Nothing sensitive reaches it: `AuditEventRecorder` writes short annotations, never
 * payloads and never credentials.
 */
export function AuditPage() {
  const { t } = useTranslation('security')
  const { t: tc } = useTranslation('common')
  const enumLabels = useEnumLabels()
  const format = useFormat()
  const { selected } = useCompany()
  const companyId = selected?.id ?? ''

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [selectedEntry, setSelectedEntry] = useState<AuditEventView | null>(null)

  const eventsQuery = useQuery({
    queryKey: ['audit-events', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchAuditEvents({
        companyId,
        page,
        size: PAGE_SIZE,
        aggregateType: filters.aggregateType || undefined,
        action: filters.action || undefined,
        aggregateId: filters.aggregateId.trim() || undefined,
        correlationId: filters.correlationId.trim() || undefined,
        // `datetime-local` has no zone, so it is read as local time and sent as an instant -
        // the same conversion the trip workspace makes for an operator's "actual departure".
        from: filters.from ? new Date(filters.from).toISOString() : undefined,
        to: filters.to ? new Date(filters.to).toISOString() : undefined,
        signal,
      }),
    enabled: companyId !== '',
    placeholderData: keepPreviousData,
  })

  function applyFilters() {
    setFilters(draftFilters)
    setPage(0)
  }

  function resetFilters() {
    setDraftFilters(DEFAULT_FILTERS)
    setFilters(DEFAULT_FILTERS)
    setPage(0)
  }

  /** The person, or the credential, or neither - in that order, because that is how it reads. */
  function actorOf(entry: AuditEventView): string {
    return entry.actorEmail ?? entry.actorMachineLabel ?? t('audit.noActor')
  }

  const columns: DataTableColumn<AuditEventView>[] = [
    {
      key: 'occurredAt',
      header: t('audit.columns.when'),
      render: (entry) => <span className="text-nowrap">{format.dateTime(entry.occurredAt)}</span>,
    },
    {
      key: 'actor',
      header: t('audit.columns.actor'),
      render: (entry) => (
        <div className="d-flex flex-column tms-min-w-0">
          <span className="tms-truncate">{actorOf(entry)}</span>
          {entry.actorMachineLabel && !entry.actorEmail && (
            <span className="small text-body-secondary">{t('audit.machineActor')}</span>
          )}
        </div>
      ),
    },
    {
      key: 'action',
      header: t('audit.columns.action'),
      render: (entry) => enumLabels.auditAction(entry.action),
    },
    {
      key: 'resource',
      header: t('audit.columns.resource'),
      render: (entry) => enumLabels.auditAggregateType(entry.aggregateType),
    },
    {
      key: 'aggregateId',
      header: t('audit.columns.identifier'),
      render: (entry) => <span className="tms-code small">{entry.aggregateId}</span>,
    },
    {
      key: 'detail',
      header: t('audit.columns.detail'),
      render: (entry) => (
        // A button and not a row click: the row is long, and a dispatcher scrolling one of these
        // with a mouse would open the drawer by accident several times a minute.
        <button
          type="button"
          className="btn btn-sm btn-link p-0 text-start"
          onClick={() => setSelectedEntry(entry)}
          aria-label={t('audit.openDetailNamed', {
            action: enumLabels.auditAction(entry.action),
            when: format.dateTime(entry.occurredAt),
          })}
        >
          {summarise(entry) || t('audit.noDetail')}
        </button>
      ),
    },
  ]

  /** The first couple of metadata values, so the common case needs no drawer at all. */
  function summarise(entry: AuditEventView): string {
    return Object.values(entry.metadata)
      .filter((value): value is string => value !== null && value !== '')
      .slice(0, 2)
      .join(' · ')
  }

  const pageData = eventsQuery.data

  return (
    <div>
      <PageHeader icon="clock-history" title={t('audit.title')} description={t('audit.description')} />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="audit-filter-from" className="form-label small mb-1">
            {t('audit.filters.from')}
          </label>
          <input
            id="audit-filter-from"
            type="datetime-local"
            className="form-control form-control-sm"
            value={draftFilters.from}
            onChange={(event) => setDraftFilters({ ...draftFilters, from: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="audit-filter-to" className="form-label small mb-1">
            {t('audit.filters.to')}
          </label>
          <input
            id="audit-filter-to"
            type="datetime-local"
            className="form-control form-control-sm"
            value={draftFilters.to}
            onChange={(event) => setDraftFilters({ ...draftFilters, to: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="audit-filter-resource" className="form-label small mb-1">
            {t('audit.columns.resource')}
          </label>
          <Select
            id="audit-filter-resource"
            size="sm"
            value={draftFilters.aggregateType}
            onChange={(next) =>
              setDraftFilters({ ...draftFilters, aggregateType: next as AuditAggregateType | '' })
            }
            options={[
              { value: '', label: t('audit.filters.allResources') },
              ...AUDIT_AGGREGATE_TYPES.map((type) => ({
                value: type,
                label: enumLabels.auditAggregateType(type),
              })),
            ]}
          />
        </div>
        <div>
          <label htmlFor="audit-filter-action" className="form-label small mb-1">
            {t('audit.columns.action')}
          </label>
          <Select
            id="audit-filter-action"
            size="sm"
            value={draftFilters.action}
            onChange={(next) => setDraftFilters({ ...draftFilters, action: next as AuditAction | '' })}
            options={[
              { value: '', label: t('audit.filters.allActions') },
              ...AUDIT_ACTIONS.map((action) => ({ value: action, label: enumLabels.auditAction(action) })),
            ]}
          />
        </div>
        <div>
          <label htmlFor="audit-filter-aggregate-id" className="form-label small mb-1">
            {t('audit.columns.identifier')}
          </label>
          <input
            id="audit-filter-aggregate-id"
            className="form-control form-control-sm"
            placeholder={t('audit.filters.identifierHint')}
            value={draftFilters.aggregateId}
            onChange={(event) => setDraftFilters({ ...draftFilters, aggregateId: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="audit-filter-correlation" className="form-label small mb-1">
            {t('audit.filters.correlationId')}
          </label>
          <input
            id="audit-filter-correlation"
            className="form-control form-control-sm"
            placeholder={t('audit.filters.correlationHint')}
            value={draftFilters.correlationId}
            onChange={(event) => setDraftFilters({ ...draftFilters, correlationId: event.target.value })}
          />
        </div>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(entry) => entry.id}
        isLoading={eventsQuery.isPending}
        error={eventsQuery.isError ? describeApiError(eventsQuery.error as ApiError) : null}
        onRetry={() => void eventsQuery.refetch()}
        emptyTitle={t('audit.empty.title')}
        emptyMessage={t('audit.empty.message')}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {selectedEntry && (
        <TmsDrawer
          open
          title={enumLabels.auditAction(selectedEntry.action)}
          subtitle={format.dateTime(selectedEntry.occurredAt)}
          size="md"
          onClose={() => setSelectedEntry(null)}
        >
          <dl className="row small mb-0">
            <dt className="col-4 fw-normal text-body-secondary">{t('audit.columns.actor')}</dt>
            <dd className="col-8 tms-truncate">{actorOf(selectedEntry)}</dd>

            <dt className="col-4 fw-normal text-body-secondary">{t('audit.columns.resource')}</dt>
            <dd className="col-8">{enumLabels.auditAggregateType(selectedEntry.aggregateType)}</dd>

            <dt className="col-4 fw-normal text-body-secondary">{t('audit.columns.identifier')}</dt>
            <dd className="col-8 tms-code">{selectedEntry.aggregateId}</dd>

            {selectedEntry.correlationId && (
              <>
                <dt className="col-4 fw-normal text-body-secondary">{t('audit.filters.correlationId')}</dt>
                <dd className="col-8 tms-code">{selectedEntry.correlationId}</dd>
              </>
            )}

            {Object.entries(selectedEntry.metadata).map(([key, value]) => (
              <div className="row mx-0 px-0" key={key}>
                {/* The metadata keys are written by the backend and are not part of any
                    translated vocabulary, so they are shown as they were recorded rather than
                    guessed at with a lookup that would miss half of them. */}
                <dt className="col-4 fw-normal text-body-secondary tms-code px-0">{key}</dt>
                <dd className="col-8 px-0 mb-1">{value ?? '—'}</dd>
              </div>
            ))}
          </dl>

          {Object.keys(selectedEntry.metadata).length === 0 && (
            <p className="small text-body-secondary mt-3 mb-0">{t('audit.noDetailHint')}</p>
          )}

          <p className="small text-body-secondary mt-4 mb-0">{tc('states.readOnly')}</p>
        </TmsDrawer>
      )}
    </div>
  )
}
