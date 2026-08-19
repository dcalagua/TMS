import { useCallback, useEffect, useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'

/** Application shell: top bar, side navigation and the routed content area. Rendered only for
 * signed-in users - `ProtectedRoute` guards the routes that mount it.
 *
 * The mobile drawer's open/closed state lives here because both the top bar's toggle and the
 * sidebar's links need it. Closing on every `pathname` change covers link clicks, browser
 * Back/Forward and programmatic navigation with one rule; at `lg` and above the class it
 * toggles is inert, so desktop is unaffected. */
export function AppLayout() {
  const [navOpen, setNavOpen] = useState(false)
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
    <div className="d-flex flex-column min-vh-100">
      <TopBar navOpen={navOpen} onToggleNav={() => setNavOpen((open) => !open)} />

      <div className="d-flex flex-grow-1 tms-min-w-0">
        <Sidebar open={navOpen} onRequestClose={closeNav} />

        {navOpen && (
          <button
            type="button"
            className="offcanvas-backdrop fade show d-lg-none border-0 p-0"
            onClick={closeNav}
            aria-label="Close navigation"
          />
        )}

        {/* `tms-min-w-0` is what stops a wide table from stretching this flex item past the
            viewport; without it `flex-grow-1` resolves against the content's intrinsic width
            and the whole page gains a horizontal scrollbar. */}
        <main className="flex-grow-1 bg-body-tertiary tms-min-w-0">
          <div className="container-fluid py-3">
            <Outlet />
          </div>
        </main>
      </div>

      <footer className="border-top py-2">
        <div className="container-fluid small text-body-secondary d-flex justify-content-between">
          <span>TMS by EBIM</span>
          <span>Transport Management System</span>
        </div>
      </footer>
    </div>
  )
}
