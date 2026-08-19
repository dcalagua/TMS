import { useEffect, useRef, type RefObject } from 'react'

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

function focusableWithin(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
    (element) => element.offsetParent !== null || element === document.activeElement,
  )
}

/** Tracks how many dialogs are open so two stacked dialogs cannot leave the page
 * permanently unscrollable when the inner one closes. */
let openDialogCount = 0

export interface DialogBehaviourOptions {
  open: boolean
  onClose: () => void
  /** Set false for a dialog that must be dismissed through its own buttons. */
  closeOnEscape?: boolean
}

/**
 * The behaviour every dialog in TMS shares, in one place instead of re-implemented per form:
 *
 * - focus moves into the dialog when it opens and returns to the trigger when it closes;
 * - Tab and Shift+Tab cycle inside it, so the keyboard cannot wander onto the page behind;
 * - Escape closes it;
 * - the page behind stops scrolling while it is open.
 *
 * Returns the ref to attach to the dialog element.
 *
 * The setup runs once per open, not once per render of the caller: `onClose` and `closeOnEscape`
 * are read through refs so a callback that changes identity - `TmsDrawer`'s dismiss handler does,
 * the moment the form inside becomes dirty - cannot tear the effect down and re-run it. Re-running
 * it would restore focus to the trigger and then push it back to the first field, which reads to
 * the user as the cursor jumping out of the input after the first keystroke.
 */
export function useDialogBehaviour({
  open,
  onClose,
  closeOnEscape = true,
}: DialogBehaviourOptions): RefObject<HTMLDivElement | null> {
  const dialogRef = useRef<HTMLDivElement | null>(null)
  const previouslyFocused = useRef<HTMLElement | null>(null)
  const onCloseRef = useRef(onClose)
  const closeOnEscapeRef = useRef(closeOnEscape)

  useEffect(() => {
    onCloseRef.current = onClose
    closeOnEscapeRef.current = closeOnEscape
  })

  useEffect(() => {
    if (!open) {
      return
    }

    previouslyFocused.current = document.activeElement as HTMLElement | null

    const dialog = dialogRef.current
    if (dialog) {
      const [first] = focusableWithin(dialog)
      ;(first ?? dialog).focus()
    }

    openDialogCount += 1
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && closeOnEscapeRef.current) {
        event.stopPropagation()
        onCloseRef.current()
        return
      }

      if (event.key !== 'Tab') {
        return
      }

      const container = dialogRef.current
      if (!container) {
        return
      }

      const focusable = focusableWithin(container)
      if (focusable.length === 0) {
        event.preventDefault()
        container.focus()
        return
      }

      const first = focusable[0] as HTMLElement
      const last = focusable[focusable.length - 1] as HTMLElement
      const active = document.activeElement

      if (event.shiftKey && (active === first || !container.contains(active))) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && active === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown, true)

    return () => {
      document.removeEventListener('keydown', onKeyDown, true)
      openDialogCount = Math.max(0, openDialogCount - 1)
      if (openDialogCount === 0) {
        document.body.style.overflow = previousOverflow
      }
      previouslyFocused.current?.focus?.()
    }
  }, [open])

  return dialogRef
}
