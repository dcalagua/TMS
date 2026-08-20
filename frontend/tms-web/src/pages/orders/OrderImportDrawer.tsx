import { useState, type ChangeEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { ApiError } from '../../shared/api/httpClient'
import {
  applyOrderImport,
  downloadOrderImportTemplate,
  previewOrderImport,
  type OrderImportFormat,
  type OrderImportOutcome,
  type OrderImportReport,
} from '../../shared/api/ordersApi'
import { describeApiError, describeImportError } from '../../shared/api/problemMessages'
import { useEnumLabels } from '../../shared/i18n/enums'
import { useFormat } from '../../shared/i18n/format'
import { notifyError, notifySuccess } from '../../shared/ui/alerts'
import { FormField } from '../../shared/ui/components/FormField'
import { StatusBadge, type StatusTone } from '../../shared/ui/components/StatusBadge'
import { TmsDrawer } from '../../shared/ui/components/TmsDrawer'
import { confirmDialog } from '../../shared/ui/components/ConfirmDialog'

/** Mirrors `OrderImportLimits`. Shown, not enforced: the server rejects an oversized file
 * regardless, and duplicating a limit in the browser only helps if the operator is told it
 * before they wait for an upload to fail. */
const MAX_FILE_MB = 2
const MAX_ROWS = 5000

const OUTCOME_TONE: Record<OrderImportOutcome, StatusTone> = {
  CREATE: 'success',
  SKIPPED_DUPLICATE: 'neutral',
  REJECTED: 'danger',
}

interface OrderImportDrawerProps {
  companyId: string
  onClose: () => void
  /** Called after an applied import, so the list behind the drawer reloads. */
  onImported: () => void
}

/**
 * The bulk order import, as three steps in one drawer: take the template, upload a filled copy,
 * read what it would do - and only then apply it.
 *
 * <p>The preview is not a convenience. `OrderImportService` refuses a file with any invalid row
 * outright, so without a dry run an operator's only way to find out what is wrong with 300 rows
 * would be to attempt the import and read the refusal. Here the same evaluation runs first,
 * writes nothing, and comes back as a table.
 *
 * <p>Applying is deliberately gated behind an explicit second action and a confirmation, never
 * fused into the upload: uploading is inspection, importing is a decision.
 */
export function OrderImportDrawer({ companyId, onClose, onImported }: OrderImportDrawerProps) {
  const { t } = useTranslation('orders')
  const { t: tc } = useTranslation('common')
  const { t: tv } = useTranslation('validations')
  const enumLabels = useEnumLabels()
  const format = useFormat()

  const [externalSource, setExternalSource] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [report, setReport] = useState<OrderImportReport | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<'preview' | 'apply' | null>(null)

  // An applied report is terminal: the file is in the system and re-applying it would only
  // produce a report of skipped duplicates. The drawer switches to a result view.
  const isApplied = report?.applied === true
  const canValidate = externalSource.trim() !== '' && file !== null && busy === null
  const canApply =
    report !== null && !report.applied && report.issueCount === 0 && report.createdCount > 0 && busy === null

  /** Any edit invalidates the report: it describes the previous file, and showing it next to a
   * new one is how an operator ends up approving something they did not look at. */
  function invalidateReport() {
    setReport(null)
    setError(null)
  }

  function onFileChange(event: ChangeEvent<HTMLInputElement>) {
    setFile(event.target.files?.[0] ?? null)
    invalidateReport()
  }

  async function download(fileFormat: OrderImportFormat) {
    try {
      const downloaded = await downloadOrderImportTemplate(companyId, fileFormat)
      const url = URL.createObjectURL(downloaded.blob)
      const link = document.createElement('a')
      link.href = url
      link.download = downloaded.fileName ?? `order-import-template.${fileFormat.toLowerCase()}`
      link.click()
      // Revoking immediately after the synthetic click is safe: the browser has already taken
      // its own reference to the blob by then, and not revoking leaks it for the tab's lifetime.
      URL.revokeObjectURL(url)
    } catch (downloadError) {
      notifyError(t('import.downloadError'), describeApiError(downloadError as ApiError))
    }
  }

  async function validate() {
    if (file === null) return
    setBusy('preview')
    setError(null)
    try {
      setReport(await previewOrderImport(companyId, externalSource.trim(), file))
    } catch (previewError) {
      setReport(null)
      setError(describeImportError(previewError as ApiError))
    } finally {
      setBusy(null)
    }
  }

  async function apply() {
    if (file === null || report === null) return

    const confirmed = await confirmDialog({
      title: t('import.confirmTitle'),
      text: t('import.confirmText', { count: report.createdCount }),
      confirmLabel: t('import.apply'),
    })
    if (!confirmed) return

    setBusy('apply')
    setError(null)
    try {
      const applied = await applyOrderImport(companyId, externalSource.trim(), file)
      setReport(applied)
      if (applied.applied) {
        notifySuccess(
          t('import.applied'),
          t('import.appliedText', { created: applied.createdCount, skipped: applied.skippedCount }),
        )
        onImported()
      }
    } catch (applyError) {
      setError(describeImportError(applyError as ApiError))
    } finally {
      setBusy(null)
    }
  }

  function reset() {
    setFile(null)
    setReport(null)
    setError(null)
  }

  const outcomeLabel: Record<OrderImportOutcome, string> = {
    CREATE: t('import.outcomeCreate'),
    SKIPPED_DUPLICATE: t('import.outcomeSkipped'),
    REJECTED: t('import.outcomeRejected'),
  }

  return (
    <TmsDrawer
      open
      title={t('import.title')}
      subtitle={t('import.subtitle')}
      size="xl"
      onClose={onClose}
      closeOnEscape={busy === null}
      closeOnBackdrop={busy === null}
      footer={
        <>
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy !== null}>
            {isApplied ? tc('actions.close') : tc('actions.cancel')}
          </button>
          {isApplied ? (
            <button type="button" className="btn btn-outline-primary" onClick={reset}>
              {t('import.reset')}
            </button>
          ) : (
            <>
              <button type="button" className="btn btn-outline-primary" onClick={() => void validate()} disabled={!canValidate}>
                {busy === 'preview' ? t('import.previewing') : t('import.validate')}
              </button>
              <button type="button" className="btn btn-primary" onClick={() => void apply()} disabled={!canApply}>
                {busy === 'apply' ? t('import.applying') : t('import.apply')}
              </button>
            </>
          )}
        </>
      }
    >
      {error && (
        <div className="alert alert-danger py-2 small" role="alert">
          {error}
        </div>
      )}

      <fieldset className="tms-fieldset">
        <legend className="tms-fieldset-legend">{t('import.templateSection')}</legend>
        <p className="text-body-secondary small">{t('import.templateHelp')}</p>
        <div className="d-flex flex-wrap gap-2">
          <button
            type="button"
            className="btn btn-outline-secondary btn-sm d-inline-flex align-items-center gap-2"
            onClick={() => void download('XLSX')}
          >
            <i className="bi bi-file-earmark-spreadsheet" aria-hidden="true" />
            {t('import.downloadXlsx')}
          </button>
          <button
            type="button"
            className="btn btn-outline-secondary btn-sm d-inline-flex align-items-center gap-2"
            onClick={() => void download('CSV')}
          >
            <i className="bi bi-filetype-csv" aria-hidden="true" />
            {t('import.downloadCsv')}
          </button>
        </div>
      </fieldset>

      <fieldset className="tms-fieldset" disabled={busy !== null || isApplied}>
        <legend className="tms-fieldset-legend">{t('import.fileSection')}</legend>
        <div className="row">
          <div className="col-12 col-sm-5">
            <FormField
              label={t('import.externalSource')}
              htmlFor="import-external-source"
              help={t('import.externalSourceHelp')}
              required
              error={externalSource.trim() === '' && report !== null ? tv('required') : undefined}
            >
              <input
                id="import-external-source"
                className="form-control"
                value={externalSource}
                maxLength={64}
                onChange={(event) => {
                  setExternalSource(event.target.value)
                  invalidateReport()
                }}
              />
            </FormField>
          </div>
          <div className="col-12 col-sm-7">
            <FormField
              label={t('import.file')}
              htmlFor="import-file"
              help={t('import.fileHelp', { mb: MAX_FILE_MB, rows: format.quantity(MAX_ROWS) })}
              required
            >
              <input
                id="import-file"
                type="file"
                className="form-control"
                accept=".xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv"
                onChange={onFileChange}
              />
            </FormField>
          </div>
        </div>
      </fieldset>

      {report && (
        <fieldset className="tms-fieldset mb-0">
          <legend className="tms-fieldset-legend">{t('import.previewSection')}</legend>

          <div
            className={`alert py-2 small ${
              report.applied ? 'alert-success' : report.issueCount > 0 ? 'alert-danger' : 'alert-light border'
            }`}
            role="status"
          >
            {report.applied
              ? t('import.appliedText', { created: report.createdCount, skipped: report.skippedCount })
              : report.issueCount > 0
                ? t('import.blocked')
                : report.createdCount === 0
                  ? t('import.nothingToCreate')
                  : t('import.readyToApply')}
          </div>

          <div className="row g-2 mb-3">
            {[
              { key: 'rows', label: t('import.counts.rows'), value: report.rowCount },
              { key: 'orders', label: t('import.counts.orders'), value: report.orderCount },
              { key: 'create', label: t('import.counts.create'), value: report.createdCount },
              { key: 'duplicates', label: t('import.counts.duplicates'), value: report.skippedCount },
              { key: 'rejected', label: t('import.counts.rejected'), value: report.rejectedCount },
              { key: 'issues', label: t('import.counts.issues'), value: report.issueCount },
            ].map((tile) => (
              <div className="col-6 col-sm-4 col-lg-2" key={tile.key}>
                <div className="tms-card h-100 px-3 py-2">
                  <p className="tms-section-title mb-0">{tile.label}</p>
                  <p className="mb-0 fw-semibold">{format.quantity(tile.value)}</p>
                </div>
              </div>
            ))}
          </div>

          {report.issues.length > 0 && (
            <>
              <p className="tms-section-title">{t('import.issuesTitle')}</p>
              <div className="tms-table-scroll mb-3">
                <table className="table table-sm align-middle">
                  <caption className="visually-hidden">{t('import.issuesTitle')}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t('import.columnRow')}</th>
                      <th scope="col">{t('import.columnColumn')}</th>
                      <th scope="col">{t('import.columnReference')}</th>
                      <th scope="col">{t('import.columnMessage')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.issues.map((issue, index) => (
                      <tr key={`${issue.rowNumber}-${issue.column ?? ''}-${index}`}>
                        <td className="tms-cell-strong">{issue.rowNumber}</td>
                        <td className="tms-code">{issue.column ?? '—'}</td>
                        <td className="tms-code">{issue.externalReference ?? '—'}</td>
                        <td>{issue.message}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {report.issuesTruncated && (
                <p className="text-body-secondary small">
                  {t('import.issuesTruncated', { shown: report.issues.length, total: report.issueCount })}
                </p>
              )}
            </>
          )}

          {report.orders.length > 0 && (
            <>
              <p className="tms-section-title">{t('import.ordersTitle')}</p>
              <div className="tms-table-scroll">
                <table className="table table-sm align-middle">
                  <caption className="visually-hidden">{t('import.ordersTitle')}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t('import.columnOutcome')}</th>
                      <th scope="col">{t('import.columnReference')}</th>
                      <th scope="col">{tc('columns.orderNumber')}</th>
                      <th scope="col">{tc('columns.origin')}</th>
                      <th scope="col">{tc('columns.destination')}</th>
                      <th scope="col">{tc('columns.requiredDate')}</th>
                      <th scope="col">{tc('columns.priority')}</th>
                      <th scope="col" className="text-end">
                        {tc('columns.lines')}
                      </th>
                      <th scope="col" className="text-end">
                        {tc('columns.weight')}
                      </th>
                      <th scope="col" className="text-end">
                        {tc('columns.volume')}
                      </th>
                      <th scope="col" className="text-end">
                        {tc('columns.pallets')}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.orders.map((order) => (
                      <tr key={order.externalReference}>
                        <td>
                          <StatusBadge label={outcomeLabel[order.outcome]} tone={OUTCOME_TONE[order.outcome]} />
                        </td>
                        <td className="tms-code">{order.externalReference}</td>
                        <td className="tms-code">{order.orderNumber ?? '—'}</td>
                        <td className="tms-truncate" title={order.originName ?? undefined}>
                          {order.originName ?? order.originCode ?? '—'}
                        </td>
                        <td className="tms-truncate" title={order.destinationName ?? undefined}>
                          {order.destinationName ?? order.destinationCode ?? '—'}
                        </td>
                        <td>{format.date(order.serviceDate)}</td>
                        <td>{enumLabels.orderPriority(order.priority)}</td>
                        <td className="text-end">{format.quantity(order.lineCount)}</td>
                        <td className="text-end">{format.weight(order.totalWeightKg)}</td>
                        <td className="text-end">{format.volume(order.totalVolumeM3)}</td>
                        <td className="text-end">{format.decimal(order.totalPallets)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </fieldset>
      )}
    </TmsDrawer>
  )
}
