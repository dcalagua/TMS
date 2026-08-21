import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

/** The brand palette. Adding one means adding a palette block in `styles/tokens.css`. */
export type ThemeName = 'neutral' | 'ebim'

/** Light or dark. Named after Bootstrap 5.3's own attribute, which this drives. */
export type ThemeMode = 'light' | 'dark'

export const THEME_NAMES: ThemeName[] = ['neutral', 'ebim']

const THEME_KEY = 'tms.theme'
const MODE_KEY = 'tms.theme.mode'

export const DEFAULT_THEME: ThemeName = 'neutral'
export const DEFAULT_MODE: ThemeMode = 'light'

interface ThemeContextValue {
  theme: ThemeName
  mode: ThemeMode
  setTheme: (theme: ThemeName) => void
  setMode: (mode: ThemeMode) => void
  toggleMode: () => void
}

const ThemeContext = createContext<ThemeContextValue | null>(null)

function isTheme(value: unknown): value is ThemeName {
  return value === 'neutral' || value === 'ebim'
}

function isMode(value: unknown): value is ThemeMode {
  return value === 'light' || value === 'dark'
}

/**
 * Reads what `applyStoredTheme` already put on the element, so the provider adopts the value
 * the page painted with instead of re-deciding it and causing a second, visible switch.
 */
function readApplied<T>(attribute: string, guard: (value: unknown) => value is T, fallback: T): T {
  if (typeof document === 'undefined') {
    return fallback
  }
  const value = document.documentElement.getAttribute(attribute)
  return guard(value) ? value : fallback
}

function store(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // Storage may be unavailable (private mode, disabled cookies). The choice still applies
    // for this tab; it just does not survive a reload.
  }
}

/**
 * Applies the stored theme to `<html>`.
 *
 * Called from `main.tsx` **before** React renders, and idempotent, so the first paint already
 * carries the right palette. Doing it in an effect instead would show one frame of the default
 * theme - the flash of light that makes a dark-mode application feel broken.
 */
export function applyStoredTheme() {
  let theme: ThemeName = DEFAULT_THEME
  let mode: ThemeMode = DEFAULT_MODE
  try {
    const storedTheme = window.localStorage.getItem(THEME_KEY)
    const storedMode = window.localStorage.getItem(MODE_KEY)
    if (isTheme(storedTheme)) {
      theme = storedTheme
    }
    if (isMode(storedMode)) {
      mode = storedMode
    }
  } catch {
    // Unreadable storage is not an error worth failing a page load over.
  }
  document.documentElement.setAttribute('data-theme', theme)
  document.documentElement.setAttribute('data-bs-theme', mode)
}

/**
 * Owns which palette the interface is wearing.
 *
 * It writes two attributes on `<html>` and nothing else: `data-theme` for the brand and
 * `data-bs-theme` for light/dark. All the colour lives in `styles/tokens.css`, so no component
 * ever branches on the theme - which is what keeps a second theme from doubling the component
 * layer.
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemeName>(() => readApplied('data-theme', isTheme, DEFAULT_THEME))
  const [mode, setModeState] = useState<ThemeMode>(() => readApplied('data-bs-theme', isMode, DEFAULT_MODE))

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
  }, [theme])

  useEffect(() => {
    document.documentElement.setAttribute('data-bs-theme', mode)
  }, [mode])

  const setTheme = useCallback((next: ThemeName) => {
    setThemeState(next)
    store(THEME_KEY, next)
  }, [])

  const setMode = useCallback((next: ThemeMode) => {
    setModeState(next)
    store(MODE_KEY, next)
  }, [])

  const toggleMode = useCallback(() => {
    setModeState((current) => {
      const next = current === 'dark' ? 'light' : 'dark'
      store(MODE_KEY, next)
      return next
    })
  }, [])

  const value = useMemo<ThemeContextValue>(
    () => ({ theme, mode, setTheme, setMode, toggleMode }),
    [theme, mode, setTheme, setMode, toggleMode],
  )

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext)
  if (!context) {
    throw new Error('useTheme must be used inside a ThemeProvider')
  }
  return context
}
