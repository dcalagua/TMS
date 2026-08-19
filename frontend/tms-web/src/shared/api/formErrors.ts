import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import type { ApiError } from './httpClient'
import { describeApiError } from './problemMessages'

/**
 * Turns a backend refusal into inline field errors plus, when needed, one form-level message.
 *
 * Every form dialog needs the same three-way split, so it lives here rather than being written
 * out per screen:
 *
 * - a `validation-failed` document names the offending fields, and each one that the form
 *   actually renders becomes an inline error under that field, where the user is looking;
 * - a field the form does not render (the backend validates more than any single screen shows)
 *   would otherwise vanish, so its message is surfaced at form level;
 * - anything else - a conflict, a permission refusal, a server error - is described from its
 *   stable `code`, never from `detail`.
 *
 * @param knownFields the form's own field names; anything outside it cannot be shown inline
 * @param fallback translated copy for "some fields need correcting", used when every field
 *   error was placed inline and there is nothing left to say at form level
 * @returns the form-level message, or `null` when the inline errors say everything
 */
export function applyApiFieldErrors<TValues extends FieldValues>(
  error: ApiError,
  knownFields: ReadonlySet<string>,
  setError: UseFormSetError<TValues>,
  fallback: string,
): string | null {
  if (error.fieldErrors.length === 0) {
    return describeApiError(error)
  }

  const unmatched: string[] = []
  for (const fieldError of error.fieldErrors) {
    if (knownFields.has(fieldError.field)) {
      setError(fieldError.field as Path<TValues>, { message: fieldError.message })
    } else {
      unmatched.push(fieldError.message)
    }
  }

  return unmatched.length > 0 ? unmatched.join(' ') : fallback
}
