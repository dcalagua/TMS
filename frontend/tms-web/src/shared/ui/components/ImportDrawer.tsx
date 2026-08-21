import { useState, type ChangeEvent, type ReactNode } from 'react'
import type { ApiError } from '../../api/httpClient'
import {
  applyImport,
  downloadImportTemplate,
  previewImport,
  type ImportFormat,
  type ImportOutcome,
  type ImportReport,
} from '../../api/importApi'
import { describeApiError, describeImportError } from '../../api/problemMessages'
import { notifyError, notifySuccess } from '../alerts'
import { FormField } from './FormField'
import { TmsDrawer } from './TmsDrawer'
import { confirmDialog } from './ConfirmDialog'

/** Every string the drawer renders, pre-translated by the caller - keeps this component free of
 * any particular i18n namespace so it stays reusable across Locations, Carriers, Vehicle Types
 * and Vehicles (and any future entity) without those entities sharing one translation namespace. */
export interface ImportDrawerStrings {
  title: string
  subtitle: string
  templateSection: string
  templateHelp: string
  downloadXlsx: string
  downloadCsv: string
  downloadError: string
  fileSection: string
  file: string
  fileHelp: (maxFileMb: number, maxRows: number) => string
  previewSection: string
  validate: string
  previewing: string
  apply: string
  applying: string
  applied: (created: number, skipped: number) => string
  confirmTitle: string
  confirmText: (createdCount: number) => string
  blocked: string
  readyToApply: string
  nothingToCreate: string
  reset: string
  issuesTitle: string
  issuesTruncated: (shown: number, total: number) => string
  downloadIssuesReport: string
  itemsTitle: string
  columnRow: string
  columnColumn: string
  columnIdentifier: string
  columnMessage: string
  countRows: string
  countItems: string
  countCreate: string
  countDuplicates: string
  countRejected: string
  countIssues: string
  outcomeCreate: string
  outcomeSkipped: string
  outcomeRejected: string
  cancel: string
  close: string
}

interface ImportDrawerProps<T> {
  /** e.g. `/masterdata/locations/import` - the controller's `@RequestMapping`. */
  apiBasePath: string
  companyId: string
  strings: ImportDrawerStrings
  onClose: () => void
  /** Called once after an applied import, so the list behind the drawer reloads. */
  onImported: () => void
  /** Renders the entity-specific preview table for one report's items. */
  renderItems: (items: T[], outcomeLabel: (outcome: ImportOutcome) => string) => ReactNode
  /** Shown in the file field's help text. Mirrors the backend's `ImportLimits`; not enforced
   * here - the server rejects an oversized file regardless. */
  maxFileMb?: number
  maxRows?: number
}

/**
 * The bulk master-data import, as three steps in one drawer: download the template, upload a
 * filled copy, read what it would do - and only then apply it. Generalizes
 * `pages/orders/OrderImportDrawer.tsx` so Locations, Carriers, Vehicle Types and Vehicles share
 * one drawer instead of four near-identical copies of it; only the entity-specific preview
 * columns (via {@link ImportDrawerProps.renderItems}) and the API base path differ.
 *
 * The preview is not a convenience: the backend refuses a file with any invalid row outright, so
 * without a dry run an operator's only way to find out what is wrong would be to attempt the
 * import and read the refusal. Applying is gated behind an explicit second action and a
 * confirmation, never fused into the upload: uploading is inspection, importing is a decision.
 */
export function ImportDrawer<T>({
  apiBasePath,
  companyId,
  strings: s,
  onClose,
  onImported,
  renderItems,
  maxFileMb = 2,
  maxRows = 5000,
}: ImportDrawerProps<T>) {
  const [file, setFile] = useState<File | null>(null)
  const [report, setReport] = useState<ImportReport<T> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<'preview' | 'apply' | null>(null)

  // An applied report is terminal: the file is in the system and re-applying it would only
  // produce a report of skipped duplicates. The drawer switches to a result view.
  const isApplied = report?.applied === true
  const canValidate = file !== null && busy === null
  const canApply =
    report !== null && !report.applied && report.issueCount === 0 && report.createdCount > 0 && busy === null

  const outcomeLabel: Record<ImportOutcome, string> = {
    CREATE: s.outcomeCreate,
    SKIPPED_DUPLICATE: s.outcomeSkipped,
    REJECTED: s.outcomeRejected,
  }

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

  async function download(fileFormat: ImportFormat) {
    try {
      const downloaded = await downloadImportTemplate(apiBasePath, companyId, fileFormat)
      const url = URL.createObjectURL(downloaded.blob)
      const link = document.createElement('a')
      link.href = url
      link.download = downloaded.fileName ?? `import-template.${fileFormat.toLowerCase()}`
      link.click()
      // Revoking immediately after the synthetic click is safe: the browser has already taken
      // its own reference to the blob by then, and not revoking leaks it for the tab's lifetime.
      URL.revokeObjectURL(url)
    } catch (downloadError) {
      notifyError(s.downloadError, describeApiError(downloadError as ApiError))
    }
  }

  /** Every row's problem, as a CSV built client-side: the issues already sit in `report`, so
   * there is nothing to fetch. Lets an operator hand the file to whoever fixes it without
   * reading the drawer's table row by row. */
  function downloadIssuesCsv() {
    if (report === null || report.issues.length === 0) return
    const header = [s.columnRow, s.columnColumn, s.columnIdentifier, s.columnMessage]
    const csvEscape = (value: string) => (/[",\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value)
    const rows = report.issues.map((issue) =>
      [String(issue.rowNumber), issue.column ?? '', issue.identifier ?? '', issue.message].map(csvEscape).join(','),
    )
    // A UTF-8 BOM so Excel on Windows opens accented text correctly, matching the templates.
    // Written as a code point rather than the literal character, invisible in a source file.
    const csv = String.fromCharCode(0xfeff) + [header.map(csvEscape).join(','), ...rows].join('\r\n')
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `${(report.fileName ?? 'import').replace(/\.[^.]+$/, '')}-errors.csv`
    link.click()
    URL.revokeObjectURL(url)
  }

  async function validate() {
    if (file === null) return
    setBusy('preview')
    setError(null)
    try {
      setReport(await previewImport<T>(apiBasePath, companyId, file))
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
      title: s.confirmTitle,
      text: s.confirmText(report.createdCount),
      confirmLabel: s.apply,
    })
    if (!confirmed) return

    setBusy('apply')
    setError(null)
    try {
      const applied = await applyImport<T>(apiBasePath, companyId, file)
      setReport(applied)
      if (applied.applied) {
        notifySuccess(s.applied(applied.createdCount, applied.skippedCount))
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

  return (
    <TmsDrawer
      open
      title={s.title}
      subtitle={s.subtitle}
      size="xl"
      onClose={onClose}
      closeOnEscape={busy === null}
      closeOnBackdrop={busy === null}
      footer={
        <>
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy !== null}>
            {isApplied ? s.close : s.cancel}
          </button>
          {isApplied ? (
            <button type="button" className="btn btn-outline-primary" onClick={reset}>
              {s.reset}
            </button>
          ) : (
            <>
              <button type="button" className="btn btn-outline-primary" onClick={() => void validate()} disabled={!canValidate}>
                {busy === 'preview' ? s.previewing : s.validate}
              </button>
              <button type="button" className="btn btn-primary" onClick={() => void apply()} disabled={!canApply}>
                {busy === 'apply' ? s.applying : s.apply}
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
        <legend className="tms-fieldset-legend">{s.templateSection}</legend>
        <p className="text-body-secondary small">{s.templateHelp}</p>
        <div className="d-flex flex-wrap gap-2">
          <button
            type="button"
            className="btn btn-outline-secondary btn-sm d-inline-flex align-items-center gap-2"
            onClick={() => void download('XLSX')}
          >
            <i className="bi bi-file-earmark-spreadsheet" aria-hidden="true" />
            {s.downloadXlsx}
          </button>
          <button
            type="button"
            className="btn btn-outline-secondary btn-sm d-inline-flex align-items-center gap-2"
            onClick={() => void download('CSV')}
          >
            <i className="bi bi-filetype-csv" aria-hidden="true" />
            {s.downloadCsv}
          </button>
        </div>
      </fieldset>

      <fieldset className="tms-fieldset" disabled={busy !== null || isApplied}>
        <legend className="tms-fieldset-legend">{s.fileSection}</legend>
        <FormField label={s.file} htmlFor="import-file" help={s.fileHelp(maxFileMb, maxRows)} required>
          <input
            id="import-file"
            type="file"
            className="form-control"
            accept=".xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv"
            onChange={onFileChange}
          />
        </FormField>
      </fieldset>

      {report && (
        <fieldset className="tms-fieldset mb-0">
          <legend className="tms-fieldset-legend">{s.previewSection}</legend>

          <div
            className={`alert py-2 small ${
              report.applied ? 'alert-success' : report.issueCount > 0 ? 'alert-danger' : 'alert-light border'
            }`}
            role="status"
          >
            {report.applied
              ? s.applied(report.createdCount, report.skippedCount)
              : report.issueCount > 0
                ? s.blocked
                : report.createdCount === 0
                  ? s.nothingToCreate
                  : s.readyToApply}
          </div>

          <div className="row g-2 mb-3">
            {[
              { key: 'rows', label: s.countRows, value: report.rowCount },
              { key: 'items', label: s.countItems, value: report.itemCount },
              { key: 'create', label: s.countCreate, value: report.createdCount },
              { key: 'duplicates', label: s.countDuplicates, value: report.skippedCount },
              { key: 'rejected', label: s.countRejected, value: report.rejectedCount },
              { key: 'issues', label: s.countIssues, value: report.issueCount },
            ].map((tile) => (
              <div className="col-6 col-sm-4 col-lg-2" key={tile.key}>
                <div className="tms-card h-100 px-3 py-2">
                  <p className="tms-section-title mb-0">{tile.label}</p>
                  <p className="mb-0 fw-semibold">{tile.value}</p>
                </div>
              </div>
            ))}
          </div>

          {report.issues.length > 0 && (
            <>
              <div className="d-flex flex-wrap align-items-center justify-content-between gap-2">
                <p className="tms-section-title mb-0">{s.issuesTitle}</p>
                <button
                  type="button"
                  className="btn btn-outline-secondary btn-sm d-inline-flex align-items-center gap-2"
                  onClick={downloadIssuesCsv}
                >
                  <i className="bi bi-download" aria-hidden="true" />
                  {s.downloadIssuesReport}
                </button>
              </div>
              <div className="tms-table-scroll mb-3">
                <table className="table table-sm align-middle">
                  <caption className="visually-hidden">{s.issuesTitle}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{s.columnRow}</th>
                      <th scope="col">{s.columnColumn}</th>
                      <th scope="col">{s.columnIdentifier}</th>
                      <th scope="col">{s.columnMessage}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.issues.map((issue, index) => (
                      <tr key={`${issue.rowNumber}-${issue.column ?? ''}-${index}`}>
                        <td className="tms-cell-strong">{issue.rowNumber}</td>
                        <td className="tms-code">{issue.column ?? '—'}</td>
                        <td className="tms-code">{issue.identifier ?? '—'}</td>
                        <td>{issue.message}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {report.issuesTruncated && (
                <p className="text-body-secondary small">{s.issuesTruncated(report.issues.length, report.issueCount)}</p>
              )}
            </>
          )}

          {report.items.length > 0 && (
            <>
              <p className="tms-section-title">{s.itemsTitle}</p>
              {renderItems(report.items, (outcome) => outcomeLabel[outcome])}
            </>
          )}
        </fieldset>
      )}
    </TmsDrawer>
  )
}
