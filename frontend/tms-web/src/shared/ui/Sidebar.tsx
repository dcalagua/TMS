import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { useCompany } from '../company/CompanyContext'
import { NAV_GROUPS } from './navConfig'

export const SIDEBAR_ID = 'tms-sidebar'

function navLinkClass({ isActive }: { isActive: boolean }): string {
  return `nav-link text-white-50${isActive ? ' active text-white fw-semibold' : ''}`
}

export interface SidebarProps {
  /** Whether the mobile drawer is open. Ignored at `lg` and above, where the panel is static. */
  open: boolean
  onRequestClose: () => void
}

/**
 * Responsive sidebar navigation. `.offcanvas-lg` makes this a static column at the `lg`
 * breakpoint and a slide-in drawer below it - one markup, no separate mobile component.
 *
 * The drawer is driven by React state rather than by Bootstrap's `data-bs-*` data API. The
 * data API attaches a delegated click handler on `document` that calls `preventDefault()` on
 * any anchor carrying `data-bs-dismiss`, and it manages instances in a registry belonging to
 * whichever copy of Bootstrap's JS was loaded - neither of which composes with React Router's
 * client-side navigation. Owning the open/closed state here keeps a single source of truth and
 * lets a link do exactly one thing: navigate.
 *
 * Group visibility is gated by capability as UX only; every route it links to still needs the
 * backend's own permission check to actually do anything.
 */
export function Sidebar({ open, onRequestClose }: SidebarProps) {
  const { t } = useTranslation('navigation')
  const { hasCapability, status } = useCompany()

  const visibleGroups = NAV_GROUPS.filter(
    (group) => !group.capability || status !== 'ready' || hasCapability(group.capability),
  )

  return (
    <div
      className={`offcanvas-lg offcanvas-start bg-dark text-white${open ? ' show' : ''}`}
      tabIndex={-1}
      id={SIDEBAR_ID}
      aria-labelledby="tms-sidebar-label"
      style={{ width: 240 }}
    >
      <div className="offcanvas-header d-lg-none">
        <h2 className="offcanvas-title h6 mb-0" id="tms-sidebar-label">
          {t('menu')}
        </h2>
        <button
          type="button"
          className="btn-close btn-close-white"
          onClick={onRequestClose}
          aria-label={t('close')}
        />
      </div>
      <div className="offcanvas-body d-flex flex-column p-0">
        <nav className="nav flex-column py-2" aria-label={t('mainNavigation')}>
          <NavLink to="/" end className={navLinkClass}>
            {t('home')}
          </NavLink>

          {visibleGroups.map((group) => (
            <div key={group.labelKey} className="mt-3">
              <div className="text-uppercase small text-white-50 px-3 mb-1" style={{ letterSpacing: '0.04em' }}>
                {t(group.labelKey)}
              </div>
              {group.items.map((item) => (
                <NavLink key={item.to} to={item.to} className={navLinkClass}>
                  {t(item.labelKey)}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
      </div>
    </div>
  )
}
