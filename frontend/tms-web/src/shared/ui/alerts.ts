import Swal from 'sweetalert2'
import i18n from '../i18n'

/**
 * SweetAlert2 wrappers so confirmations and critical feedback look the same everywhere.
 * Screens must not call Swal directly; they call these helpers.
 *
 * Button labels default to the active language rather than to English literals, so a dialog
 * opened from a screen that forgot to pass labels is still translated.
 */

const BOOTSTRAP_BUTTONS = {
  confirmButton: 'btn btn-primary mx-1',
  cancelButton: 'btn btn-outline-secondary mx-1',
  denyButton: 'btn btn-danger mx-1',
}

export async function confirmAction(options: {
  title: string
  text?: string
  confirmLabel?: string
  cancelLabel?: string
  dangerous?: boolean
}): Promise<boolean> {
  const result = await Swal.fire({
    title: options.title,
    text: options.text,
    icon: options.dangerous === true ? 'warning' : 'question',
    showCancelButton: true,
    confirmButtonText: options.confirmLabel ?? i18n.t('confirm.confirm', { ns: 'dialogs' }),
    cancelButtonText: options.cancelLabel ?? i18n.t('confirm.cancel', { ns: 'dialogs' }),
    reverseButtons: true,
    buttonsStyling: false,
    customClass: {
      ...BOOTSTRAP_BUTTONS,
      confirmButton: options.dangerous === true ? BOOTSTRAP_BUTTONS.denyButton : BOOTSTRAP_BUTTONS.confirmButton,
    },
  })

  return result.isConfirmed
}

/**
 * A confirmation that also collects one short piece of text - the "why" behind a destructive
 * action, cancelling a confirmed shipment being the case it was written for.
 *
 * <p>Deliberately a dialog and not a drawer: the reason is the confirmation, and splitting them
 * into "are you sure?" followed by a form is two chances to abandon an action that has to be
 * either done with an explanation or not done at all.
 *
 * @returns the trimmed text, or `null` when the user dismissed the dialog. An empty string is
 *   never returned - `required` refuses it in the dialog, where the user can still fix it.
 */
export async function promptForText(options: {
  title: string
  text?: string
  inputLabel?: string
  inputPlaceholder?: string
  required?: boolean
  requiredMessage?: string
  maxLength?: number
  confirmLabel?: string
  cancelLabel?: string
  dangerous?: boolean
}): Promise<string | null> {
  const result = await Swal.fire({
    title: options.title,
    text: options.text,
    icon: options.dangerous === true ? 'warning' : 'question',
    input: 'text',
    inputLabel: options.inputLabel,
    inputPlaceholder: options.inputPlaceholder,
    inputAttributes: options.maxLength === undefined ? undefined : { maxlength: String(options.maxLength) },
    inputValidator: (value) => {
      if (options.required === true && value.trim() === '') {
        return options.requiredMessage ?? i18n.t('prompt.required', { ns: 'dialogs' })
      }
      return null
    },
    showCancelButton: true,
    confirmButtonText: options.confirmLabel ?? i18n.t('confirm.confirm', { ns: 'dialogs' }),
    cancelButtonText: options.cancelLabel ?? i18n.t('confirm.cancel', { ns: 'dialogs' }),
    reverseButtons: true,
    buttonsStyling: false,
    customClass: {
      ...BOOTSTRAP_BUTTONS,
      confirmButton: options.dangerous === true ? BOOTSTRAP_BUTTONS.denyButton : BOOTSTRAP_BUTTONS.confirmButton,
      input: 'form-control',
    },
  })

  return result.isConfirmed ? String(result.value ?? '').trim() : null
}

export function notifySuccess(title: string, text?: string): void {
  void Swal.fire({
    title,
    text,
    icon: 'success',
    toast: true,
    position: 'top-end',
    timer: 2500,
    showConfirmButton: false,
  })
}

export function notifyError(title: string, text?: string): void {
  void Swal.fire({
    title,
    text,
    icon: 'error',
    confirmButtonText: i18n.t('actions.close', { ns: 'common' }),
    buttonsStyling: false,
    customClass: BOOTSTRAP_BUTTONS,
  })
}
