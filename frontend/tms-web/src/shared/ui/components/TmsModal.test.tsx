import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { TmsModal } from './TmsModal'

function Harness({ onClose = () => {} }: { onClose?: () => void }) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Abrir
      </button>
      <TmsModal
        open={open}
        title="Nuevo origen"
        description="Completa los datos"
        onClose={() => {
          setOpen(false)
          onClose()
        }}
        footer={
          <>
            <button type="button">Cancelar</button>
            <button type="button">Guardar</button>
          </>
        }
      >
        <input aria-label="Código" />
        <input aria-label="Nombre" />
      </TmsModal>
    </>
  )
}

describe('TmsModal', () => {
  it('renders nothing while closed', () => {
    render(<Harness />)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('exposes itself as a modal dialog named by its title', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleName('Nuevo origen')
    expect(dialog).toHaveAccessibleDescription('Completa los datos')
  })

  it('moves focus into the dialog when it opens', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    await waitFor(() => expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true))
  })

  it('keeps Tab inside the dialog instead of letting focus escape to the page behind', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    const dialog = screen.getByRole('dialog')
    for (let press = 0; press < 8; press += 1) {
      await user.tab()
      expect(dialog.contains(document.activeElement)).toBe(true)
    }
  })

  it('closes on Escape and returns focus to whatever opened it', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<Harness onClose={onClose} />)

    const trigger = screen.getByRole('button', { name: 'Abrir' })
    await user.click(trigger)
    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })

  it('closes from the header close button', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    await user.click(screen.getByRole('button', { name: 'Cerrar' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('stops the page behind from scrolling while it is open, and restores it after', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))
    expect(document.body.style.overflow).toBe('hidden')

    await user.keyboard('{Escape}')
    await waitFor(() => expect(document.body.style.overflow).not.toBe('hidden'))
  })

  it('renders its footer actions', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    expect(screen.getByRole('button', { name: 'Guardar' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeInTheDocument()
  })

  it('ignores Escape when the caller disables it, so a submit in flight is not abandoned', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(
      <TmsModal open title="Guardando" onClose={onClose} closeOnEscape={false}>
        <input aria-label="Código" />
      </TmsModal>,
    )

    await user.keyboard('{Escape}')

    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
