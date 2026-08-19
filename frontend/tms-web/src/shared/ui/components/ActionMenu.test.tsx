import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ActionMenu } from './ActionMenu'

function renderMenu(overrides: Partial<Parameters<typeof ActionMenu>[0]> = {}) {
  const onEdit = vi.fn()
  const onDeactivate = vi.fn()

  render(
    <ActionMenu
      items={[
        { key: 'edit', label: 'Editar', icon: 'bi-pencil', onSelect: onEdit },
        { key: 'deactivate', label: 'Desactivar', icon: 'bi-slash-circle', onSelect: onDeactivate, dangerous: true },
      ]}
      {...overrides}
    />,
  )

  return { onEdit, onDeactivate }
}

describe('ActionMenu', () => {
  it('renders nothing when a row has no available actions', () => {
    render(<ActionMenu items={[]} />)

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('gives its icon-only trigger an accessible name', () => {
    renderMenu()

    expect(screen.getByRole('button', { name: 'Abrir menú de acciones' })).toBeInTheDocument()
  })

  it('keeps the menu closed until asked', () => {
    renderMenu()

    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('runs the selected action and closes', async () => {
    const user = userEvent.setup()
    const { onEdit } = renderMenu()

    await user.click(screen.getByRole('button', { name: 'Abrir menú de acciones' }))
    await user.click(screen.getByRole('menuitem', { name: 'Editar' }))

    expect(onEdit).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument())
  })

  it('focuses the first entry on open and moves through them with the arrow keys', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: 'Abrir menú de acciones' }))

    await waitFor(() => expect(screen.getByRole('menuitem', { name: 'Editar' })).toHaveFocus())
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('menuitem', { name: 'Desactivar' })).toHaveFocus()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('menuitem', { name: 'Editar' })).toHaveFocus()
    await user.keyboard('{ArrowUp}')
    expect(screen.getByRole('menuitem', { name: 'Desactivar' })).toHaveFocus()
  })

  it('closes on Escape and returns focus to the trigger', async () => {
    const user = userEvent.setup()
    renderMenu()
    const trigger = screen.getByRole('button', { name: 'Abrir menú de acciones' })

    await user.click(trigger)
    await user.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument())
    expect(trigger).toHaveFocus()
  })

  it('skips a disabled entry when arrowing', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    render(
      <ActionMenu
        items={[
          { key: 'a', label: 'Editar', onSelect },
          { key: 'b', label: 'Desactivar', onSelect, disabled: true },
          { key: 'c', label: 'Eliminar', onSelect },
        ]}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Abrir menú de acciones' }))
    await waitFor(() => expect(screen.getByRole('menuitem', { name: 'Editar' })).toHaveFocus())
    await user.keyboard('{ArrowDown}')

    expect(screen.getByRole('menuitem', { name: 'Eliminar' })).toHaveFocus()
  })

  describe('escaping the table', () => {
    // The defect this replaced: the panel was an absolutely positioned child of the cell, so
    // `.tms-table-wrap` (overflow: hidden) clipped it and `.tms-table-scroll` (overflow-x:
    // auto) counted it as content and grew a scrollbar. Both are properties of an overflow
    // container and neither is answerable with z-index.
    function renderInsideAnOverflowContainer() {
      const onEdit = vi.fn()
      const { container } = render(
        <div className="tms-table-wrap" style={{ overflow: 'hidden' }}>
          <div className="tms-table-scroll" style={{ overflowX: 'auto' }}>
            <ActionMenu items={[{ key: 'edit', label: 'Editar', onSelect: onEdit }]} />
          </div>
        </div>,
      )
      return { onEdit, container }
    }

    it('renders the panel outside the clipping container, on the body', async () => {
      const user = userEvent.setup()
      const { container } = renderInsideAnOverflowContainer()

      await user.click(screen.getByRole('button', { name: 'Abrir menú de acciones' }))

      const menu = screen.getByRole('menu')
      expect(container.querySelector('[role="menu"]')).toBeNull()
      expect(menu.closest('.tms-table-scroll')).toBeNull()
      expect(menu.parentElement).toBe(document.body)
    })

    it('still runs the action when the panel is portalled away from its trigger', async () => {
      // The panel is no longer a DOM descendant of the trigger's container, so a dismissal
      // check that only knows about the container treats a click on an entry as a click
      // outside - closing on mousedown, before the click reaches the button.
      const user = userEvent.setup()
      const { onEdit } = renderInsideAnOverflowContainer()

      await user.click(screen.getByRole('button', { name: 'Abrir menú de acciones' }))
      await user.click(screen.getByRole('menuitem', { name: 'Editar' }))

      expect(onEdit).toHaveBeenCalledTimes(1)
    })

    it('is positioned against the viewport rather than laid out in the row', async () => {
      const user = userEvent.setup()
      renderInsideAnOverflowContainer()

      await user.click(screen.getByRole('button', { name: 'Abrir menú de acciones' }))

      expect(screen.getByRole('menu')).toHaveStyle({ position: 'fixed' })
    })

    it('closes on a click elsewhere on the page', async () => {
      const user = userEvent.setup()
      render(
        <>
          <button type="button">Fuera</button>
          <ActionMenu items={[{ key: 'edit', label: 'Editar', onSelect: vi.fn() }]} />
        </>,
      )

      await user.click(screen.getByRole('button', { name: 'Abrir menú de acciones' }))
      expect(screen.getByRole('menu')).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Fuera' }))
      await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument())
    })
  })

  it('marks the trigger as expanded only while the menu is open', async () => {
    const user = userEvent.setup()
    renderMenu()
    const trigger = screen.getByRole('button', { name: 'Abrir menú de acciones' })

    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    await user.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
  })
})
