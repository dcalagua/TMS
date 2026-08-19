import { confirmAction } from '../alerts'

export interface ConfirmDialogOptions {
  title: string
  text?: string
  confirmLabel?: string
  cancelLabel?: string
  /** Renders the confirm button as destructive (delete, revoke, deactivate, ...). */
  dangerous?: boolean
}

/** The one entry point screens use for a confirmation prompt. Wraps SweetAlert2
 * (`shared/ui/alerts.ts`) so every confirmation in the app looks and behaves the same and no
 * screen calls `Swal` directly. Resolves `true` only if the user explicitly confirmed. */
export function confirmDialog(options: ConfirmDialogOptions): Promise<boolean> {
  return confirmAction(options)
}
