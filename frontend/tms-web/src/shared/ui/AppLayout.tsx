import { NavLink, Outlet } from 'react-router-dom'

interface NavItem {
  to: string
  label: string
  /** Modules are added as their vertical slices land in later steps. */
  enabled: boolean
}

const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Dashboard', enabled: true },
  { to: '/masters', label: 'Master data', enabled: false },
  { to: '/fleet', label: 'Fleet', enabled: false },
  { to: '/orders', label: 'Orders', enabled: false },
  { to: '/planning', label: 'Planning', enabled: false },
]

/** Application shell: top bar, side navigation and the routed content area. */
export function AppLayout() {
  return (
    <div className="d-flex flex-column min-vh-100">
      <nav className="navbar navbar-expand-lg navbar-dark bg-dark border-bottom border-secondary">
        <div className="container-fluid">
          <span className="navbar-brand fw-semibold">
            TMS <span className="text-secondary fw-normal">by EBIM</span>
          </span>
          <button
            className="navbar-toggler"
            type="button"
            data-bs-toggle="collapse"
            data-bs-target="#tms-navbar"
            aria-controls="tms-navbar"
            aria-expanded="false"
            aria-label="Toggle navigation"
          >
            <span className="navbar-toggler-icon" />
          </button>
          <div className="collapse navbar-collapse" id="tms-navbar">
            <ul className="navbar-nav me-auto">
              {NAV_ITEMS.map((item) => (
                <li className="nav-item" key={item.to}>
                  {item.enabled ? (
                    <NavLink
                      className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}
                      to={item.to}
                      end={item.to === '/'}
                    >
                      {item.label}
                    </NavLink>
                  ) : (
                    <span className="nav-link disabled" aria-disabled="true" title="Available in a later step">
                      {item.label}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </nav>

      <main className="flex-grow-1 bg-body-tertiary">
        <div className="container-fluid py-3">
          <Outlet />
        </div>
      </main>

      <footer className="border-top py-2">
        <div className="container-fluid small text-body-secondary d-flex justify-content-between">
          <span>TMS by EBIM</span>
          <span>Transport Management System</span>
        </div>
      </footer>
    </div>
  )
}
