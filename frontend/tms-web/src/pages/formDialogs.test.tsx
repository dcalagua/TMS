import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import i18n from '../shared/i18n'
import { DEFAULT_LANGUAGE } from '../shared/i18n/config'
import { CarrierFormModal } from './fleet/CarrierFormModal'
import { VehicleTypeFormModal } from './fleet/VehicleTypeFormModal'
import { FrequencyFormModal } from './masters/FrequencyFormModal'
import { OriginFormModal } from './masters/OriginFormModal'
import { ZoneFormModal } from './masters/ZoneFormModal'

/**
 * The dialog contract, asserted against the real form modals rather than against `TmsModal` in
 * isolation: every form that used to hand-build Bootstrap modal markup must now behave the same
 * way. Covered here are the five that need no async lookups; the others assert the same
 * contract in their own suites.
 */

const DIALOGS = [
  {
    name: 'ZoneFormModal',
    render: (onClose: () => void) => (
      <ZoneFormModal companyId="company-1" zone={null} onClose={onClose} onSaved={vi.fn()} />
    ),
    createTitle: 'Nueva zona',
    englishTitle: 'New zone',
  },
  {
    name: 'OriginFormModal',
    render: (onClose: () => void) => (
      <OriginFormModal companyId="company-1" origin={null} onClose={onClose} onSaved={vi.fn()} />
    ),
    createTitle: 'Nuevo origen',
    englishTitle: 'New origin',
  },
  {
    name: 'FrequencyFormModal',
    render: (onClose: () => void) => (
      <FrequencyFormModal companyId="company-1" frequency={null} onClose={onClose} onSaved={vi.fn()} />
    ),
    createTitle: 'Nueva frecuencia',
    englishTitle: 'New frequency',
  },
  {
    name: 'CarrierFormModal',
    render: (onClose: () => void) => (
      <CarrierFormModal companyId="company-1" carrier={null} onClose={onClose} onSaved={vi.fn()} />
    ),
    createTitle: 'Nuevo transportista',
    englishTitle: 'New carrier',
  },
  {
    name: 'VehicleTypeFormModal',
    render: (onClose: () => void) => (
      <VehicleTypeFormModal companyId="company-1" vehicleType={null} onClose={onClose} onSaved={vi.fn()} />
    ),
    createTitle: 'Nuevo tipo de vehículo',
    englishTitle: 'New vehicle type',
  },
] as const

afterEach(async () => {
  vi.clearAllMocks()
  await i18n.changeLanguage(DEFAULT_LANGUAGE)
})

describe.each(DIALOGS)('$name dialog contract', (dialog) => {
  it('opens as a modal dialog named by its title', () => {
    render(dialog.render(vi.fn()))

    const element = screen.getByRole('dialog')
    expect(element).toHaveAttribute('aria-modal', 'true')
    expect(element).toHaveAccessibleName(dialog.createTitle)
  })

  it('moves focus inside itself when it opens', async () => {
    render(dialog.render(vi.fn()))

    await waitFor(() => expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true))
  })

  it('keeps Tab within the dialog instead of letting focus reach the page behind', async () => {
    const user = userEvent.setup()
    render(dialog.render(vi.fn()))
    const element = screen.getByRole('dialog')

    for (let press = 0; press < 10; press += 1) {
      await user.tab()
      expect(element.contains(document.activeElement)).toBe(true)
    }
  })

  it('closes on Escape', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(dialog.render(onClose))

    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes from the header close button and from Cancel', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(dialog.render(onClose))

    await user.click(screen.getByRole('button', { name: 'Cerrar' }))
    expect(onClose).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('locks the page behind while open and releases it on unmount', async () => {
    const { unmount } = render(dialog.render(vi.fn()))

    expect(document.body.style.overflow).toBe('hidden')

    unmount()
    await waitFor(() => expect(document.body.style.overflow).not.toBe('hidden'))
  })

  it('offers a save action', () => {
    render(dialog.render(vi.fn()))

    expect(screen.getByRole('button', { name: 'Guardar' })).toBeInTheDocument()
  })

  it('renders in English when the language is switched', async () => {
    await i18n.changeLanguage('en')

    render(dialog.render(vi.fn()))

    expect(screen.getByRole('dialog')).toHaveAccessibleName(dialog.englishTitle)
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
  })

  it('leaves no hand-built Bootstrap modal markup behind', () => {
    const { container } = render(dialog.render(vi.fn()))

    expect(container.querySelector('.modal.d-block')).toBeNull()
    expect(document.querySelector('.modal-backdrop')).toBeNull()
  })
})
