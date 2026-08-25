import { Suspense, useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Outlet, useLocation } from 'react-router-dom'
import { LoadingState } from './components/LoadingState'
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
 * Application shell: a full-height navigation column, and a content column holding the top bar
 * and the routed screen. Rendered only for signed-in users - `ProtectedRoute` guards the routes
 * that mount it.
 *
 * The sidebar reaching the top of the window, rather than starting below a full-width header,
 * is what lets it carry the brand and read as the spine of the product.
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
          viewport; without it the column resolves against the content's intrinsic width and
          the whole page gains a horizontal scrollbar. */}
      <div className="tms-shell-main tms-min-w-0">
        <TopBar
          navOpen={navOpen}
          onToggleNav={() => setNavOpen((open) => !open)}
          collapsed={collapsed}
          onToggleCollapsed={toggleCollapsed}
        />

        <main className="tms-main tms-min-w-0">
          <div className="tms-content">
            {/* Every screen below this point is code-split (see `appRoutes`), so the first visit
                to one is a network fetch. One boundary here rather than one per route: the shell
                - sidebar, top bar, company switcher - must stay on screen while it arrives, and
                a boundary inside each page would unmount the shell instead. */}
            <Suspense fallback={<LoadingState />}>
              <Outlet />
            </Suspense>
          </div>
        </main>
      </div>
    </div>
  )
}
