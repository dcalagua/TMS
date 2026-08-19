import { describe, expect, it, vi } from 'vitest'
import { applyApiFieldErrors } from './formErrors'
import { ApiError } from './httpClient'

function problem(overrides: Record<string, unknown>): ApiError {
  return new ApiError(400, { code: 'validation-failed', ...overrides }, 'corr-1', 'boom')
}

describe('applyApiFieldErrors', () => {
  it('places a known field error inline and says nothing at form level beyond the fallback', () => {
    const setError = vi.fn()

    const message = applyApiFieldErrors(
      problem({ errors: [{ field: 'code', message: 'Code already exists' }] }),
      new Set(['code', 'name']),
      setError,
      'Corrige los campos marcados.',
    )

    expect(setError).toHaveBeenCalledWith('code', { message: 'Code already exists' })
    expect(message).toBe('Corrige los campos marcados.')
  })

  it('surfaces a field the form does not render, instead of dropping it', () => {
    const setError = vi.fn()

    const message = applyApiFieldErrors(
      problem({ errors: [{ field: 'internalRef', message: 'Reference is already taken' }] }),
      new Set(['code']),
      setError,
      'Corrige los campos marcados.',
    )

    expect(setError).not.toHaveBeenCalled()
    expect(message).toBe('Reference is already taken')
  })

  it('splits a mixed document: known fields inline, unknown ones at form level', () => {
    const setError = vi.fn()

    const message = applyApiFieldErrors(
      problem({
        errors: [
          { field: 'code', message: 'Code already exists' },
          { field: 'internalRef', message: 'Reference is already taken' },
        ],
      }),
      new Set(['code']),
      setError,
      'Corrige los campos marcados.',
    )

    expect(setError).toHaveBeenCalledWith('code', { message: 'Code already exists' })
    expect(message).toBe('Reference is already taken')
  })

  it('describes a non-validation failure from its code, never from detail', () => {
    const setError = vi.fn()
    const conflict = new ApiError(409, { code: 'conflict', detail: 'Row was changed' }, 'corr-1', 'boom')

    const message = applyApiFieldErrors(conflict, new Set(['code']), setError, 'Corrige los campos marcados.')

    expect(setError).not.toHaveBeenCalled()
    expect(message).toBe('Este cambio entra en conflicto con otra actualización. Recarga e inténtalo de nuevo.')
  })
})
