import { promptForText } from '../alerts'

export interface PromptDialogOptions {
  title: string
  text?: string
  inputLabel?: string
  inputPlaceholder?: string
  /** Refuses an empty answer inside the dialog, so the user fixes it without losing the action. */
  required?: boolean
  requiredMessage?: string
  maxLength?: number
  confirmLabel?: string
  cancelLabel?: string
  /** Renders the confirm button as destructive (cancel a shipment, revoke, delete, ...). */
  dangerous?: boolean
}

/**
 * The confirmation-with-a-reason prompt, the sibling of `confirmDialog`. Same rule: screens call
 * this, never `Swal` directly, so every dialog in the app looks and behaves the same.
 *
 * Resolves to the trimmed text the user entered, or `null` if they dismissed it.
 */
export function promptDialog(options: PromptDialogOptions): Promise<string | null> {
  return promptForText(options)
}
