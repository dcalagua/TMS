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
