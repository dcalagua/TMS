import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { useCompany } from '../company/CompanyContext'
import { HOME_NAV, NAV_GROUPS, type NavLeaf } from './navConfig'

export const SIDEBAR_ID = 'tms-sidebar'

export interface SidebarProps {
  /** Whether the mobile drawer is open. Ignored at `lg` and above, where the panel is static. */
  open: boolean
  /** Whether the desktop panel is collapsed to an icon rail. */
  collapsed: boolean
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
export function Sidebar({ open, collapsed, onRequestClose }: SidebarProps) {
  const { t } = useTranslation('navigation')
  const { hasCapability, status } = useCompany()

  const visibleGroups = NAV_GROUPS.filter(
    (group) => !group.capability || status !== 'ready' || hasCapability(group.capability),
  )

  function renderLink(item: NavLeaf, end = false) {
    const label = t(item.labelKey)
    return (
      <NavLink
        key={item.to}
        to={item.to}
        end={end}
        className={({ isActive }) => `tms-nav-link${isActive ? ' active' : ''}`}
        // The rail hides the label visually, so the link keeps its name through `title`
        // as well; screen readers still read the text, which stays in the DOM.
        title={label}
      >
        <i className={`bi ${item.icon} tms-nav-icon`} aria-hidden="true" />
        <span className="tms-nav-label tms-truncate">{label}</span>
      </NavLink>
    )
  }

  return (
    <div
      className={`offcanvas-lg offcanvas-start tms-sidebar${open ? ' show' : ''}${collapsed ? ' tms-sidebar-rail' : ''}`}
      tabIndex={-1}
      id={SIDEBAR_ID}
      aria-labelledby="tms-sidebar-label"
    >
      <div className="offcanvas-header d-lg-none border-bottom" style={{ borderColor: 'var(--tms-sidebar-border)' }}>
        <h2 className="offcanvas-title h6 mb-0 text-white" id="tms-sidebar-label">
          {t('menu')}
        </h2>
        <button
          type="button"
          className="tms-icon-btn tms-icon-btn-inverse"
          onClick={onRequestClose}
          aria-label={t('close')}
        >
          <i className="bi bi-x-lg" aria-hidden="true" />
        </button>
      </div>

      <div className="offcanvas-body tms-sidebar-body d-flex flex-column p-0 pb-3">
        <nav className="d-flex flex-column pt-2" aria-label={t('mainNavigation')}>
          {renderLink(HOME_NAV, true)}

          {visibleGroups.map((group) => (
            <div key={group.labelKey}>
              <p className="tms-nav-group-label">
                <span className="tms-nav-group-label-text">{t(group.labelKey)}</span>
              </p>
              {group.items.map((item) => renderLink(item))}
            </div>
          ))}
        </nav>
      </div>
    </div>
  )
}
