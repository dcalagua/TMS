import Swal from 'sweetalert2'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { DEFAULT_LANGUAGE } from '../i18n/config'
import { confirmAction, notifyError } from './alerts'

vi.mock('sweetalert2', () => ({
  default: { fire: vi.fn().mockResolvedValue({ isConfirmed: true }) },
}))

const fire = vi.mocked(Swal.fire)

function lastOptions(): Record<string, unknown> {
  return (fire.mock.calls.at(-1)?.[0] ?? {}) as Record<string, unknown>
}

beforeEach(() => {
  fire.mockClear()
})

afterEach(async () => {
  await i18n.changeLanguage(DEFAULT_LANGUAGE)
})

describe('SweetAlert2 wrappers', () => {
  it('labels a confirmation in Spanish by default', async () => {
    await confirmAction({ title: '¿Desactivar origen?' })

    expect(lastOptions().confirmButtonText).toBe('Confirmar')
    expect(lastOptions().cancelButtonText).toBe('Cancelar')
  })

  it('follows the active language instead of shipping English defaults', async () => {
    await i18n.changeLanguage('en')

    await confirmAction({ title: 'Deactivate origin?' })

    expect(lastOptions().confirmButtonText).toBe('Confirm')
    expect(lastOptions().cancelButtonText).toBe('Cancel')
  })

  it('lets a caller override the labels for a specific action', async () => {
    await confirmAction({ title: '¿Desactivar origen?', confirmLabel: 'Desactivar', cancelLabel: 'Volver' })

    expect(lastOptions().confirmButtonText).toBe('Desactivar')
    expect(lastOptions().cancelButtonText).toBe('Volver')
  })

  it('translates the dismiss button of an error dialog', () => {
    notifyError('No se pudo guardar')

    expect(lastOptions().confirmButtonText).toBe('Cerrar')
  })
})
