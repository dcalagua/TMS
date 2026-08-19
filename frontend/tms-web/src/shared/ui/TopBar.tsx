import { useTranslation } from 'react-i18next'
import { Breadcrumb } from './Breadcrumb'
import { CompanySelector } from './CompanySelector'
import { LanguageSwitcher } from './LanguageSwitcher'
import { NavSearch } from './NavSearch'
import { SIDEBAR_ID } from './Sidebar'
import { UserMenu } from './UserMenu'
import { IconButton } from './components/IconButton'

export interface TopBarProps {
  /** Reflects the mobile drawer state so the toggle can expose `aria-expanded` honestly. */
  navOpen: boolean
  onToggleNav: () => void
  collapsed: boolean
  onToggleCollapsed: () => void
}

/**
 * Top bar: two intentional halves rather than a row of controls pushed to one end.
 *
 * On the left, where the user is; on the right, who they are and what scope they are working
 * in. The product name is deliberately absent - the sidebar carries it, and repeating it here
 * spent the only part of the bar with room to say something the user does not already know.
 */
export function TopBar({ navOpen, onToggleNav, collapsed, onToggleCollapsed }: TopBarProps) {
  const { t } = useTranslation(['navigation', 'common'])

  return (
    <header className="tms-topbar">
      <IconButton
        icon="bi-list"
        label={t('toggle')}
        className="d-lg-none"
        onClick={onToggleNav}
        aria-controls={SIDEBAR_ID}
        aria-expanded={navOpen}
      />

      <IconButton
        icon={collapsed ? 'bi-chevron-double-right' : 'bi-chevron-double-left'}
        label={collapsed ? t('expand') : t('collapse')}
        className="d-none d-lg-inline-flex"
        onClick={onToggleCollapsed}
        aria-controls={SIDEBAR_ID}
        aria-expanded={!collapsed}
      />

      <Breadcrumb />

      <NavSearch />

      {/* `tms-min-w-0` is what lets the company name actually truncate: without it this flex
          item sizes to its content and pushes the language switch off a narrow phone. */}
      <div className="tms-topbar-actions tms-min-w-0">
        <CompanySelector />
        <span className="tms-topbar-divider d-none d-sm-block" aria-hidden="true" />
        <LanguageSwitcher />
        <span className="tms-topbar-divider d-none d-sm-block" aria-hidden="true" />
        <UserMenu />
      </div>
    </header>
  )
}
