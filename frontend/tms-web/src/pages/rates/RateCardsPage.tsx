import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { fetchCarriers } from '../../shared/api/carriersApi'
import type { ApiError } from '../../shared/api/httpClient'
import { describeApiError } from '../../shared/api/problemMessages'
import {
  activateRateCard,
  deactivateRateCard,
  fetchRateCards,
  RATE_CARD_SCOPES,
  type RateCardScope,
  type RateCardView,
} from '../../shared/api/ratesApi'
import { useCompany } from '../../shared/company/CompanyContext'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import {
  ActionMenu,
  ActiveBadge,
  confirmDialog,
  DataTable,
  FilterBar,
  PageHeader,
  Pagination,
  Select,
  type DataTableColumn,
} from '../../shared/ui/components'
import { RateCardFormDrawer } from './RateCardFormDrawer'

const PAGE_SIZE = 25

type ActiveFilter = 'active' | 'inactive' | 'all'

interface AppliedFilters {
  code: string
  carrierId: string
  scope: RateCardScope | ''
  onDate: string
  active: ActiveFilter
}

const DEFAULT_FILTERS: AppliedFilters = { code: '', carrierId: '', scope: '', onDate: '', active: 'active' }

type ModalState = { mode: 'create' } | { mode: 'edit'; card: RateCardView } | null

/**
 * The tariff master: what each carrier charges, for what, and between which dates.
 *
 * The validity column is deliberately a range and never a single "valid" badge. Two cards for the
 * same corridor, one ending on Friday and one starting on Saturday, are the normal way a
 * renegotiation is recorded here, and a screen that collapsed that into "active/inactive" would
 * hide the only thing a commercial manager is looking for.
 */
export function RateCardsPage() {
  const { t } = useTranslation('rates')
  const { t: tc } = useTranslation('common')
  const { t: td } = useTranslation('dialogs')
  const { selected, hasPermission } = useCompany()
  const enumLabels = useEnumLabels()
  const format = useFormat()
  const companyId = selected?.id ?? ''
  const canManage = hasPermission('rates.rate_card:manage')
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [draftFilters, setDraftFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [filters, setFilters] = useState<AppliedFilters>(DEFAULT_FILTERS)
  const [modal, setModal] = useState<ModalState>(null)

  const cardsQuery = useQuery({
    queryKey: ['rate-cards', companyId, page, filters],
    queryFn: ({ signal }) =>
      fetchRateCards({
        companyId,
        page,
        size: PAGE_SIZE,
        sort: 'code,asc',
        code: filters.code || undefined,
        carrierId: filters.carrierId || undefined,
        scope: filters.scope || undefined,
        onDate: filters.onDate || undefined,
        active: filters.active === 'all' ? undefined : filters.active === 'active',
        signal,
      }),
    placeholderData: keepPreviousData,
  })

  const carriersQuery = useQuery({
    queryKey: ['carriers-for-rate-filter', companyId],
    queryFn: ({ signal }) => fetchCarriers({ companyId, size: 200, active: true, sort: 'code,asc', signal }),
  })
  const carriers = carriersQuery.data?.content ?? []

  function applyFilters() {
    setFilters(draftFilters)
    setPage(0)
  }

  function resetFilters() {
    setDraftFilters(DEFAULT_FILTERS)
    setFilters(DEFAULT_FILTERS)
    setPage(0)
  }

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['rate-cards', companyId] })
  }

  async function toggleActive(card: RateCardView) {
    const confirmed = await confirmDialog({
      title: card.active ? td('deactivate.title', { name: card.code }) : td('activate.title', { name: card.code }),
      text: card.active ? t('deactivateText') : t('activateText'),
      confirmLabel: card.active ? tc('actions.deactivate') : tc('actions.activate'),
      dangerous: card.active,
    })
    if (!confirmed) return

    try {
      if (card.active) {
        await deactivateRateCard(companyId, card.id)
        notifySuccess(td('deactivated'), card.code)
      } else {
        await activateRateCard(companyId, card.id)
        notifySuccess(td('activated'), card.code)
      }
      refresh()
    } catch (error) {
      notifyError(td('errorTitle'), describeApiError(error as ApiError))
    }
  }

  /**
   * Money keeps both decimals and a unit rate keeps up to four - `format.decimal` drops trailing
   * zeros, and a base amount rendered as "120" next to another card's "120,50" invites a
   * comparison of two numbers that are not written the same way.
   */
  const money = (value: number) =>
    format.number(value, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const rate = (value: number) =>
    format.number(value, { minimumFractionDigits: 2, maximumFractionDigits: 4 })

  /** "Base 120,00 · 0,85/km" - the whole tariff in one column, because that is what is compared. */
  function summarizeComponents(card: RateCardView): string {
    const parts: string[] = []
    if (card.baseAmount != null) parts.push(`${t('componentShort.base')} ${money(card.baseAmount)}`)
    if (card.amountPerKm != null) parts.push(`${rate(card.amountPerKm)}${t('componentShort.perKm')}`)
    if (card.amountPerKg != null) parts.push(`${rate(card.amountPerKg)}${t('componentShort.perKg')}`)
    if (card.amountPerM3 != null) parts.push(`${rate(card.amountPerM3)}${t('componentShort.perM3')}`)
    if (card.amountPerPallet != null) parts.push(`${rate(card.amountPerPallet)}${t('componentShort.perPallet')}`)
    if (card.minimumAmount != null) parts.push(`${t('componentShort.minimum')} ${money(card.minimumAmount)}`)
    return parts.join(' · ')
  }

  const columns: DataTableColumn<RateCardView>[] = [
    { key: 'code', header: tc('columns.code'), render: (card) => <span className="fw-semibold">{card.code}</span> },
    { key: 'name', header: tc('columns.name'), render: (card) => card.name },
    {
      key: 'carrier',
      header: tc('columns.carrier'),
      render: (card) => card.carrierName ?? card.carrierCode ?? '—',
    },
    {
      key: 'scope',
      header: t('columns.scope'),
      render: (card) => (
        <div className="d-flex flex-column">
          <span>{enumLabels.rateCardScope(card.scope)}</span>
          {card.scopeTargetCode && <span className="text-body-secondary small">{card.scopeTargetCode}</span>}
        </div>
      ),
    },
    {
      key: 'vehicleType',
      header: tc('fields.vehicleType'),
      render: (card) => card.vehicleTypeCode ?? t('anyVehicleType'),
    },
    {
      key: 'validity',
      header: t('columns.validity'),
      render: (card) =>
        `${format.date(card.validFrom)} — ${card.validTo ? format.date(card.validTo) : t('noEndDate')}`,
    },
    {
      key: 'components',
      header: t('columns.components'),
      render: (card) => (
        <div className="d-flex flex-column">
          <span className="tms-code">{card.currency}</span>
          <span className="text-body-secondary small">{summarizeComponents(card)}</span>
        </div>
      ),
    },
    { key: 'active', header: tc('columns.status'), render: (card) => <ActiveBadge active={card.active} /> },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: tc('columns.actions'),
      actions: true,
      render: (card) => (
        <ActionMenu
          items={[
            {
              key: 'edit',
              label: tc('actions.edit'),
              icon: 'bi-pencil',
              onSelect: () => setModal({ mode: 'edit', card }),
            },
            {
              key: 'active',
              label: card.active ? tc('actions.deactivate') : tc('actions.activate'),
              icon: card.active ? 'bi-slash-circle' : 'bi-check-circle',
              dangerous: card.active,
              onSelect: () => void toggleActive(card),
            },
          ]}
        />
      ),
    })
  }

  const pageData = cardsQuery.data

  return (
    <div>
      <PageHeader
        icon="cash-coin"
        title={t('title')}
        description={t('description')}
        actions={
          canManage && (
            <button
              type="button"
              className="btn btn-primary btn-sm d-inline-flex align-items-center gap-2"
              onClick={() => setModal({ mode: 'create' })}
            >
              <i className="bi bi-plus-lg" aria-hidden="true" />
              {t('new')}
            </button>
          )
        }
      />

      <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
        <div>
          <label htmlFor="rate-filter-code" className="form-label small mb-1">
            {tc('columns.code')}
          </label>
          <input
            id="rate-filter-code"
            className="form-control form-control-sm"
            value={draftFilters.code}
            onChange={(event) => setDraftFilters({ ...draftFilters, code: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="rate-filter-carrier" className="form-label small mb-1">
            {tc('columns.carrier')}
          </label>
          <Select
            id="rate-filter-carrier"
            size="sm"
            value={draftFilters.carrierId}
            onChange={(next) => setDraftFilters({ ...draftFilters, carrierId: next })}
            options={[
              { value: '', label: tc('filters.allCarriers') },
              ...carriers.map((carrier) => ({ value: carrier.id, label: carrier.code })),
            ]}
          />
        </div>
        <div>
          <label htmlFor="rate-filter-scope" className="form-label small mb-1">
            {t('columns.scope')}
          </label>
          <Select
            id="rate-filter-scope"
            size="sm"
            value={draftFilters.scope}
            onChange={(next) => setDraftFilters({ ...draftFilters, scope: next as RateCardScope | '' })}
            options={[
              { value: '', label: t('filters.allScopes') },
              ...RATE_CARD_SCOPES.map((scope) => ({ value: scope, label: enumLabels.rateCardScope(scope) })),
            ]}
          />
        </div>
        <div>
          <label htmlFor="rate-filter-on-date" className="form-label small mb-1">
            {t('filters.onDate')}
          </label>
          <input
            id="rate-filter-on-date"
            type="date"
            className="form-control form-control-sm"
            value={draftFilters.onDate}
            onChange={(event) => setDraftFilters({ ...draftFilters, onDate: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="rate-filter-active" className="form-label small mb-1">
            {tc('columns.status')}
          </label>
          <Select
            id="rate-filter-active"
            size="sm"
            value={draftFilters.active}
            onChange={(next) => setDraftFilters({ ...draftFilters, active: next as ActiveFilter })}
            options={[
              { value: 'active', label: tc('filters.statusActive') },
              { value: 'inactive', label: tc('filters.statusInactive') },
              { value: 'all', label: tc('filters.statusAll') },
            ]}
          />
        </div>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={pageData?.content ?? []}
        total={pageData?.totalElements}
        rowKey={(card) => card.id}
        isLoading={cardsQuery.isPending}
        error={cardsQuery.isError ? describeApiError(cardsQuery.error as ApiError) : null}
        onRetry={() => void cardsQuery.refetch()}
        emptyTitle={t('empty.title')}
        emptyMessage={t('empty.message')}
        footer={pageData ? <Pagination page={pageData} onPageChange={setPage} /> : undefined}
      />

      {modal && (
        <RateCardFormDrawer
          companyId={companyId}
          card={modal.mode === 'edit' ? modal.card : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            notifySuccess(modal.mode === 'edit' ? td('updated') : td('created'))
            refresh()
          }}
        />
      )}
    </div>
  )
}
