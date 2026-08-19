import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../i18n'
import { DEFAULT_LANGUAGE } from '../../i18n/config'
import { confirmAction } from '../alerts'
import { TmsDrawer } from './TmsDrawer'

vi.mock('../alerts', async () => {
  const actual = await vi.importActual<typeof import('../alerts')>('../alerts')
  return { ...actual, confirmAction: vi.fn() }
})

const confirm = vi.mocked(confirmAction)

function Harness({
  onClose = () => {},
  dirty = false,
  ...rest
}: Partial<Parameters<typeof TmsDrawer>[0]> & { dirty?: boolean }) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Abrir
      </button>
      <TmsDrawer
        open={open}
        title="Nuevo origen"
        subtitle="Registra un nuevo punto de carga"
        dirty={dirty}
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
        {...rest}
      >
        <input aria-label="Código" />
        <input aria-label="Nombre" />
      </TmsDrawer>
    </>
  )
}

beforeEach(() => {
  confirm.mockReset()
  confirm.mockResolvedValue(true)
})

afterEach(async () => {
  await i18n.changeLanguage(DEFAULT_LANGUAGE)
})

describe('TmsDrawer', () => {
  it('renders nothing while closed', () => {
    render(<Harness />)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('opens as a modal dialog named and described by its own copy', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    const drawer = screen.getByRole('dialog')
    expect(drawer).toHaveAttribute('aria-modal', 'true')
    expect(drawer).toHaveAccessibleName('Nuevo origen')
    expect(drawer).toHaveAccessibleDescription('Registra un nuevo punto de carga')
  })

  it('slides in from the right at the requested width', async () => {
    const user = userEvent.setup()
    render(<Harness size="lg" />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    expect(screen.getByRole('dialog')).toHaveClass('tms-drawer', 'tms-drawer-size-lg')
  })

  it('moves focus inside itself when it opens', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    await waitFor(() => expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true))
  })

  it('keeps Tab inside the panel instead of letting focus reach the list behind', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    const drawer = screen.getByRole('dialog')
    for (let press = 0; press < 8; press += 1) {
      await user.tab()
      expect(drawer.contains(document.activeElement)).toBe(true)
    }
  })

  it('closes from the header X and restores focus to whatever opened it', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<Harness onClose={onClose} />)

    const trigger = screen.getByRole('button', { name: 'Abrir' })
    await user.click(trigger)
    await user.click(screen.getByRole('button', { name: 'Cerrar' }))

    expect(onClose).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })

  it('closes on Escape', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<Harness onClose={onClose} />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))
    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes on a backdrop click', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    const { container } = render(<Harness onClose={onClose} />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))
    const backdrop = document.querySelector('.tms-drawer-backdrop')
    expect(backdrop).not.toBeNull()
    await user.click(backdrop as Element)

    expect(onClose).toHaveBeenCalledTimes(1)
    expect(container).toBeTruthy()
  })

  it('ignores Escape and the backdrop when the caller disables them', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<Harness onClose={onClose} closeOnEscape={false} closeOnBackdrop={false} />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))
    await user.keyboard('{Escape}')
    await user.click(document.querySelector('.tms-drawer-backdrop') as Element)

    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('locks the page behind while open and releases it on close', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))
    expect(document.body.style.overflow).toBe('hidden')

    await user.keyboard('{Escape}')
    await waitFor(() => expect(document.body.style.overflow).not.toBe('hidden'))
  })

  it('keeps its footer actions visible', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    expect(screen.getByRole('button', { name: 'Guardar' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeInTheDocument()
  })

  it('shows a loading state instead of the body when asked', async () => {
    const user = userEvent.setup()
    render(<Harness loading />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.queryByLabelText('Código')).not.toBeInTheDocument()
  })

  describe('unsaved changes', () => {
    it('closes straight away when the form is clean, without asking', async () => {
      const user = userEvent.setup()
      const onClose = vi.fn()
      render(<Harness onClose={onClose} dirty={false} />)

      await user.click(screen.getByRole('button', { name: 'Abrir' }))
      await user.keyboard('{Escape}')

      expect(confirm).not.toHaveBeenCalled()
      expect(onClose).toHaveBeenCalledTimes(1)
    })

    it('asks before discarding unsaved edits, and closes when confirmed', async () => {
      const user = userEvent.setup()
      const onClose = vi.fn()
      confirm.mockResolvedValue(true)
      render(<Harness onClose={onClose} dirty />)

      await user.click(screen.getByRole('button', { name: 'Abrir' }))
      await user.keyboard('{Escape}')

      await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
      expect(confirm).toHaveBeenCalledWith(
        expect.objectContaining({ title: '¿Descartar cambios?', text: 'Tienes cambios sin guardar.' }),
      )
    })

    it('stays open when the user chooses to keep editing', async () => {
      const user = userEvent.setup()
      const onClose = vi.fn()
      confirm.mockResolvedValue(false)
      render(<Harness onClose={onClose} dirty />)

      await user.click(screen.getByRole('button', { name: 'Abrir' }))
      await user.keyboard('{Escape}')

      await waitFor(() => expect(confirm).toHaveBeenCalled())
      expect(onClose).not.toHaveBeenCalled()
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    it('guards the backdrop and the X as well as Escape', async () => {
      const user = userEvent.setup()
      confirm.mockResolvedValue(false)
      render(<Harness dirty />)

      await user.click(screen.getByRole('button', { name: 'Abrir' }))

      await user.click(document.querySelector('.tms-drawer-backdrop') as Element)
      await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1))

      await user.click(screen.getByRole('button', { name: 'Cerrar' }))
      await waitFor(() => expect(confirm).toHaveBeenCalledTimes(2))
    })
  })

  it('keeps the caret in the field once the form turns dirty', async () => {
    // The dirty flag flips on the first keystroke, which changes the identity of the drawer's
    // dismiss handler. Focus management must not restart because of that, or every field in the
    // app would swallow all but the first character typed into it.
    function DirtyTrackingHarness() {
      const [open, setOpen] = useState(false)
      const [value, setValue] = useState('')

      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>
            Abrir
          </button>
          <TmsDrawer open={open} title="Nuevo origen" onClose={() => setOpen(false)} dirty={value !== ''}>
            <input aria-label="Código" value={value} onChange={(event) => setValue(event.target.value)} />
          </TmsDrawer>
        </>
      )
    }

    const user = userEvent.setup()
    render(<DirtyTrackingHarness />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))
    const field = screen.getByLabelText('Código')
    await user.click(field)
    await user.keyboard('NEW-ORIGIN')

    expect(field).toHaveValue('NEW-ORIGIN')
    expect(document.activeElement).toBe(field)
  })

  it('renders its chrome in English when the language is switched', async () => {
    await i18n.changeLanguage('en')
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument()
  })

  it('asks in English too', async () => {
    await i18n.changeLanguage('en')
    const user = userEvent.setup()
    confirm.mockResolvedValue(false)
    render(<Harness dirty />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))
    await user.keyboard('{Escape}')

    await waitFor(() =>
      expect(confirm).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Discard changes?', text: 'You have unsaved changes.' }),
      ),
    )
  })
})
