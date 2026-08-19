import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type RefObject,
} from 'react'

/** Which edge of the panel lines up with which edge of the trigger. */
export type MenuAlign = 'start' | 'end'

export interface MenuOptions {
  /** `end` right-aligns the panel with the trigger, which is what a row action menu wants. */
  align?: MenuAlign
  /** Which entries can take focus, so disabled ones are skipped. */
  isEnabled?: (index: number) => boolean
}

export interface MenuBehaviour {
  open: boolean
  toggle: () => void
  close: (returnFocus?: boolean) => void
  containerRef: RefObject<HTMLDivElement | null>
  triggerRef: RefObject<HTMLButtonElement | null>
  /**
   * Attach to the floating panel. Required, not optional: the panel is portalled out of the
   * container, so the outside-click test cannot find it by DOM ancestry any more.
   */
  menuRef: RefObject<HTMLDivElement | null>
  /** Fixed-position style for the panel, recomputed while it is open. */
  menuStyle: CSSProperties
  registerItem: (index: number) => (element: HTMLButtonElement | null) => void
  onKeyDown: (event: KeyboardEvent<HTMLElement>) => void
}

/** Breathing room between the panel and the trigger, and between the panel and the viewport. */
const OFFSET = 4
const VIEWPORT_MARGIN = 8

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
 * The panel is positioned against the trigger's viewport rectangle rather than laid out
 * inside it, and the consumer renders it through a portal. That is what stops a table's own
 * overflow containers from clipping it or counting it as scrollable content.
 *
 * @param itemCount how many entries the menu renders
 * @param options alignment and which entries can take focus
 */
export function useMenu(itemCount: number, options: MenuOptions = {}): MenuBehaviour {
  const { align = 'end', isEnabled = () => true } = options
  const [open, setOpen] = useState(false)
  const [menuStyle, setMenuStyle] = useState<CSSProperties>({ position: 'fixed', top: -9999, left: -9999 })
  const containerRef = useRef<HTMLDivElement | null>(null)
  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const menuRef = useRef<HTMLDivElement | null>(null)
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
      const target = event.target as Node
      // The panel is portalled to the body, so `containerRef` alone would report a click on
      // a menu entry as a click outside - closing the menu on mousedown, before the click
      // ever reached the entry.
      const inside =
        containerRef.current?.contains(target) === true || menuRef.current?.contains(target) === true
      if (!inside) {
        setOpen(false)
      }
    }

    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [open])

  useLayoutEffect(() => {
    if (!open) {
      return
    }

    function place() {
      const trigger = triggerRef.current
      const menu = menuRef.current
      if (!trigger || !menu) {
        return
      }

      const anchor = trigger.getBoundingClientRect()
      const { offsetWidth: width, offsetHeight: height } = menu
      const viewportWidth = document.documentElement.clientWidth
      const viewportHeight = document.documentElement.clientHeight

      // Below the trigger, unless the panel would run off the bottom and there is more room
      // above - the last rows of a long table are exactly where this matters.
      const below = anchor.bottom + OFFSET
      const above = anchor.top - height - OFFSET
      const fitsBelow = below + height <= viewportHeight - VIEWPORT_MARGIN
      const top = fitsBelow || above < VIEWPORT_MARGIN ? below : above

      const preferred = align === 'end' ? anchor.right - width : anchor.left
      const left = Math.min(
        Math.max(preferred, VIEWPORT_MARGIN),
        Math.max(viewportWidth - width - VIEWPORT_MARGIN, VIEWPORT_MARGIN),
      )

      setMenuStyle({ position: 'fixed', top, left, maxHeight: viewportHeight - top - VIEWPORT_MARGIN })
    }

    place()

    // `capture` so scrolling any ancestor - the table's own horizontal scroller included -
    // keeps the panel attached to its trigger instead of leaving it stranded.
    window.addEventListener('scroll', place, true)
    window.addEventListener('resize', place)
    return () => {
      window.removeEventListener('scroll', place, true)
      window.removeEventListener('resize', place)
    }
  }, [open, align])

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
    menuRef,
    menuStyle,
    registerItem,
    onKeyDown,
  }
}
