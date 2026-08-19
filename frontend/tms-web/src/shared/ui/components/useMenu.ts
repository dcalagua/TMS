import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type RefObject } from 'react'

export interface MenuBehaviour {
  open: boolean
  toggle: () => void
  close: (returnFocus?: boolean) => void
  containerRef: RefObject<HTMLDivElement | null>
  triggerRef: RefObject<HTMLButtonElement | null>
  registerItem: (index: number) => (element: HTMLButtonElement | null) => void
  onKeyDown: (event: KeyboardEvent<HTMLElement>) => void
}

/**
 * Keyboard and dismissal behaviour for a popup menu, shared by the row action menu, the
 * company switcher and the user menu.
 *
 * Written here rather than delegated to Bootstrap's dropdown data API: that API resolves
 * instances against whichever copy of Bootstrap's JS loaded first, which is the same class of
 * problem that made the sidebar links stop navigating. Arrow keys move through the entries,
 * Home/End jump to the ends, Escape closes and returns focus to the trigger, Tab closes, and a
 * click outside dismisses.
 *
 * @param itemCount how many entries the menu renders
 * @param isEnabled which entries can take focus, so disabled ones are skipped
 */
export function useMenu(itemCount: number, isEnabled: (index: number) => boolean = () => true): MenuBehaviour {
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement | null>(null)
  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([])

  // Held in a ref so the focus helpers stay referentially stable while still seeing the
  // current predicate: a menu whose entries enable and disable as the user types must not
  // rebuild its keyboard handler on every keystroke.
  const isEnabledRef = useRef(isEnabled)
  useEffect(() => {
    isEnabledRef.current = isEnabled
  })

  const close = useCallback((returnFocus = true) => {
    setOpen(false)
    if (returnFocus) {
      triggerRef.current?.focus()
    }
  }, [])

  const enabledIndexes = useCallback(
    () => Array.from({ length: itemCount }, (_unused, index) => index).filter((index) => isEnabledRef.current(index)),
    [itemCount],
  )

  const focusItem = useCallback(
    (position: number) => {
      const enabled = enabledIndexes()
      if (enabled.length === 0) {
        return
      }
      const index = enabled[(position + enabled.length) % enabled.length]
      if (index !== undefined) {
        itemRefs.current[index]?.focus()
      }
    },
    [enabledIndexes],
  )

  useEffect(() => {
    if (!open) {
      return
    }

    function onPointerDown(event: MouseEvent) {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }

    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [open])

  useEffect(() => {
    if (open) {
      focusItem(0)
    }
  }, [open, focusItem])

  const onKeyDown = useCallback(
    (event: KeyboardEvent<HTMLElement>) => {
      const enabled = enabledIndexes()
      const currentIndex = itemRefs.current.findIndex((element) => element === document.activeElement)
      const position = enabled.indexOf(currentIndex)

      switch (event.key) {
        case 'Escape':
          event.preventDefault()
          close()
          break
        case 'ArrowDown':
          event.preventDefault()
          focusItem(position + 1)
          break
        case 'ArrowUp':
          event.preventDefault()
          focusItem(position - 1)
          break
        case 'Home':
          event.preventDefault()
          focusItem(0)
          break
        case 'End':
          event.preventDefault()
          focusItem(enabled.length - 1)
          break
        case 'Tab':
          setOpen(false)
          break
        default:
          break
      }
    },
    [close, focusItem, enabledIndexes],
  )

  const registerItem = useCallback(
    (index: number) => (element: HTMLButtonElement | null) => {
      itemRefs.current[index] = element
    },
    [],
  )

  return {
    open,
    toggle: () => setOpen((current) => !current),
    close,
    containerRef,
    triggerRef,
    registerItem,
    onKeyDown,
  }
}
