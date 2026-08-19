import { NavLink } from 'react-router-dom'
import { useCompany } from '../company/CompanyContext'
import { NAV_GROUPS } from './navConfig'

export const SIDEBAR_ID = 'tms-sidebar'

function navLinkClass({ isActive }: { isActive: boolean }): string {
  return `nav-link text-white-50${isActive ? ' active text-white fw-semibold' : ''}`
}

/**
 * Responsive sidebar navigation. `.offcanvas-lg` makes this a static column at the `lg`
 * breakpoint and a slide-in drawer below it - one markup, no separate mobile component.
 * Group visibility is gated by capability as UX only; every route it links to still needs
 * the backend's own permission check to actually do anything.
 */
export function Sidebar() {
  const { hasCapability, status } = useCompany()

  const visibleGroups = NAV_GROUPS.filter(
    (group) => !group.capability || status !== 'ready' || hasCapability(group.capability),
  )

  return (
    <div
      className="offcanvas-lg offcanvas-start bg-dark text-white"
      tabIndex={-1}
      id={SIDEBAR_ID}
      aria-labelledby="tms-sidebar-label"
      style={{ width: 240 }}
    >
      <div className="offcanvas-header d-lg-none">
        <h2 className="offcanvas-title h6 mb-0" id="tms-sidebar-label">
          Menu
        </h2>
        <button
          type="button"
          className="btn-close btn-close-white"
          data-bs-dismiss="offcanvas"
          data-bs-target={`#${SIDEBAR_ID}`}
          aria-label="Close"
        />
      </div>
      <div className="offcanvas-body d-flex flex-column p-0">
        <nav className="nav flex-column py-2" aria-label="Main navigation">
          <NavLink to="/" end className={navLinkClass} data-bs-dismiss="offcanvas" data-bs-target={`#${SIDEBAR_ID}`}>
            Dashboard
          </NavLink>

          {visibleGroups.map((group) => (
            <div key={group.label} className="mt-3">
              <div className="text-uppercase small text-white-50 px-3 mb-1" style={{ letterSpacing: '0.04em' }}>
                {group.label}
              </div>
              {group.items.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  className={navLinkClass}
                  data-bs-dismiss="offcanvas"
                  data-bs-target={`#${SIDEBAR_ID}`}
                >
                  {item.label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
      </div>
    </div>
  )
}
