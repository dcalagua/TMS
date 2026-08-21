import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { DeliveryDrawer, type DeliveryValues } from './DeliveryDrawer'
import { orderDelivery } from './tripFixtures'

/**
 * The form that records what a customer actually received.
 *
 * <p>What is worth testing here is the *shape* of it, because the shape is the rule made visible:
 * a result where nobody was present has no receiver to name, one where nothing was attempted has no
 * time, and anything short of a clean delivery has to be explained. The server enforces all three -
 * these assertions are about not asking an operator to fill in a box that would then be rejected.
 */

function renderDrawer(existing?: ReturnType<typeof orderDelivery>) {
  const onSubmit = vi.fn<(values: DeliveryValues) => Promise<void>>().mockResolvedValue(undefined)
  const onClose = vi.fn()
  render(
    <DeliveryDrawer
      stopLabel="1. Tienda Uno"
      orderNumber="ORD-00000001"
      existing={existing}
      onClose={onClose}
      onSubmit={onSubmit}
    />,
  )
  return { onSubmit, onClose }
}

/** `Select` is a button + listbox, not a native `<select>`: open it, then click the option. */
async function pickResult(optionName: string) {
  await userEvent.click(screen.getByRole('combobox', { name: /Resultado/ }))
  await userEvent.click(await screen.findByRole('option', { name: optionName }))
}

describe('DeliveryDrawer', () => {
  it('asks for the handover time and the receiver of an ordinary delivery', () => {
    renderDrawer()

    expect(screen.getByLabelText(/Fecha y hora de entrega/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Recibido por/)).toBeInTheDocument()
  })

  it('drops the time and the receiver when nothing was attempted', async () => {
    renderDrawer()

    await pickResult('No intentada')

    // Neither field is merely optional here - both are refused by the API, so the form does not
    // offer them at all.
    expect(screen.queryByLabelText(/Fecha y hora de entrega/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/Recibido por/)).not.toBeInTheDocument()
  })

  it('keeps the time but drops the receiver on a failed attempt: nobody received anything', async () => {
    renderDrawer()

    await pickResult('Entrega fallida')

    expect(screen.getByLabelText(/Fecha y hora de entrega/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/Recibido por/)).not.toBeInTheDocument()
  })

  it('refuses to submit a rejection with no explanation', async () => {
    const { onSubmit } = renderDrawer()

    await pickResult('Rechazado')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar entrega' }))

    expect(await screen.findByText('Explica qué ocurrió con la mercancía.')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('refuses to submit a delivery with no handover time', async () => {
    const { onSubmit } = renderDrawer()

    await userEvent.click(screen.getByRole('button', { name: 'Guardar entrega' }))

    expect(await screen.findByText('Indica cuándo cambió de manos la mercancía.')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('sends the whole state of the delivery, clearing what the chosen result has no room for', async () => {
    const { onSubmit } = renderDrawer()

    // `fireEvent` and not `userEvent.type` for the datetime input: a `datetime-local` field is
    // typed segment by segment in a real browser, and jsdom does not model that.
    fireEvent.change(screen.getByLabelText(/Fecha y hora de entrega/), { target: { value: '2026-08-20T11:30' } })
    await userEvent.type(screen.getByLabelText(/Recibido por/), 'R. Diaz')
    // Chosen last, on purpose: the receiver typed a moment ago must not travel with a result that
    // cannot carry one - the API takes the whole state, so a leftover value would be sent as meant.
    await pickResult('No intentada')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar entrega' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit).toHaveBeenCalledWith({
      result: 'NOT_ATTEMPTED',
      deliveredAt: null,
      receiverName: null,
      receiverDocument: null,
      notes: null,
    })
  })

  it('pre-fills a correction from the delivery it is correcting', () => {
    renderDrawer(orderDelivery({ result: 'PARTIAL', notes: 'Falta un pallet', receiverName: 'R. Diaz' }))

    expect(screen.getByRole('heading', { name: 'Corregir la entrega' })).toBeInTheDocument()
    expect(screen.getByLabelText(/Observaciones/)).toHaveValue('Falta un pallet')
    expect(screen.getByLabelText(/Recibido por/)).toHaveValue('R. Diaz')
  })

  it("shows the server's refusal inside the drawer, without losing what was typed", async () => {
    const onSubmit = vi.fn<(values: DeliveryValues) => Promise<void>>()
      .mockRejectedValue(new Error('deliveredAt cannot be in the future.'))
    render(
      <DeliveryDrawer
        stopLabel="1. Tienda Uno"
        orderNumber="ORD-00000001"
        onClose={vi.fn()}
        onSubmit={onSubmit}
      />,
    )

    fireEvent.change(screen.getByLabelText(/Fecha y hora de entrega/), { target: { value: '2026-08-20T11:30' } })
    await userEvent.click(screen.getByRole('button', { name: 'Guardar entrega' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('deliveredAt cannot be in the future.')
    expect(screen.getByLabelText(/Fecha y hora de entrega/)).toHaveValue('2026-08-20T11:30')
  })
})
