import { apiDownload, apiUpload, type DownloadedFile } from './httpClient'

/**
 * Generic client for every bulk master-data import endpoint (Locations, Carriers, Vehicle
 * Types, Vehicles - the order import at `ordersApi.ts` predates this and keeps its own
 * `OrderImportReport` shape, which these mirror).
 *
 * Every import endpoint shares the same three-call shape: `GET {base}/template`,
 * `POST {base}/preview` (writes nothing), `POST {base}` (applies). `basePath` is the one thing
 * that differs between entities, e.g. `/masterdata/locations/import` or `/fleet/carriers/import`.
 */

/** Mirrors the backend's `ImportFormat` enum. */
export type ImportFormat = 'XLSX' | 'CSV'

/** Mirrors `ImportOutcome` - what the import decided about one row. */
export type ImportOutcome = 'CREATE' | 'SKIPPED_DUPLICATE' | 'REJECTED'

/** Mirrors `ImportIssue` - one reason a row cannot be accepted. */
export interface ImportIssue {
  rowNumber: number
  column: string | null
  identifier: string | null
  message: string
}

/**
 * Mirrors the backend's `ImportReport<T>`. One shape for the preview and for the applied
 * result, so the table an operator approves and the confirmation they get are the same
 * rendering - `applied` is the only thing that differs. `items` is generic per entity (e.g.
 * `LocationImportPreview[]`).
 *
 * A file with issues comes back as HTTP 200 with `applied: false` and nothing written, so a
 * caller must branch on `applied` rather than on the request having succeeded.
 */
export interface ImportReport<T> {
  dryRun: boolean
  applied: boolean
  batchId: string | null
  fileName: string | null
  format: ImportFormat
  rowCount: number
  itemCount: number
  createdCount: number
  skippedCount: number
  rejectedCount: number
  issueCount: number
  issuesTruncated: boolean
  items: T[]
  issues: ImportIssue[]
}

export function downloadImportTemplate(
  basePath: string,
  companyId: string,
  format: ImportFormat,
  signal?: AbortSignal,
): Promise<DownloadedFile> {
  return apiDownload(`${basePath}/template`, { companyId, query: { format }, signal })
}

function importForm(file: File): FormData {
  const form = new FormData()
  form.append('file', file)
  return form
}

/** Validates the file and reports what applying it would do. Writes nothing. */
export function previewImport<T>(
  basePath: string,
  companyId: string,
  file: File,
  signal?: AbortSignal,
): Promise<ImportReport<T>> {
  return apiUpload<ImportReport<T>>(`${basePath}/preview`, { companyId, formData: importForm(file), signal })
}

/** Applies the file in one transaction, or writes nothing at all if any row has an issue. */
export function applyImport<T>(basePath: string, companyId: string, file: File): Promise<ImportReport<T>> {
  return apiUpload<ImportReport<T>>(basePath, { companyId, formData: importForm(file) })
}
