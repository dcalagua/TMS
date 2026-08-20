import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { OrderImportReport } from '../../shared/api/ordersApi'
import { OrderImportDrawer } from './OrderImportDrawer'

const ordersApiMocks = vi.hoisted(() => ({
  downloadOrderImportTemplate: vi.fn(),
  previewOrderImport: vi.fn(),
  applyOrderImport: vi.fn(),
}))
vi.mock('../../shared/api/ordersApi', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api/ordersApi')>('../../shared/api/ordersApi')
  return { ...actual, ...ordersApiMocks }
})

const alertMocks = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  confirmAction: vi.fn().mockResolvedValue(true),
}))
vi.mock('../../shared/ui/alerts', () => alertMocks)

const BASE_REPORT: OrderImportReport = {
  dryRun: true,
  applied: false,
  batchId: null,
  fileName: 'orders.csv',
  format: 'CSV',
  externalSource: 'WMS',
  rowCount: 2,
  orderCount: 1,
  createdCount: 1,
  skippedCount: 0,
  rejectedCount: 0,
  issueCount: 0,
  issuesTruncated: false,
  orders: [
    {
      externalReference: 'EXT-1',
      outcome: 'CREATE',
      firstRowNumber: 2,
      originCode: 'LIM-01',
      originName: 'Lima warehouse',
      destinationCode: 'ST-100',
      destinationName: 'Store 100',
      customerName: 'Acme',
      serviceDate: '2026-03-01',
      priority: 'NORMAL',
      lineCount: 2,
      totalWeightKg: 120,
      totalVolumeM3: 1.5,
      totalPallets: 2,
      totalsSource: 'CALCULATED',
      orderNumber: null,
    },
  ],
  issues: [],
}

function renderDrawer(props: Partial<Parameters<typeof OrderImportDrawer>[0]> = {}) {
  return render(
    <OrderImportDrawer companyId="company-1" onClose={vi.fn()} onImported={vi.fn()} {...props} />,
  )
}

/** Fills the two required inputs so the Validate action becomes reachable. */
async function fillFile(name = 'orders.csv') {
  await userEvent.type(screen.getByLabelText(/^sistema de origen/i), 'WMS')
  await userEvent.upload(
    screen.getByLabelText(/^archivo/i),
    new File(['external_reference\nEXT-1'], name, { type: 'text/csv' }),
  )
}

afterEach(() => {
  vi.clearAllMocks()
  alertMocks.confirmAction.mockResolvedValue(true)
})

describe('OrderImportDrawer', () => {
  it('cannot validate or import before a source and a file are given', () => {
    renderDrawer()

    expect(screen.getByRole('button', { name: 'Validar el archivo' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Importar los pedidos' })).toBeDisabled()
  })

  it('previews the file without importing anything', async () => {
    ordersApiMocks.previewOrderImport.mockResolvedValue(BASE_REPORT)
    renderDrawer()

    await fillFile()
    await userEvent.click(screen.getByRole('button', { name: 'Validar el archivo' }))

    await waitFor(() => expect(ordersApiMocks.previewOrderImport).toHaveBeenCalledWith(
      'company-1', 'WMS', expect.any(File),
    ))
    expect(ordersApiMocks.applyOrderImport).not.toHaveBeenCalled()
    expect(await screen.findByText(/Todavía no se ha guardado nada/)).toBeInTheDocument()
    expect(screen.getByText('EXT-1')).toBeInTheDocument()
    expect(screen.getByText('Store 100')).toBeInTheDocument()
  })

  it('refuses to import a file with issues and lists the offending rows', async () => {
    ordersApiMocks.previewOrderImport.mockResolvedValue({
      ...BASE_REPORT,
      createdCount: 0,
      rejectedCount: 1,
      issueCount: 2,
      orders: [{ ...BASE_REPORT.orders[0]!, outcome: 'REJECTED' }],
      issues: [
        { rowNumber: 2, column: 'origin_code', externalReference: 'EXT-1', message: 'Unknown origin NOPE.' },
        { rowNumber: 3, column: 'quantity', externalReference: 'EXT-1', message: 'Quantity must be positive.' },
      ],
    })
    renderDrawer()

    await fillFile()
    await userEvent.click(screen.getByRole('button', { name: 'Validar el archivo' }))

    expect(await screen.findByText(/no se importó nada/)).toBeInTheDocument()
    expect(screen.getByText('Unknown origin NOPE.')).toBeInTheDocument()
    expect(screen.getByText('Quantity must be positive.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Importar los pedidos' })).toBeDisabled()
  })

  it('applies the file after an explicit confirmation and reports what was created', async () => {
    ordersApiMocks.previewOrderImport.mockResolvedValue(BASE_REPORT)
    ordersApiMocks.applyOrderImport.mockResolvedValue({
      ...BASE_REPORT,
      dryRun: false,
      applied: true,
      batchId: 'batch-1',
      orders: [{ ...BASE_REPORT.orders[0]!, orderNumber: 'TO-00000042' }],
    })
    const onImported = vi.fn()
    renderDrawer({ onImported })

    await fillFile()
    await userEvent.click(screen.getByRole('button', { name: 'Validar el archivo' }))
    await screen.findByText(/Todavía no se ha guardado nada/)
    await userEvent.click(screen.getByRole('button', { name: 'Importar los pedidos' }))

    await waitFor(() => expect(ordersApiMocks.applyOrderImport).toHaveBeenCalledWith(
      'company-1', 'WMS', expect.any(File),
    ))
    expect(alertMocks.confirmAction).toHaveBeenCalled()
    expect(onImported).toHaveBeenCalled()
    expect(await screen.findByText('TO-00000042')).toBeInTheDocument()
  })

  it('does not import when the confirmation is dismissed', async () => {
    ordersApiMocks.previewOrderImport.mockResolvedValue(BASE_REPORT)
    alertMocks.confirmAction.mockResolvedValue(false)
    renderDrawer()

    await fillFile()
    await userEvent.click(screen.getByRole('button', { name: 'Validar el archivo' }))
    await screen.findByText(/Todavía no se ha guardado nada/)
    await userEvent.click(screen.getByRole('button', { name: 'Importar los pedidos' }))

    await waitFor(() => expect(alertMocks.confirmAction).toHaveBeenCalled())
    expect(ordersApiMocks.applyOrderImport).not.toHaveBeenCalled()
  })

  it('offers no import when every reference in the file already exists', async () => {
    // Re-uploading a file that was already imported: idempotency makes this a no-op, not an
    // error, so the drawer explains it rather than offering an action that would do nothing.
    ordersApiMocks.previewOrderImport.mockResolvedValue({
      ...BASE_REPORT,
      createdCount: 0,
      skippedCount: 1,
      orders: [{ ...BASE_REPORT.orders[0]!, outcome: 'SKIPPED_DUPLICATE' }],
    })
    renderDrawer()

    await fillFile()
    await userEvent.click(screen.getByRole('button', { name: 'Validar el archivo' }))

    expect(await screen.findByText(/todas las referencias del archivo ya existen/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Importar los pedidos' })).toBeDisabled()
    expect(screen.getByText('Ya existe')).toBeInTheDocument()
  })

  it('drops a stale report when the file is changed', async () => {
    ordersApiMocks.previewOrderImport.mockResolvedValue(BASE_REPORT)
    renderDrawer()

    await fillFile()
    await userEvent.click(screen.getByRole('button', { name: 'Validar el archivo' }))
    await screen.findByText(/Todavía no se ha guardado nada/)

    await userEvent.upload(
      screen.getByLabelText(/^archivo/i),
      new File(['other'], 'other.csv', { type: 'text/csv' }),
    )

    expect(screen.queryByText(/Todavía no se ha guardado nada/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Importar los pedidos' })).toBeDisabled()
  })

  it('surfaces a rejected upload as a form error', async () => {
    // A whole-file refusal: `OrderImportService` writes these for the operator, so the drawer
    // shows the backend's own sentence rather than the generic per-code copy.
    ordersApiMocks.previewOrderImport.mockRejectedValue({
      code: 'malformed-request',
      problem: { detail: 'The file is larger than 2 MB.' },
    })
    renderDrawer()

    await fillFile('huge.xlsx')
    await userEvent.click(screen.getByRole('button', { name: 'Validar el archivo' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The file is larger than 2 MB.')
  })

  it('downloads the template in both formats', async () => {
    const blob = new Blob(['x'])
    ordersApiMocks.downloadOrderImportTemplate.mockResolvedValue({ blob, fileName: 'tms-order-import.xlsx' })
    const createObjectURL = vi.fn().mockReturnValue('blob:url')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })

    renderDrawer()
    await userEvent.click(screen.getByRole('button', { name: 'Plantilla XLSX' }))
    await waitFor(() => expect(ordersApiMocks.downloadOrderImportTemplate)
      .toHaveBeenCalledWith('company-1', 'XLSX'))

    await userEvent.click(screen.getByRole('button', { name: 'Plantilla CSV' }))
    await waitFor(() => expect(ordersApiMocks.downloadOrderImportTemplate)
      .toHaveBeenCalledWith('company-1', 'CSV'))

    expect(revokeObjectURL).toHaveBeenCalledWith('blob:url')
    vi.unstubAllGlobals()
  })
})
