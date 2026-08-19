import Swal from 'sweetalert2'

/**
 * SweetAlert2 wrappers so confirmations and critical feedback look the same everywhere.
 * Screens must not call Swal directly; they call these helpers.
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
    confirmButtonText: options.confirmLabel ?? 'Confirm',
    cancelButtonText: options.cancelLabel ?? 'Cancel',
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
    buttonsStyling: false,
    customClass: BOOTSTRAP_BUTTONS,
  })
}
