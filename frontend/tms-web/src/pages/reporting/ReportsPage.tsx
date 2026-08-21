import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import { TRIP_STATUSES, type TripStatus } from '../../shared/api/planningApi'
import { describeApiError } from '../../shared/api/problemMessages'
import {
  downloadKpiCsv,
  fetchKpiReport,
  type KpiCostView,
  type KpiDailyRow,
} from '../../shared/api/reportingApi'
import { useCompany } from '../../shared/company/CompanyContext'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import { notifyError } from '../../shared/ui/alerts'
import {
  DataTable,
  FilterBar,
  KpiCard,
  PageHeader,
  SectionHeader,
  StatusBadge,
  type DataTableColumn,
} from '../../shared/ui/components'
import { TRIP_STATUS_TONE } from '../../shared/ui/statusTones'
import { DailyColumnChart } from './DailyColumnChart'

interface AppliedRange {
  /** Empty means "let the server decide" - thirty days back from its own today. */
  from: string
  to: string
}

const DEFAULT_RANGE: AppliedRange = { from: '', to: '' }

/**
 * Reports & KPIs (`docs/domain/KPIS_REPORTING_V1.md`).
 *
 * The counterpart of the control tower: that screen is one day and answers "what is happening", this
 * one is a span of days and answers "how did we do". It is the screen a transport manager shows to
 * somebody deciding whether to keep the contract, which is the reason every rule below exists.
 *
 * <b>Nothing on it is computed here.</b> Every counter, every percentage and every day of the series
 * is a server-side aggregate. The browser lays them out, formats them for the locale, and links what
 * it can back to the list the number came from.
 *
 * <b>A dash is a real answer.</b> A percentage arrives as `null` when nothing was measured - no
 * departure recorded, nothing delivered - and it renders as a dash with a hint saying so. Rendering
 * it as 0% would accuse an operation of never being punctual; 100% would congratulate it for the
 * absence of evidence. The same rule applies to the three sections a caller may not be entitled to
 * see, which arrive as `null` rather than as zeros.
 *
 * <b>Two charts, never one with two scales.</b> Shipments are a count and punctuality is a
 * percentage; drawing them on one plot with two y-axes would invent a correlation out of an
 * arbitrary alignment of the scales. They share the filter bar and the range, and nothing else.
 *
 * <b>The table is the twin, not an appendix.</b> Every value in the charts is a row of the detail
 * table below them, which is what makes the charts safe to keep sparse - and it is the same table
 * the CSV export writes.
 */
export function ReportsPage() {
  const { t } = useTranslation('reporting')
  const enumLabels = useEnumLabels()
  const format = useFormat()
  const { selected } = useCompany()
  const companyId = selected?.id ?? ''

  const [draft, setDraft] = useState<AppliedRange>(DEFAULT_RANGE)
  const [range, setRange] = useState<AppliedRange>(DEFAULT_RANGE)
  const [exporting, setExporting] = useState(false)

  const report = useQuery({
    queryKey: ['kpi-report', companyId, range.from, range.to],
    queryFn: ({ signal }) =>
      fetchKpiReport({
        companyId,
        from: range.from || undefined,
        to: range.to || undefined,
        signal,
      }),
    enabled: companyId !== '',
    // Held rather than replaced by a skeleton: somebody stepping from March to April is comparing
    // two ranges, and a chart that vanishes and comes back moves the page under their cursor.
    placeholderData: keepPreviousData,
  })

  // The date boxes start empty, which means "the server's default range". Once it has answered they
  // are filled in with the days it actually chose - in the company's zone, which a browser one time
  // zone east would otherwise get wrong by a day. Only the draft is touched, so this never refetches.
  const resolvedFrom = report.data?.from
  const resolvedTo = report.data?.to
  useEffect(() => {
    if (!resolvedFrom || !resolvedTo) {
      return
    }
    setDraft((current) => (current.from === '' && current.to === '' ? { from: resolvedFrom, to: resolvedTo } : current))
  }, [resolvedFrom, resolvedTo])

  function applyRange() {
    setRange(draft)
  }

  function resetRange() {
    setDraft(DEFAULT_RANGE)
    setRange(DEFAULT_RANGE)
  }

  /**
   * Fetches the CSV through the API and hands it to the browser to save - the same shape
   * `ImportDrawer` uses, because there is no address these bytes are reachable at: the request
   * carries the bearer token and the company header like every other one.
   */
  async function exportCsv() {
    setExporting(true)
    try {
      const downloaded = await downloadKpiCsv({
        companyId,
        from: range.from || undefined,
        to: range.to || undefined,
      })
      const url = URL.createObjectURL(downloaded.blob)
      const link = document.createElement('a')
      link.href = url
      link.download = downloaded.fileName ?? 'tms-kpis.csv'
      link.click()
      // Revoking immediately after the synthetic click is safe: the browser has already taken its
      // own reference to the blob by then, and not revoking leaks it for the tab's lifetime.
      URL.revokeObjectURL(url)
    } catch (error) {
      notifyError(t('exportError'), describeApiError(error as ApiError))
    } finally {
      setExporting(false)
    }
  }

  const data = report.data
  const percent = (value: number | null | undefined) =>
    value === null || value === undefined ? undefined : format.percent(value, 1)
  const count = (value: number | null | undefined) =>
    value === null || value === undefined ? undefined : format.quantity(value)

  const dailyColumns: DataTableColumn<KpiDailyRow>[] = [
    { key: 'date', header: t('columns.date'), render: (row) => format.date(row.date) },
    { key: 'trips', header: t('columns.trips'), numeric: true, render: (row) => format.quantity(row.trips) },
    {
      key: 'tripsCompleted',
      header: t('columns.completed'),
      numeric: true,
      render: (row) => format.quantity(row.tripsCompleted),
    },
    {
      key: 'tripsCancelled',
      header: t('columns.cancelled'),
      numeric: true,
      render: (row) => format.quantity(row.tripsCancelled),
    },
    {
      key: 'departures',
      header: t('columns.departures'),
      numeric: true,
      // The measured/late pair beside the percentage: 92% over five departures and 92% over four
      // hundred are different claims, and the table is where that is said in full.
      render: (row) => `${format.quantity(row.departuresLate)} / ${format.quantity(row.departuresMeasured)}`,
    },
    {
      key: 'onTimeDeparture',
      header: t('columns.onTimeDeparture'),
      numeric: true,
      render: (row) => format.percent(row.onTimeDeparturePercent, 1),
    },
    {
      key: 'deliveries',
      header: t('columns.deliveries'),
      numeric: true,
      render: (row) => `${format.quantity(row.deliveriesDelivered)} / ${format.quantity(row.deliveriesRecorded)}`,
    },
    {
      key: 'deliverySuccess',
      header: t('columns.deliverySuccess'),
      numeric: true,
      render: (row) => format.percent(row.deliverySuccessPercent, 1),
    },
    {
      key: 'exceptions',
      header: t('columns.exceptions'),
      numeric: true,
      render: (row) =>
        row.exceptions === 0
          ? '—'
          : t('exceptionCell', { total: format.quantity(row.exceptions), open: format.quantity(row.exceptionsOpen) }),
    },
  ]

  const costColumns: DataTableColumn<KpiCostView>[] = [
    { key: 'currency', header: t('columns.currency'), render: (row) => row.currency },
    {
      key: 'estimated',
      header: t('columns.estimated'),
      numeric: true,
      render: (row) => t('overTrips', { amount: format.decimal(row.estimatedAmount), trips: row.tripsEstimated }),
    },
    {
      key: 'actual',
      header: t('columns.actual'),
      numeric: true,
      render: (row) => t('overTrips', { amount: format.decimal(row.actualAmount), trips: row.tripsWithActual }),
    },
    {
      key: 'comparable',
      header: t('columns.comparable'),
      numeric: true,
      render: (row) => format.quantity(row.tripsComparable),
    },
    {
      key: 'variance',
      header: t('columns.variance'),
      numeric: true,
      // The sign is shown and never editorialised into "saving" or "overspend": an operations
      // manager reads this number in both directions.
      render: (row) =>
        row.variance === null ? (
          '—'
        ) : (
          <StatusBadge
            label={`${format.decimal(row.variance)} (${format.percent(row.variancePercent, 1)})`}
            tone={row.variance > 0 ? 'danger' : 'success'}
          />
        ),
    },
  ]

  const stale = report.isPlaceholderData ? ' tms-chart-stale' : ''

  return (
    <div>
      <PageHeader
        icon="bar-chart-line"
        title={t('title')}
        description={t('description')}
        meta={
          data ? (
            <StatusBadge
              label={t('rangeBadge', { from: format.date(data.from), to: format.date(data.to), days: data.days })}
              tone="info"
            />
          ) : undefined
        }
        actions={
          <div className="d-flex align-items-center gap-2">
            <button
              type="button"
              className="btn btn-sm btn-outline-secondary d-flex align-items-center gap-2"
              onClick={() => void report.refetch()}
            >
              <i className="bi bi-arrow-clockwise" aria-hidden="true" />
              {t('refresh')}
            </button>
            <button
              type="button"
              className="btn btn-sm btn-outline-primary d-flex align-items-center gap-2"
              onClick={() => void exportCsv()}
              disabled={exporting || data === undefined}
            >
              <i className="bi bi-filetype-csv" aria-hidden="true" />
              {t('exportCsv')}
            </button>
          </div>
        }
      />

      {/* One filter row above everything it scopes, never a filter inside a chart card: both charts
          and both tables re-render against the same slice. */}
      <FilterBar onSubmit={applyRange} onReset={resetRange}>
        <div>
          <label htmlFor="kpi-from" className="form-label small mb-1">
            {t('filters.from')}
          </label>
          <input
            id="kpi-from"
            type="date"
            className="form-control form-control-sm"
            value={draft.from}
            onChange={(event) => setDraft({ ...draft, from: event.target.value })}
          />
        </div>
        <div>
          <label htmlFor="kpi-to" className="form-label small mb-1">
            {t('filters.to')}
          </label>
          <input
            id="kpi-to"
            type="date"
            className="form-control form-control-sm"
            value={draft.to}
            onChange={(event) => setDraft({ ...draft, to: event.target.value })}
          />
        </div>
      </FilterBar>

      <p className="text-body-secondary small mt-2 mb-3">{t('scopeNote')}</p>

      {/* One request feeds the whole screen, so one alert covers all of it. Said out loud rather
          than left to the dashes the cards would otherwise show: "nothing was measured" and "I could
          not ask" have to look different. */}
      {report.isError && (
        <div className="alert alert-warning d-flex align-items-start gap-2 py-2 small" role="alert">
          <i className="bi bi-exclamation-triangle-fill mt-1" aria-hidden="true" />
          <span>{describeApiError(report.error as ApiError)}</span>
        </div>
      )}

      <SectionHeader level={2} title={t('sections.headline')} />
      <div className="tms-kpi-grid">
        <KpiCard
          icon="bi-truck"
          label={t('kpi.trips')}
          hint={t('kpi.tripsHint', {
            run: data?.shipments.tripsRun ?? 0,
            cancelled: data?.shipments.tripsCancelled ?? 0,
          })}
          value={count(data?.shipments.trips)}
          isLoading={report.isPending}
          to="/trips"
        />
        <KpiCard
          icon="bi-box-arrow-right"
          tone="info"
          label={t('kpi.onTimeDeparture')}
          hint={
            data && data.shipments.departuresMeasured === 0
              ? t('kpi.nothingMeasured')
              : t('kpi.onTimeDepartureHint', { measured: data?.shipments.departuresMeasured ?? 0 })
          }
          value={percent(data?.shipments.onTimeDeparturePercent)}
          isLoading={report.isPending}
        />
        <KpiCard
          icon="bi-geo"
          tone="info"
          label={t('kpi.onTimeService')}
          hint={
            data && data.service.serviceWindowsMeasured === 0
              ? t('kpi.nothingMeasured')
              : t('kpi.onTimeServiceHint', { measured: data?.service.serviceWindowsMeasured ?? 0 })
          }
          value={percent(data?.service.onTimeServicePercent)}
          isLoading={report.isPending}
        />
        <KpiCard
          icon="bi-box-seam"
          tone="success"
          label={t('kpi.deliverySuccess')}
          hint={
            data && data.service.deliveriesRecorded === 0
              ? t('kpi.nothingRecorded')
              : t('kpi.deliverySuccessHint', {
                  delivered: data?.service.deliveriesDelivered ?? 0,
                  recorded: data?.service.deliveriesRecorded ?? 0,
                })
          }
          value={percent(data?.service.deliverySuccessPercent)}
          isLoading={report.isPending}
        />
        <KpiCard
          icon="bi-speedometer"
          label={t('kpi.utilization')}
          // The count of shipments the percentage is about, always: 82% over eleven of four hundred
          // shipments is not a fleet-wide figure and the card has to be able to say so.
          hint={t('kpi.utilizationHint', { trips: data?.utilization.trips ?? 0 })}
          value={percent(data?.utilization.weightPercent)}
          isLoading={report.isPending}
        />
        <KpiCard
          icon="bi-exclamation-octagon"
          tone={data && data.exceptions.open > 0 ? 'danger' : 'neutral'}
          label={t('kpi.exceptions')}
          hint={t('kpi.exceptionsHint', {
            open: data?.exceptions.open ?? 0,
            per100: format.decimal(data?.exceptions.per100Trips ?? null, 1),
          })}
          value={count(data?.exceptions.exceptions)}
          isLoading={report.isPending}
        />
        <KpiCard
          icon="bi-inbox"
          label={t('kpi.plannedOrders')}
          // Null is not zero: this caller was not allowed to look at the backlog, and the hint says
          // so rather than the card implying an empty queue.
          hint={
            data && data.orders === null
              ? t('kpi.notPermitted')
              : t('kpi.plannedOrdersHint', {
                  planned: data?.orders?.planned ?? 0,
                  input: data?.orders?.inputOrders ?? 0,
                })
          }
          value={percent(data?.orders?.plannedPercent)}
          isLoading={report.isPending}
          // No link when the section came back null: the response has already told us this caller
          // may not read orders, and a card that navigated them to a 403 would be the screen
          // ignoring what it was just told.
          to={data && data.orders !== null ? '/orders' : undefined}
        />
        <KpiCard
          icon="bi-hand-thumbs-up"
          label={t('kpi.tenderAcceptance')}
          hint={
            data && data.tenders === null
              ? t('kpi.notPermitted')
              : t('kpi.tenderAcceptanceHint', {
                  answered: data?.tenders?.answered ?? 0,
                  attempts: data?.tenders?.attempts ?? 0,
                })
          }
          value={percent(data?.tenders?.acceptancePercent)}
          isLoading={report.isPending}
        />
      </div>

      {/* The lifecycle breakdown as chips rather than a ninth card: it is one fact split six ways,
          and six more cards would bury the eight numbers above that are actually decisions. */}
      {data && (
        <div className="d-flex flex-wrap align-items-center gap-2 mb-4">
          <span className="small text-body-secondary">{t('byStatus')}</span>
          {TRIP_STATUSES.map((status: TripStatus) => (
            <StatusBadge
              key={status}
              label={`${enumLabels.tripStatus(status)}: ${format.quantity(data.shipments.byStatus[status] ?? 0)}`}
              tone={TRIP_STATUS_TONE[status]}
            />
          ))}
        </div>
      )}

      <SectionHeader level={2} title={t('sections.daily')} />
      <div className={`tms-charts-grid${stale}`}>
        <DailyColumnChart
          title={t('charts.trips')}
          description={t('charts.tripsHint')}
          points={(data?.daily ?? []).map((row) => ({ date: row.date, value: row.trips }))}
          formatValue={(value) => format.quantity(value)}
          formatDate={(value) => format.date(value)}
          noDataLabel={t('charts.noData')}
        />
        <DailyColumnChart
          title={t('charts.onTime')}
          description={t('charts.onTimeHint')}
          points={(data?.daily ?? []).map((row) => ({ date: row.date, value: row.onTimeDeparturePercent }))}
          formatValue={(value) => format.percent(value, 0)}
          formatDate={(value) => format.date(value)}
          noDataLabel={t('charts.noData')}
          // Fixed at 100 so two ranges are comparable and a quiet month cannot magnify its own noise.
          max={100}
        />
      </div>

      {data?.cost && data.cost.length > 0 && (
        <>
          <SectionHeader level={2} title={t('sections.cost')} />
          {/* Said next to the table rather than left implicit: a variance over three invoiced
              shipments is not a variance over four hundred estimated ones. */}
          <p className="text-body-secondary small mb-2">{t('sections.costHint')}</p>
          <div className="mb-4">
            <DataTable
              columns={costColumns}
              rows={data.cost}
              rowKey={(row) => row.currency}
              caption={t('sections.cost')}
              emptyTitle={t('empty.costTitle')}
              emptyMessage={t('empty.costMessage')}
            />
          </div>
        </>
      )}

      <SectionHeader level={2} title={t('sections.detail')} />
      {/* The charts' twin: every value they plot is a row here, which is what makes it safe for the
          charts to stay sparse and what the CSV export writes. */}
      <p className="text-body-secondary small mb-2">{t('sections.detailHint')}</p>
      <DataTable
        columns={dailyColumns}
        rows={data?.daily ?? []}
        rowKey={(row) => row.date}
        isLoading={report.isPending}
        error={report.isError ? describeApiError(report.error as ApiError) : null}
        onRetry={() => void report.refetch()}
        emptyTitle={t('empty.dailyTitle')}
        emptyMessage={t('empty.dailyMessage')}
        caption={t('sections.detail')}
      />
    </div>
  )
}
