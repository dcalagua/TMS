import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ImportReport } from '../../api/importApi'
import { ImportDrawer, type ImportDrawerStrings } from './ImportDrawer'

const importApiMocks = vi.hoisted(() => ({
  downloadImportTemplate: vi.fn(),
  previewImport: vi.fn(),
  applyImport: vi.fn(),
}))
vi.mock('../../api/importApi', async () => {
  const actual = await vi.importActual<typeof import('../../api/importApi')>('../../api/importApi')
  return { ...actual, ...importApiMocks }
})

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn().mockResolvedValue(true),
}))
vi.mock('../alerts', () => alertMocks)

interface Item {
  code: string
  outcome: 'CREATE' | 'SKIPPED_DUPLICATE' | 'REJECTED'
  name: string
}

const STRINGS: ImportDrawerStrings = {
  title: 'Import things',
  subtitle: 'Bulk upload',
  templateSection: 'Template',
  templateHelp: 'Download it first',
  downloadXlsx: 'XLSX template',
  downloadCsv: 'CSV template',
  downloadError: 'Could not download',
  fileSection: 'File',
  file: 'File',
  fileHelp: (mb, rows) => `Up to ${mb} MB, ${rows} rows`,
  previewSection: 'Preview',
  validate: 'Validate the file',
  previewing: 'Validating...',
  apply: 'Import the things',
  applying: 'Importing...',
  applied: (created, skipped) => `${created} created, ${skipped} skipped`,
  confirmTitle: 'Import things?',
  confirmText: (count) => `${count} things will be created`,
  blocked: 'Fix the errors below',
  readyToApply: 'Nothing saved yet',
  nothingToCreate: 'Nothing new to create',
  reset: 'Start over',
  issuesTitle: 'Problems',
  issuesTruncated: (shown, total) => `Showing ${shown} of ${total}`,
  downloadIssuesReport: 'Download error report',
  itemsTitle: 'Things in the file',
  columnRow: 'Row',
  columnColumn: 'Column',
  columnIdentifier: 'Code',
  columnMessage: 'Message',
  countRows: 'Rows',
  countItems: 'Items',
  countCreate: 'Create',
  countDuplicates: 'Duplicates',
  countRejected: 'Rejected',
  countIssues: 'Issues',
  outcomeCreate: 'Will be created',
  outcomeSkipped: 'Already exists',
  outcomeRejected: 'Rejected',
  cancel: 'Cancel',
  close: 'Close',
}

const BASE_REPORT: ImportReport<Item> = {
  dryRun: true,
  applied: false,
  batchId: null,
  fileName: 'things.csv',
  format: 'CSV',
  rowCount: 1,
  itemCount: 1,
  createdCount: 1,
  skippedCount: 0,
  rejectedCount: 0,
  issueCount: 0,
  issuesTruncated: false,
  items: [{ code: 'A1', outcome: 'CREATE', name: 'Alpha' }],
  issues: [],
}

function renderDrawer(props: Partial<Parameters<typeof ImportDrawer<Item>>[0]> = {}) {
  return render(
    <ImportDrawer<Item>
      apiBasePath="/things/import"
      companyId="company-1"
      strings={STRINGS}
      onClose={vi.fn()}
      onImported={vi.fn()}
      renderItems={(items) => (
        <ul>
          {items.map((item) => (
            <li key={item.code}>{item.name}</li>
          ))}
        </ul>
      )}
      {...props}
    />,
  )
}

async function uploadFile(name = 'things.csv') {
  await userEvent.upload(screen.getByLabelText(/^File/i), new File(['code\nA1'], name, { type: 'text/csv' }))
}

afterEach(() => {
  vi.clearAllMocks()
  alertMocks.confirmAction.mockResolvedValue(true)
})

describe('ImportDrawer', () => {
  it('cannot validate before a file is chosen', () => {
    renderDrawer()
    expect(screen.getByRole('button', { name: 'Validate the file' })).toBeDisabled()
  })

  it('previews the file without importing anything', async () => {
    importApiMocks.previewImport.mockResolvedValue(BASE_REPORT)
    renderDrawer()

    await uploadFile()
    await userEvent.click(screen.getByRole('button', { name: 'Validate the file' }))

    await waitFor(() =>
      expect(importApiMocks.previewImport).toHaveBeenCalledWith('/things/import', 'company-1', expect.any(File)),
    )
    expect(importApiMocks.applyImport).not.toHaveBeenCalled()
    expect(await screen.findByText('Nothing saved yet')).toBeInTheDocument()
    expect(screen.getByText('Alpha')).toBeInTheDocument()
  })

  it('refuses to import a file with issues and lists them', async () => {
    importApiMocks.previewImport.mockResolvedValue({
      ...BASE_REPORT,
      createdCount: 0,
      rejectedCount: 1,
      issueCount: 1,
      items: [{ ...BASE_REPORT.items[0]!, outcome: 'REJECTED' }],
      issues: [{ rowNumber: 2, column: 'code', identifier: null, message: 'A code is required.' }],
    })
    renderDrawer()

    await uploadFile()
    await userEvent.click(screen.getByRole('button', { name: 'Validate the file' }))

    expect(await screen.findByText('Fix the errors below')).toBeInTheDocument()
    expect(screen.getByText('A code is required.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Import the things' })).toBeDisabled()
  })

  it('offers an error report download when there are issues, and none when there are not', async () => {
    importApiMocks.previewImport.mockResolvedValue({
      ...BASE_REPORT,
      createdCount: 0,
      rejectedCount: 1,
      issueCount: 1,
      items: [{ ...BASE_REPORT.items[0]!, outcome: 'REJECTED' }],
      issues: [{ rowNumber: 2, column: 'code', identifier: null, message: 'A code is required.' }],
    })
    const createObjectURL = vi.fn().mockReturnValue('blob:url')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })

    renderDrawer()
    await uploadFile()
    await userEvent.click(screen.getByRole('button', { name: 'Validate the file' }))
    await screen.findByText('Fix the errors below')

    await userEvent.click(screen.getByRole('button', { name: 'Download error report' }))
    expect(createObjectURL).toHaveBeenCalled()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:url')
    vi.unstubAllGlobals()
  })

  it('offers no error report download when the file is clean', async () => {
    importApiMocks.previewImport.mockResolvedValue(BASE_REPORT)
    renderDrawer()

    await uploadFile()
    await userEvent.click(screen.getByRole('button', { name: 'Validate the file' }))
    await screen.findByText('Nothing saved yet')

    expect(screen.queryByRole('button', { name: 'Download error report' })).not.toBeInTheDocument()
  })

  it('applies the file after confirmation and reports the outcome', async () => {
    importApiMocks.previewImport.mockResolvedValue(BASE_REPORT)
    importApiMocks.applyImport.mockResolvedValue({ ...BASE_REPORT, dryRun: false, applied: true, batchId: 'b1' })
    const onImported = vi.fn()
    renderDrawer({ onImported })

    await uploadFile()
    await userEvent.click(screen.getByRole('button', { name: 'Validate the file' }))
    await screen.findByText('Nothing saved yet')
    await userEvent.click(screen.getByRole('button', { name: 'Import the things' }))

    await waitFor(() =>
      expect(importApiMocks.applyImport).toHaveBeenCalledWith('/things/import', 'company-1', expect.any(File)),
    )
    expect(alertMocks.confirmAction).toHaveBeenCalled()
    expect(onImported).toHaveBeenCalled()
    expect(await screen.findByText('1 created, 0 skipped')).toBeInTheDocument()
  })

  it('does not import when the confirmation is dismissed', async () => {
    importApiMocks.previewImport.mockResolvedValue(BASE_REPORT)
    alertMocks.confirmAction.mockResolvedValue(false)
    renderDrawer()

    await uploadFile()
    await userEvent.click(screen.getByRole('button', { name: 'Validate the file' }))
    await screen.findByText('Nothing saved yet')
    await userEvent.click(screen.getByRole('button', { name: 'Import the things' }))

    await waitFor(() => expect(alertMocks.confirmAction).toHaveBeenCalled())
    expect(importApiMocks.applyImport).not.toHaveBeenCalled()
  })

  it('drops a stale report when the file is changed', async () => {
    importApiMocks.previewImport.mockResolvedValue(BASE_REPORT)
    renderDrawer()

    await uploadFile()
    await userEvent.click(screen.getByRole('button', { name: 'Validate the file' }))
    await screen.findByText('Nothing saved yet')

    await userEvent.upload(screen.getByLabelText(/^File/i), new File(['other'], 'other.csv', { type: 'text/csv' }))

    expect(screen.queryByText('Nothing saved yet')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Import the things' })).toBeDisabled()
  })

  it('downloads the template in both formats', async () => {
    const blob = new Blob(['x'])
    importApiMocks.downloadImportTemplate.mockResolvedValue({ blob, fileName: 'template.xlsx' })
    const createObjectURL = vi.fn().mockReturnValue('blob:url')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })

    renderDrawer()
    await userEvent.click(screen.getByRole('button', { name: 'XLSX template' }))
    await waitFor(() =>
      expect(importApiMocks.downloadImportTemplate).toHaveBeenCalledWith('/things/import', 'company-1', 'XLSX'),
    )

    await userEvent.click(screen.getByRole('button', { name: 'CSV template' }))
    await waitFor(() =>
      expect(importApiMocks.downloadImportTemplate).toHaveBeenCalledWith('/things/import', 'company-1', 'CSV'),
    )

    expect(revokeObjectURL).toHaveBeenCalledWith('blob:url')
    vi.unstubAllGlobals()
  })
})
