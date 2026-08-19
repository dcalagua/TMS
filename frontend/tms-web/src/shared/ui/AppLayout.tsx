import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Outlet, useLocation } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'

const COLLAPSED_STORAGE_KEY = 'tms.sidebarCollapsed'

function readCollapsedPreference(): boolean {
  try {
    return window.localStorage.getItem(COLLAPSED_STORAGE_KEY) === 'true'
  } catch {
    return false
  }
}

/**
 * Application shell: top bar, side navigation and the routed content area. Rendered only for
 * signed-in users - `ProtectedRoute` guards the routes that mount it.
 *
 * Two independent navigation states live here because both the top bar and the sidebar need
 * them: `navOpen` is the mobile drawer, `collapsed` is the desktop icon rail. Closing the
 * drawer on every `pathname` change covers link clicks, browser Back/Forward and programmatic
 * navigation with one rule; at `lg` and above the class it toggles is inert, so desktop is
 * unaffected.
 *
 * There is no footer: a dense operational screen cannot spare a row of vertical space to
 * repeat the product name already shown in the top bar.
 */
export function AppLayout() {
  const { t } = useTranslation('navigation')
  const [navOpen, setNavOpen] = useState(false)
  const [collapsed, setCollapsed] = useState(readCollapsedPreference)
  const { pathname } = useLocation()

  const closeNav = useCallback(() => setNavOpen(false), [])

  // Adjusted during render (React's documented "reset state when a value changes" pattern,
  // as used in `CompanyContext`) rather than in an effect, so the drawer is already closed in
  // the same commit that shows the new screen instead of one render later.
  const [trackedPathname, setTrackedPathname] = useState(pathname)
  if (pathname !== trackedPathname) {
    setTrackedPathname(pathname)
    if (navOpen) {
      setNavOpen(false)
    }
  }

  const toggleCollapsed = useCallback(() => {
    setCollapsed((current) => {
      const next = !current
      try {
        window.localStorage.setItem(COLLAPSED_STORAGE_KEY, String(next))
      } catch {
        // Storage unavailable: the preference simply does not survive a reload.
      }
      return next
    })
  }, [])

  useEffect(() => {
    if (!navOpen) {
      return
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setNavOpen(false)
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [navOpen])

  return (
    <div className="tms-shell">
      <TopBar
        navOpen={navOpen}
        onToggleNav={() => setNavOpen((open) => !open)}
        collapsed={collapsed}
        onToggleCollapsed={toggleCollapsed}
      />

      <div className="d-flex flex-grow-1 tms-min-w-0">
        <Sidebar open={navOpen} collapsed={collapsed} onRequestClose={closeNav} />

        {navOpen && (
          <button
            type="button"
            className="offcanvas-backdrop fade show d-lg-none border-0 p-0"
            onClick={closeNav}
            aria-label={t('closeNavigation')}
          />
        )}

        {/* `tms-min-w-0` is what stops a wide table from stretching this flex item past the
            viewport; without it `flex-grow-1` resolves against the content's intrinsic width
            and the whole page gains a horizontal scrollbar. */}
        <main className="flex-grow-1 tms-main tms-min-w-0">
          <div className="tms-content">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}
